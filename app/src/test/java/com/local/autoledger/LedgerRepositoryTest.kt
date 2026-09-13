package com.local.autoledger

import androidx.test.core.app.ApplicationProvider
import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

/**
 * 数据层判重与删除。用 Robolectric 在电脑上跑真实的 SQLite，不需要设备。
 *
 * 这个类是 v1.0.1 两个真机 bug 的回归防线：
 *  - 连付几笔同金额不能被判重吃掉；
 *  - 删掉的记录不能被通知重放写回来。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTest {

    /** 固定基准时间，避免依赖「现在」 */
    private val t0 = 1_800_000_000_000L
    private val wechatText = "微信支付 已支付¥3.00"

    @Before
    fun setUp() {
        LedgerRepository.init(ApplicationProvider.getApplicationContext())
        LedgerRepository.deleteAll()
    }

    private fun txn(
        cents: Long = 300L,
        at: Long = t0,
        channel: Channel = Channel.WECHAT,
        direction: Direction = Direction.EXPENSE,
        raw: String = wechatText,
        merchant: String = ""
    ) = Txn(
        amountCents = cents,
        direction = direction,
        categoryKey = "other",
        merchant = merchant,
        channel = channel,
        occurredAt = at,
        rawText = raw
    )

    // ------------------------------------------------ 问题二：不能吃掉真实的连续几笔

    @Test
    fun `连着三笔同金额都要记上`() {
        // 老逻辑用「金额 + 方向 + 120 秒」，第 2、3 笔会被当成重复丢掉
        assertTrue(LedgerRepository.insert(txn(at = t0)) > 0)
        assertTrue(LedgerRepository.insert(txn(at = t0 + 35_000)) > 0)
        assertTrue(LedgerRepository.insert(txn(at = t0 + 80_000)) > 0)
        assertEquals(3, LedgerRepository.count())
    }

    @Test
    fun `同一条通知被立刻重放只记一次`() {
        assertTrue(LedgerRepository.insert(txn(at = t0)) > 0)
        assertEquals(-1L, LedgerRepository.insert(txn(at = t0 + 500)))
        assertEquals(1, LedgerRepository.count())
    }

    @Test
    fun `金额不同不会互相吃掉`() {
        assertTrue(LedgerRepository.insert(txn(cents = 1200, at = t0)) > 0)
        assertTrue(LedgerRepository.insert(txn(cents = 1500, at = t0 + 5_000, raw = "微信支付 已支付¥15.00")) > 0)
        assertEquals(2, LedgerRepository.count())
    }

    @Test
    fun `收支方向不同不会互相吃掉`() {
        assertTrue(LedgerRepository.insert(txn(cents = 5000, at = t0)) > 0)
        assertTrue(
            LedgerRepository.insert(
                txn(cents = 5000, at = t0 + 3_000, direction = Direction.INCOME, raw = "微信支付 收款成功 50.00元")
            ) > 0
        )
        assertEquals(2, LedgerRepository.count())
    }

    @Test
    fun `跨来源描述同一笔钱只记一次`() {
        assertTrue(LedgerRepository.insert(txn(cents = 2500, at = t0)) > 0)
        val sms = LedgerRepository.insert(
            txn(cents = 2500, at = t0 + 90_000, channel = Channel.SMS, raw = "95588 | 消费2500元")
        )
        assertEquals(-1L, sms)
        assertEquals(1, LedgerRepository.count())
    }

    @Test
    fun `隔了十分钟的真实两笔都要记上`() {
        assertTrue(LedgerRepository.insert(txn(at = t0)) > 0)
        assertTrue(LedgerRepository.insert(txn(at = t0 + 600_000)) > 0)
        assertEquals(2, LedgerRepository.count())
    }

    // ------------------------------------------------ 问题一：删掉的不许回来

    @Test
    fun `删除后通知重放不会把记录写回来`() {
        val id = LedgerRepository.insert(txn(at = t0))
        assertTrue(id > 0)
        assertEquals(1, LedgerRepository.delete(id))

        // 系统把通知栏里那条付款通知重新投递一遍
        assertEquals(-1L, LedgerRepository.insert(txn(at = t0 + 120_000)))
        assertEquals(0, LedgerRepository.count())
    }

    @Test
    fun `墓碑只拦原文完全一致的重放`() {
        val id = LedgerRepository.insert(txn(cents = 300, at = t0))
        LedgerRepository.delete(id)
        // 金额一样但原文不同 —— 是另一笔消费，不能跟着被拦
        assertTrue(
            LedgerRepository.insert(
                txn(cents = 300, at = t0 + 60_000, raw = "微信支付 向 便利店 付款 3.00元")
            ) > 0
        )
    }

    @Test
    fun `超出墓碑窗口后允许重新入账`() {
        val id = LedgerRepository.insert(txn(at = t0))
        LedgerRepository.delete(id)
        // 40 分钟之后，同原文不再拦截（避免把用户后面真实发生的一笔永久挡掉）
        assertTrue(LedgerRepository.insert(txn(at = t0 + 2_400_000)) > 0)
    }

    @Test
    fun `清空全部数据会连墓碑一起清掉`() {
        val id = LedgerRepository.insert(txn(at = t0))
        LedgerRepository.delete(id)
        LedgerRepository.deleteAll()
        assertTrue(LedgerRepository.insert(txn(at = t0 + 60_000)) > 0)
    }

    @Test
    fun `删除不存在的 id 返回 0`() {
        assertEquals(0, LedgerRepository.delete(99_999L))
    }

    // ------------------------------------------------ 手动记账

    @Test
    fun `手动保存不做判重`() {
        // 用户手动记两笔一模一样的账是合理的（比如两杯同样的咖啡）
        assertTrue(LedgerRepository.save(txn(cents = 1500, at = t0, channel = Channel.MANUAL)) > 0)
        assertTrue(LedgerRepository.save(txn(cents = 1500, at = t0, channel = Channel.MANUAL)) > 0)
        assertEquals(2, LedgerRepository.count())
    }

    @Test
    fun `编辑已有记录不会新增一行`() {
        val id = LedgerRepository.insert(txn(cents = 1500, at = t0))
        val saved = LedgerRepository.save(txn(cents = 1900, at = t0).copy(id = id))
        assertEquals(id, saved)
        assertEquals(1, LedgerRepository.count())
        assertEquals(1900L, LedgerRepository.byId(id)!!.amountCents)
    }

    @Test
    fun `改分类只动分类字段`() {
        val id = LedgerRepository.insert(txn(cents = 1500, at = t0))
        LedgerRepository.updateCategory(id, "dining")
        val t = LedgerRepository.byId(id)!!
        assertEquals("dining", t.categoryKey)
        assertEquals(1500L, t.amountCents)
    }

    // ------------------------------------------------ 月度统计

    @Test
    fun `月度统计按分类汇总金额与占比`() {
        val now = System.currentTimeMillis()
        LedgerRepository.save(
            txn(cents = 3000, at = now, channel = Channel.MANUAL).copy(categoryKey = "dining")
        )
        LedgerRepository.save(
            txn(cents = 1000, at = now, channel = Channel.MANUAL).copy(categoryKey = "transport")
        )
        LedgerRepository.save(
            txn(cents = 5000, at = now, channel = Channel.MANUAL, direction = Direction.INCOME)
                .copy(categoryKey = "salary")
        )

        val s = LedgerRepository.monthSummary(YearMonth.now())
        assertEquals(4000L, s.expenseCents)
        assertEquals(5000L, s.incomeCents)
        assertEquals(1000L, s.balanceCents)
        assertEquals(3, s.count)
        val dining = s.expenseStats.first { it.category.key == "dining" }
        assertEquals(0.75f, dining.ratio, 0.001f)
    }
}
