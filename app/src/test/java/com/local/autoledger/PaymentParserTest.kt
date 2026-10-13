package com.local.autoledger

import com.local.autoledger.capture.PaymentParser
import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 支付通知 / 支付成功页文本的解析。纯字符串处理，不需要设备。
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

    // ------------------------------------------------ 凌晨的「被动进账」播报

    @Test
    fun `余额宝昨日收益不记账`() {
        // 理财收益每天凌晨发放，支付宝会推一条「昨日收益已到账」。
        // 它带金额、又写着「到账 / 已存入」，以前会被记成一条没有交易对方的收入。
        val text = "支付宝 余额宝昨日收益 1.23元 已存入余额宝"
        assertNull(PaymentParser.parseNotification(alipay, text))
        assertEquals("理财收益 / 利息类播报，不属于日常收支", PaymentParser.skipReason(alipay, text))
    }

    @Test
    fun `零钱通收益与利息到账都不记账`() {
        assertNull(PaymentParser.parseNotification(wechat, "微信支付 零钱通昨日收益已到账 0.55元"))
        assertNull(PaymentParser.parseNotification(alipay, "支付宝 结息 3.20元 已入账"))
        assertNull(PaymentParser.parseNotification(alipay, "支付宝 基金分红 12.00元 已到账"))
    }

    @Test
    fun `理财过滤不能误伤正常付款`() {
        // 「余额宝 / 零钱通」是合法的扣款渠道，不能被当成理财播报一起丢掉
        val viaYuE = PaymentParser.parseNotification(alipay, "支付宝 在 肯德基 消费 45.00元 余额宝")!!
        assertEquals(4500L, viaYuE.amountCents)
        assertEquals("余额宝", viaYuE.payMethod)

        val viaLingQianTong =
            PaymentParser.parseNotification(wechat, "微信支付 已支付¥25.00 支付方式：零钱通")!!
        assertEquals(2500L, viaLingQianTong.amountCents)
        assertEquals("零钱通", viaLingQianTong.payMethod)
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
}
