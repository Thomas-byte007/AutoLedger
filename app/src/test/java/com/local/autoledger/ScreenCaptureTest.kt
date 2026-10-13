package com.local.autoledger

import androidx.test.core.app.ApplicationProvider
import com.local.autoledger.capture.PaymentParser
import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Txn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 无障碍链路：读微信 / 支付宝的支付成功页。
 *
 * 页面文本比通知脏得多（一屏上所有可见文字都会进来），所以这里重点锁住
 * 「什么时候不许记账」—— 中间态页面、账单列表页、转账待确认页都带着金额，
 * 记错了比漏记更难收拾。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenCaptureTest {

    private val t0 = 1_800_000_000_000L

    @Before
    fun setUp() {
        LedgerRepository.init(ApplicationProvider.getApplicationContext())
        LedgerRepository.deleteAll()
    }

    @After
    fun tearDown() {
        LedgerRepository.deleteAll()
    }

    // ------------------------------------------------ 该记的

    @Test
    fun `微信支付成功页`() {
        val p = PaymentParser.parsePaymentScreen(
            PaymentParser.PKG_WECHAT,
            "支付成功 ¥25.00 瑞幸咖啡 零钱 完成"
        )
        assertNotNull(p)
        assertEquals(2500L, p!!.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
        assertEquals(Channel.WECHAT, p.channel)
    }

    @Test
    fun `支付宝成功页金额不带符号`() {
        val p = PaymentParser.parsePaymentScreen(
            PaymentParser.PKG_ALIPAY,
            "支付成功 -12.50 商户：全家便利店 付款方式 花呗 完成"
        )
        assertNotNull(p)
        assertEquals(1250L, p!!.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
        assertEquals(Channel.ALIPAY, p.channel)
    }

    @Test
    fun `带元字的中文金额`() {
        val p = PaymentParser.parsePaymentScreen(
            PaymentParser.PKG_WECHAT, "付款成功 12.00元 微信红包 零钱"
        )
        assertNotNull(p)
        assertEquals(1200L, p!!.amountCents)
    }

    @Test
    fun `收款页面记成收入`() {
        val p = PaymentParser.parsePaymentScreen(
            PaymentParser.PKG_ALIPAY, "收款成功 ¥88.00 已存入余额 完成"
        )
        assertNotNull(p)
        assertEquals(8800L, p!!.amountCents)
        assertEquals(Direction.INCOME, p.direction)
    }

    @Test
    fun `退款页面记成收入`() {
        val p = PaymentParser.parsePaymentScreen(
            PaymentParser.PKG_ALIPAY, "退款成功 ¥8.00 原路退回 花呗"
        )
        assertNotNull(p)
        assertEquals(800L, p!!.amountCents)
        assertEquals(Direction.INCOME, p.direction)
    }

    // ------------------------------------------------ 支付宝「碰一下」

    @Test
    fun `碰一下金额晚一步渲染出来也不会漏`() {
        // 碰一下的支付成功页，金额是从无到有画上去的：窗口事件刚到的那一帧只有这几个字。
        // 这一帧不能记账，但必须被认成「金额还没出来」，服务才会安排重扫。
        val early = "碰一下就支付 支付成功 完成"
        assertNull(PaymentParser.parsePaymentScreen(PaymentParser.PKG_ALIPAY, early))
        assertEquals(true, PaymentParser.incompleteSuccessPage(PaymentParser.PKG_ALIPAY, early))

        // 金额渲染出来之后，同一套文案就能正常解析
        val p = PaymentParser.parsePaymentScreen(
            PaymentParser.PKG_ALIPAY, "碰一下支付成功 -5.00 全家便利店 花呗 完成"
        )
        assertNotNull(p)
        assertEquals(500L, p!!.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
    }

    @Test
    fun `中间态与非支付应用不算未完成的成功页`() {
        // 没有「成功」字样的中间态页面不触发重扫
        assertEquals(
            false,
            PaymentParser.incompleteSuccessPage(PaymentParser.PKG_ALIPAY, "碰一下就支付 付款方式选择")
        )
        // 只有金额没有成功字样，同样不重扫
        assertEquals(
            false,
            PaymentParser.incompleteSuccessPage(PaymentParser.PKG_WECHAT, "¥25.00 瑞幸咖啡 零钱")
        )
        // 非微信 / 支付宝的页面一律不管
        assertEquals(
            false,
            PaymentParser.incompleteSuccessPage("com.taobao.taobao", "支付成功")
        )
    }

    // ------------------------------------------------ 不该记的

    @Test
    fun `中间态页面不记账`() {
        assertNull(
            PaymentParser.parsePaymentScreen(
                PaymentParser.PKG_WECHAT, "确认付款 ¥25.00 请输入支付密码 零钱"
            )
        )
        assertNull(
            PaymentParser.parsePaymentScreen(
                PaymentParser.PKG_ALIPAY, "正在支付 ¥25.00 付款方式 花呗"
            )
        )
    }

    @Test
    fun `转账待对方确认不记账`() {
        assertNull(
            PaymentParser.parsePaymentScreen(
                PaymentParser.PKG_WECHAT, "等待对方确认 ¥50.00 转账 零钱"
            )
        )
    }

    @Test
    fun `账单列表页不记账`() {
        val listPage = buildString {
            append("账单 全部 交易成功 交易关闭 ")
            repeat(12) {
                append("瑞幸咖啡 交易成功 -25.00 2026-09-1${it % 9} 12:3${it % 9} ")
            }
        }
        assertNull(PaymentParser.parsePaymentScreen(PaymentParser.PKG_ALIPAY, listPage))
    }

    @Test
    fun `只有金额没有成功字样不记账`() {
        assertNull(
            PaymentParser.parsePaymentScreen(
                PaymentParser.PKG_WECHAT, "¥25.00 瑞幸咖啡 零钱"
            )
        )
    }

    @Test
    fun `判不出收支方向不记账`() {
        assertNull(
            PaymentParser.parsePaymentScreen(PaymentParser.PKG_ALIPAY, "交易成功 -12.50 明细")
        )
    }

    @Test
    fun `非支付类应用不记账`() {
        assertNull(
            PaymentParser.parsePaymentScreen("com.taobao.taobao", "支付成功 ¥25.00")
        )
    }

    // ------------------------------------------------ 与通知链路的衔接

    @Test
    fun `通知刚记过同一笔时无障碍要让开`() {
        LedgerRepository.insert(
            Txn(
                amountCents = 2500L,
                direction = Direction.EXPENSE,
                categoryKey = "other",
                channel = Channel.WECHAT,
                occurredAt = t0,
                rawText = "微信支付 已支付¥25.00"
            )
        )
        assertEquals(
            true,
            LedgerRepository.hasRecent(Direction.EXPENSE, 2500L, t0 - 60_000L)
        )
        // 金额不同就不算同一笔
        assertEquals(
            false,
            LedgerRepository.hasRecent(Direction.EXPENSE, 2600L, t0 - 60_000L)
        )
        // 时间窗之外也不算
        assertEquals(
            false,
            LedgerRepository.hasRecent(Direction.EXPENSE, 2500L, t0 + 60_000L)
        )
    }
}
