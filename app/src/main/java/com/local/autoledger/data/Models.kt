package com.local.autoledger.data

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Direction(val code: Int, val label: String) {
    EXPENSE(0, "支出"),
    INCOME(1, "收入");

    companion object {
        fun of(code: Int): Direction = if (code == 1) INCOME else EXPENSE
        fun ofLabel(text: String?): Direction? = when {
            text == null -> null
            text.contains("支出") || text.contains("支") && text.length == 1 -> EXPENSE
            text.contains("收入") || text.contains("收") && text.length == 1 -> INCOME
            else -> null
        }
    }
}

/** 记录来源渠道 */
enum class Channel(val key: String, val label: String, val short: String) {
    WECHAT("wechat", "微信", "微信"),
    ALIPAY("alipay", "支付宝", "支付宝"),
    SMS("sms", "银行卡短信", "短信"),
    IMPORT("import", "账单导入", "导入"),
    MANUAL("manual", "手动记账", "手动"),
    OTHER("other", "其他", "其他");

    companion object {
        fun of(key: String?): Channel = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

data class Category(
    val key: String,
    val label: String,
    val emoji: String,
    val colorRes: Int,
    val direction: Direction
)

/** 一条账目 */
data class Txn(
    val id: Long = 0L,
    val amountCents: Long,
    val direction: Direction,
    val categoryKey: String,
    val merchant: String = "",
    val payMethod: String = "",
    val channel: Channel = Channel.MANUAL,
    val occurredAt: Long = System.currentTimeMillis(),
    /** true = 引擎自动判定，false = 未识别，需要用户手动分类 */
    val autoCategory: Boolean = false,
    val note: String = "",
    val rawText: String = "",
    val fingerprint: String = ""
) {
    val isPending: Boolean get() = !autoCategory
}

/** 分类规则：关键词 -> 分类 */
data class Rule(
    val id: Long = 0L,
    val keyword: String,
    val categoryKey: String,
    val direction: Direction,
    val priority: Int = 0,
    val builtin: Boolean = false
)

/** 单个分类的月度统计 */
data class CategoryStat(
    val category: Category,
    val amountCents: Long,
    val count: Int,
    val ratio: Float
)

/** 某个月的汇总 */
data class MonthSummary(
    val yearMonth: YearMonth,
    val expenseCents: Long = 0L,
    val incomeCents: Long = 0L,
    val count: Int = 0,
    val pendingCount: Int = 0,
    val expenseStats: List<CategoryStat> = emptyList(),
    val incomeStats: List<CategoryStat> = emptyList(),
    val topMerchants: List<Pair<String, Long>> = emptyList(),
    val maxTxn: Txn? = null,
    val activeDays: Int = 0
) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

/** 近 N 个月的趋势点 */
data class MonthPoint(val yearMonth: YearMonth, val expenseCents: Long, val incomeCents: Long)

object TimeFmt {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun toLocal(ts: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), zone)
    fun toDate(ts: Long): LocalDate = toLocal(ts).toLocalDate()
    fun toMillis(dt: LocalDateTime): Long = dt.atZone(zone).toInstant().toEpochMilli()

    fun monthRange(ym: YearMonth): LongRange {
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start..end
    }

    fun dayStartMs(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private val dayFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val fullFmt = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
    private val hmFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val detailFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun dayLabel(date: LocalDate): String = date.format(dayFmt)
    fun monthLabel(ym: YearMonth): String = ym.format(fullFmt)
    fun hm(ts: Long): String = toLocal(ts).format(hmFmt)
    fun detail(ts: Long): String = toLocal(ts).format(detailFmt)
    fun monthRangeLabel(ym: YearMonth): String = ym.format(fullFmt)
}

object Money {
    private val fmt = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(Locale.US))

    /** 1234 分 -> "12.34" */
    fun plain(cents: Long): String = fmt.format(cents / 100.0)

    /** 1234 分 -> "¥12.34" */
    fun yuan(cents: Long): String = "¥" + plain(cents)

    /** 带正负号：支出为 -，收入为 + */
    fun signed(cents: Long, direction: Direction): String =
        (if (direction == Direction.EXPENSE) "-" else "+") + plain(cents)

    /** 解析 "12.34" / "¥12.34" / "1,234.5" -> 分 */
    fun parseToCents(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.replace(",", "").replace("¥", "").replace("￥", "")
            .replace("元", "").trim()
        val v = cleaned.toDoubleOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return Math.round(v * 100)
    }
}
