package com.local.autoledger

import androidx.test.core.app.ApplicationProvider
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.data.Txn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 年度账单的统计口径。用 Robolectric 起真实 SQLite，不需要设备。
 *
 * 重点保护三件事：
 *  1. 12 根柱子必须齐全，没账目的月份补 0（否则柱状图会画不满一年）；
 *  2. 各个月加起来 = 全年，一级类加起来 = 全年支出，二级类加起来 = 一级类；
 *  3. 下钻走的是「整年」而不是当月，别串到别的月份或方向。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class YearSummaryTest {

    private companion object {
        const val YEAR = 2026
    }

    @Before
    fun setUp() {
        LedgerRepository.init(ApplicationProvider.getApplicationContext())
        LedgerRepository.deleteAll()
    }

    @After
    fun tearDown() {
        LedgerRepository.deleteAll()
        Categories.clearLayer()
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 10): Long =
        TimeFmt.toMillis(LocalDateTime.of(year, month, day, hour, 0))

    private fun txn(
        cents: Long,
        categoryKey: String,
        direction: Direction = Direction.EXPENSE,
        ts: Long = at(YEAR, 5, 10),
        merchant: String = ""
    ) = Txn(
        amountCents = cents,
        direction = direction,
        categoryKey = categoryKey,
        merchant = merchant,
        channel = Channel.MANUAL,
        occurredAt = ts,
        autoCategory = true
    )

    @Test
    fun `空年份也要给出 12 根柱子`() {
        val s = LedgerRepository.yearSummary(YEAR)
        assertEquals(12, s.months.size)
        assertEquals(0L, s.expenseCents)
        assertEquals(0, s.monthCount)
        assertTrue(s.expenseGroups.isEmpty())
        assertEquals(1, s.months.first().yearMonth.monthValue)
        assertEquals(12, s.months.last().yearMonth.monthValue)
    }

    @Test
    fun `全年汇总能对得上每个月的和`() {
        LedgerRepository.save(txn(3000, "dining", ts = at(YEAR, 1, 5), merchant = "海底捞"))
        LedgerRepository.save(txn(2000, "snack", ts = at(YEAR, 1, 20), merchant = "良品铺子"))
        LedgerRepository.save(txn(6000, "rent", ts = at(YEAR, 8, 1), merchant = "房东"))
        LedgerRepository.save(txn(10000, "salary", Direction.INCOME, ts = at(YEAR, 8, 10)))
        // 隔壁年份的记录不能混进来
        LedgerRepository.save(txn(9999, "dining", ts = at(YEAR + 1, 1, 1)))
        LedgerRepository.save(txn(8888, "dining", ts = at(YEAR - 1, 12, 31)))

        val s = LedgerRepository.yearSummary(YEAR)

        assertEquals(11000L, s.expenseCents)
        assertEquals(10000L, s.incomeCents)
        assertEquals(-1000L, s.balanceCents)
        assertEquals(4, s.count)
        assertEquals(
            "各个月加起来必须等于全年",
            s.expenseCents,
            s.months.sumOf { it.expenseCents }
        )
        assertEquals(2, s.monthCount)
        assertEquals("月均只摊在有支出的月份上", 5500L, s.avgExpenseCents)
        assertEquals(3, s.activeDays)

        // 支出最多的是 8 月
        assertEquals(8, s.busiestMonth!!.yearMonth.monthValue)
        assertEquals(6000L, s.busiestMonth!!.expenseCents)
        assertEquals(6000L, s.maxTxn!!.amountCents)
    }

    @Test
    fun `年度也会按一级类与二级类分层汇总`() {
        LedgerRepository.save(txn(3000, "dining", ts = at(YEAR, 3, 2)))
        LedgerRepository.save(txn(2000, "snack", ts = at(YEAR, 3, 3)))
        LedgerRepository.save(txn(1000, "transport", ts = at(YEAR, 4, 4)))
        LedgerRepository.save(txn(7000, "salary", Direction.INCOME, ts = at(YEAR, 4, 10)))

        val s = LedgerRepository.yearSummary(YEAR)

        assertEquals(6000L, s.expenseCents)
        assertEquals(
            "一级类加起来必须等于全年支出",
            s.expenseCents,
            s.expenseGroups.sumOf { it.amountCents }
        )
        val dining = s.expenseGroups.first { it.group.key == Categories.gDining.key }
        assertEquals(5000L, dining.amountCents)
        assertEquals(
            "二级类加起来必须等于一级类",
            dining.amountCents,
            dining.children.sumOf { it.amountCents }
        )
        assertEquals(2, dining.children.size)

        assertEquals(1, s.incomeGroups.size)
        assertEquals(7000L, s.incomeGroups.first().amountCents)
    }

    @Test
    fun `按整年下钻只看这一年这一个方向`() {
        LedgerRepository.save(txn(3000, "dining", ts = at(YEAR, 2, 2), merchant = "星巴克"))
        LedgerRepository.save(txn(2000, "snack", ts = at(YEAR, 2, 3), merchant = "良品铺子"))
        LedgerRepository.save(txn(1000, "snack", ts = at(YEAR + 1, 2, 3), merchant = "明年的零食"))
        LedgerRepository.save(txn(4000, "salary", Direction.INCOME, ts = at(YEAR, 2, 10)))

        val leaves = LedgerRepository.categoryTxnsOfYear(
            YEAR, Direction.EXPENSE, Categories.gDining.key, "snack"
        )
        assertEquals(1, leaves.size)
        assertEquals("良品铺子", leaves.first().merchant)

        val allLeaves = LedgerRepository.categoryTxnsOfYear(YEAR, Direction.EXPENSE)
        assertEquals(2, allLeaves.size)
        assertTrue(allLeaves.all { it.direction == Direction.EXPENSE })
    }

    @Test
    fun `年度账单知道库里有哪些年份`() {
        LedgerRepository.save(txn(1000, "dining", ts = at(2024, 2, 2)))
        LedgerRepository.save(txn(1000, "dining", ts = at(2026, 2, 2)))

        val years = LedgerRepository.years()
        assertTrue(years.containsAll(listOf(2024, 2026)))
        assertEquals("最新的年份排在最前面", 2026, years.first())
    }

    @Test
    fun `上个月那一年的数据是单独算的`() {
        LedgerRepository.save(txn(5000, "dining", ts = at(YEAR - 1, 6, 6)))
        LedgerRepository.save(txn(1000, "dining", ts = at(YEAR, 6, 6)))

        assertEquals(5000L, LedgerRepository.yearSummary(YEAR - 1).expenseCents)
        assertEquals(1000L, LedgerRepository.yearSummary(YEAR).expenseCents)
        assertEquals("¥10.00", Money.yuan(LedgerRepository.yearSummary(YEAR).expenseCents))
    }

    @Test
    fun `月度汇总和年度汇总是同一套口径`() {
        val ym = YearMonth.of(YEAR, 5)
        LedgerRepository.save(txn(1200, "dining", ts = at(YEAR, 5, 10)))
        LedgerRepository.save(txn(800, "dining", ts = at(YEAR, 5, 20)))

        val month = LedgerRepository.monthSummary(ym)
        val year = LedgerRepository.yearSummary(YEAR)
        assertEquals(month.expenseCents, year.expenseCents)
        assertEquals(
            month.expenseGroups.sumOf { it.amountCents },
            year.expenseGroups.sumOf { it.amountCents }
        )
    }
}
