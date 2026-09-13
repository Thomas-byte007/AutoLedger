package com.local.autoledger

import com.local.autoledger.data.Direction
import com.local.autoledger.data.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 金额格式化与解析。全是纯计算，跑在 JVM 上，不需要设备。 */
class MoneyTest {

    @Test
    fun `千分位格式化`() {
        assertEquals("12.34", Money.plain(1234))
        assertEquals("0.00", Money.plain(0))
        assertEquals("0.01", Money.plain(1))
        assertEquals("1,234,567.89", Money.plain(123_456_789))
    }

    @Test
    fun `带货币符号`() {
        assertEquals("¥12.34", Money.yuan(1234))
    }

    @Test
    fun `支出为负收入为正`() {
        assertEquals("-12.34", Money.signed(1234, Direction.EXPENSE))
        assertEquals("+12.34", Money.signed(1234, Direction.INCOME))
    }

    @Test
    fun `解析用户输入与账单金额`() {
        assertEquals(1234L, Money.parseToCents("12.34"))
        assertEquals(1234L, Money.parseToCents("¥12.34"))
        assertEquals(1234L, Money.parseToCents("￥12.34"))
        assertEquals(123450L, Money.parseToCents("1,234.5"))
        assertEquals(123400L, Money.parseToCents("1234"))
        assertEquals(1L, Money.parseToCents("0.01"))
        assertEquals(100L, Money.parseToCents("1"))
        assertEquals(1234L, Money.parseToCents(" 12.34 "))
    }

    @Test
    fun `废输入一律返回 null 而不是 0`() {
        assertNull(Money.parseToCents(null))
        assertNull(Money.parseToCents(""))
        assertNull(Money.parseToCents("   "))
        assertNull(Money.parseToCents("元"))
        assertNull(Money.parseToCents("abc"))
        assertNull(Money.parseToCents("1.2.3"))
    }

    @Test
    fun `方向码值固定不可改`() {
        // 数据库里存的是 int，改了会让老数据的方向反过来
        assertEquals(0, Direction.EXPENSE.code)
        assertEquals(1, Direction.INCOME.code)
        assertEquals(Direction.EXPENSE, Direction.of(0))
        assertEquals(Direction.INCOME, Direction.of(1))
        assertEquals(Direction.EXPENSE, Direction.of(-1))
    }
}
