package com.local.autoledger

import com.local.autoledger.capture.PaymentParser
import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付通知 / 银行短信的解析。纯字符串处理，不需要设备。
 *
 * 这里的用例就是「自动记账到底认不认得出」的第一道防线：
 * 真机上收到通知却记不上账，先看这些用例还过不过。
 */
class PaymentParserTest {

    private val wechat = PaymentParser.PKG_WECHAT
    private val alipay = PaymentParser.PKG_ALIPAY

    // ------------------------------------------------------------ 微信

    @Test
    fun `微信付款`() {
        val p = PaymentParser.parseNotification(wechat, "微信支付 已支付¥25.00")!!
        assertEquals(2500L, p.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
        assertEquals(Channel.WECHAT, p.channel)
    }

    @Test
    fun `微信付款带支付方式与商户`() {
        val p = PaymentParser.parseNotification(
            wechat, "微信支付 已支付¥36.50 商户名称：星巴克 支付方式：零钱"
        )!!
        assertEquals(3650L, p.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
        assertEquals("星巴克", p.merchant)
        assertEquals("零钱", p.payMethod)
    }

    @Test
    fun `微信向某人付款`() {
        val p = PaymentParser.parseNotification(wechat, "微信支付 向 张小明 付款 88.00元")!!
        assertEquals(8800L, p.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
        assertEquals("张小明", p.merchant)
    }

    @Test
    fun `微信转账对方发来`() {
        val p = PaymentParser.parseNotification(wechat, "微信支付 已收款 来自 李四 的转账 100.00元")!!
        assertEquals(10000L, p.amountCents)
        assertEquals(Direction.INCOME, p.direction)
        assertEquals("李四", p.merchant)
    }

    @Test
    fun `微信红包存入零钱算收入`() {
        val p = PaymentParser.parseNotification(wechat, "微信支付 红包已存入零钱 8.80元")!!
        assertEquals(880L, p.amountCents)
        assertEquals(Direction.INCOME, p.direction)
        assertEquals("红包", p.merchant)
    }

    @Test
    fun `微信转账收款到账算收入`() {
        val p = PaymentParser.parseNotification(wechat, "微信支付 收款到账 200.00元")!!
        assertEquals(20000L, p.amountCents)
        assertEquals(Direction.INCOME, p.direction)
    }

    // ------------------------------------------------------------ 支付宝

    @Test
    fun `支付宝付款`() {
        val p = PaymentParser.parseNotification(alipay, "支付宝 支付成功 25.00元")!!
        assertEquals(2500L, p.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
        assertEquals(Channel.ALIPAY, p.channel)
    }

    @Test
    fun `支付宝在商家消费`() {
        val p = PaymentParser.parseNotification(alipay, "支付宝 在 肯德基 消费 45.00元 花呗")!!
        assertEquals(4500L, p.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
        assertEquals("肯德基", p.merchant)
        assertEquals("花呗", p.payMethod)
    }

    @Test
    fun `支付宝收款成功算收入`() {
        val p = PaymentParser.parseNotification(alipay, "支付宝 收款成功 88.00元")!!
        assertEquals(8800L, p.amountCents)
        assertEquals(Direction.INCOME, p.direction)
    }

    @Test
    fun `退款算收入`() {
        val p = PaymentParser.parseNotification(alipay, "支付宝 退款成功 ¥30.00 已原路退回")!!
        assertEquals(3000L, p.amountCents)
        assertEquals(Direction.INCOME, p.direction)
    }

    // ------------------------------------------------------------ 宁可漏不可错

    @Test
    fun `验证码类通知一律不记`() {
        assertNull(PaymentParser.parseNotification(wechat, "微信支付 验证码 123456，请勿泄露"))
        assertNull(PaymentParser.parseNotification(alipay, "支付宝 登录安全提醒 请勿告诉他人"))
    }

    @Test
    fun `营销与账单提醒不记`() {
        assertNull(PaymentParser.parseNotification(alipay, "支付宝 花呗账单已生成 本月应还 ¥1,200.00"))
        assertNull(PaymentParser.parseNotification(wechat, "微信支付 优惠券到账 满50减10 点击查看"))
    }

    @Test
    fun `未完成的收款不记`() {
        // 对方只是发起转账，钱还没进来，记账会虚增收入
        assertNull(PaymentParser.parseNotification(wechat, "微信支付 待收款 ¥50.00 请你确认收款"))
        assertNull(PaymentParser.parseNotification(wechat, "微信转账 已过期 ¥50.00"))
    }

    @Test
    fun `没有金额不记`() {
        assertNull(PaymentParser.parseNotification(wechat, "微信支付 支付成功"))
        assertNull(PaymentParser.parseNotification(wechat, ""))
    }

    @Test
    fun `没有收支方向不记`() {
        // 注意不能在文本里出现「支付 / 付款 / 消费」这类词，否则方向判定会命中
        assertNull(PaymentParser.parseNotification(wechat, "微信 ¥25.00"))
    }

    @Test
    fun `金额为 0 不记`() {
        assertNull(PaymentParser.parseNotification(wechat, "微信支付 已支付¥0.00"))
    }

    @Test
    fun `非微信支付宝的包名不处理`() {
        assertNull(PaymentParser.parseNotification("com.example.pay", "支付成功 25.00元"))
    }

    // ------------------------------------------------------------ 银行短信

    @Test
    fun `银行消费短信`() {
        val p = PaymentParser.parseSms(
            "95588", "您尾号1234的储蓄卡消费人民币25.00元，商户名称：星巴克", 1_700_000_000_000L
        )!!
        assertEquals(2500L, p.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
        assertEquals(Channel.SMS, p.channel)
        assertEquals("银行卡(尾号1234)", p.payMethod)
        assertEquals("星巴克", p.merchant)
    }

    @Test
    fun `银行入账短信`() {
        val p = PaymentParser.parseSms("95588", "您尾号1234的储蓄卡入账人民币8000.00元", 1L)!!
        assertEquals(800000L, p.amountCents)
        assertEquals(Direction.INCOME, p.direction)
    }

    @Test
    fun `短信里的验证码不记`() {
        assertNull(PaymentParser.parseSms("95588", "您尾号1234的储蓄卡验证码123456，请勿告知他人", 1L))
    }

    @Test
    fun `没写元的数字不会被当金额`() {
        // 「尾号 1234」这种数字不能算钱
        assertNull(PaymentParser.parseSms("95588", "您尾号1234的储蓄卡有一笔待确认交易", 1L))
    }

    @Test
    fun `识别银行发件人`() {
        assertTrue(PaymentParser.looksLikeBankSender("95588"))
        assertTrue(PaymentParser.looksLikeBankSender("95533"))
        assertTrue(PaymentParser.looksLikeBankSender("中国工商银行"))
        assertTrue(!PaymentParser.looksLikeBankSender("10086"))
        assertTrue(!PaymentParser.looksLikeBankSender("10690001"))
        assertTrue(!PaymentParser.looksLikeBankSender(null))
    }
}
