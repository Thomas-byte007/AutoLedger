package com.local.autoledger.data

import androidx.annotation.DrawableRes
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

/**
 * 记录来源渠道。
 *
 * [SMS] 是历史遗留：早期版本支持读银行卡短信，现在已经下线，
 * 但库里可能还留着那个时期的老账目，枚举值必须保留才能正确读出来。
 */
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

/**
 * 一级类（分类分组）。例如「饮食」下面挂着「餐饮 / 零食 / 饮料」。
 *
 * 一级类的 key 与二级类的 key 都落进数据库，一旦发布就不能再改名，
 * 展示名、颜色、图标可以随意调整。
 */
data class CategoryGroup(
    val key: String,
    val label: String,
    val colorRes: Int,
    val direction: Direction,
    /** 图标名，见 [CategoryIconCatalog]；内置一级类的图标名与 key 同名 */
    val iconName: String = key
) {
    /** 列表里真正画出来的 vector drawable */
    @get:DrawableRes
    val iconRes: Int get() = CategoryIconCatalog.resOf(iconName, group = true)
}

/**
 * 二级类（记账时真正选中的那一项）。
 *
 * [iconName] 只是「图标的名字」，真正的 drawable 由 [CategoryIconCatalog] 解析，
 * 这样图标名可以跟着分类一起存进数据库（换机、升级都不会变），
 * 而不会像资源 id 那样随打包漂移。内置二级类的图标名与 key 同名。
 */
data class Category(
    val key: String,
    val label: String,
    val colorRes: Int,
    val direction: Direction,
    /** 所属一级类 key；空串表示未归类，会回落到该方向的兜底一级类 */
    val group: String = "",
    /** 用户自建分类：可以删除，但内置分类不行 */
    val custom: Boolean = false,
    /** 图标名，见 [CategoryIconCatalog] */
    val iconName: String = key
) {
    /** 列表里真正画出来的 vector drawable */
    @get:DrawableRes
    val iconRes: Int get() = CategoryIconCatalog.resOf(iconName)
}

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

/**
 * 用户对「内置分类」的改动补丁。
 *
 * 内置二级类的 key 写死在代码里（也是老数据库里已经落好的值），不能改名，
 * 所以改名 / 换色 / 换图标 / 改挂一级类这些操作都记成一张补丁表；
 * 只有用户真正改过的字段才不为 null，没动过的字段仍能跟着内置字典升级。
 */
data class CategoryPatch(
    val label: String? = null,
    /** 图标名（见 [CategoryIconCatalog]）；数据库列名沿用 emoji，兼容老库 */
    val iconName: String? = null,
    val colorRes: Int? = null,
    /** 改挂到别的一级类 */
    val group: String? = null,
    val hidden: Boolean = false
)

/** 对一级类的同名补丁 */
data class GroupPatch(
    val label: String? = null,
    val iconName: String? = null,
    val colorRes: Int? = null,
    val hidden: Boolean = false
)

/**
 * 覆盖在内置字典上的「用户层」。
 *
 * - [customs]：用户新建的二级类（整行存在 `category` 表里）；
 * - [patches] / [groupPatches]：对内置分类的改名、换色、换图标、改归属；
 * - [hiddenLeaves] / [hiddenGroups]：被用户删除的内置分类（账目已改挂，这里只负责不再显示）。
 */
data class CategoryLayer(
    val customs: List<Category> = emptyList(),
    val patches: Map<String, CategoryPatch> = emptyMap(),
    val hiddenLeaves: Set<String> = emptySet(),
    val groupPatches: Map<String, GroupPatch> = emptyMap(),
    val hiddenGroups: Set<String> = emptySet()
) {
    val isEmpty: Boolean
        get() = customs.isEmpty() && patches.isEmpty() && hiddenLeaves.isEmpty() &&
            groupPatches.isEmpty() && hiddenGroups.isEmpty()
}

/** 单个分类的月度统计 */
data class CategoryStat(
    val category: Category,
    val amountCents: Long,
    val count: Int,
    val ratio: Float
)

/**
 * 一级类的月度统计。[children] 是它下面各个二级类的明细，
 * 报表页「支出分布」展开一级类时看到的就是这个列表。
 */
data class GroupStat(
    val group: CategoryGroup,
    val amountCents: Long,
    val count: Int,
    /** 占同方向总额的比例 */
    val ratio: Float,
    val children: List<CategoryStat>
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
    /** 按一级类聚合的支出分布（已按金额倒序） */
    val expenseGroups: List<GroupStat> = emptyList(),
    /** 按一级类聚合的收入分布（已按金额倒序） */
    val incomeGroups: List<GroupStat> = emptyList(),
    val topMerchants: List<Pair<String, Long>> = emptyList(),
    val maxTxn: Txn? = null,
    val activeDays: Int = 0
) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

/** 近 N 个月的趋势点 */
data class MonthPoint(val yearMonth: YearMonth, val expenseCents: Long, val incomeCents: Long)

/** 某一年的汇总 */
data class YearSummary(
    val year: Int,
    val expenseCents: Long = 0L,
    val incomeCents: Long = 0L,
    val count: Int = 0,
    val activeDays: Int = 0,
    /** 按一级类聚合的支出分布（已按金额倒序） */
    val expenseGroups: List<GroupStat> = emptyList(),
    /** 按一级类聚合的收入分布（已按金额倒序） */
    val incomeGroups: List<GroupStat> = emptyList(),
    /** 12 个月的收支，按 1~12 月正序 */
    val months: List<MonthPoint> = emptyList(),
    val topMerchants: List<Pair<String, Long>> = emptyList(),
    val maxTxn: Txn? = null,
    /** 支出最多的那个月 */
    val busiestMonth: MonthPoint? = null
) {
    val balanceCents: Long get() = incomeCents - expenseCents

    /** 有账目的月份数 */
    val monthCount: Int get() = months.count { it.expenseCents > 0 || it.incomeCents > 0 }

    /** 月均支出（只摊在有账目的月份上，避免年初看起来虚低） */
    val avgExpenseCents: Long get() {
        val n = months.count { it.expenseCents > 0 }
        return if (n > 0) expenseCents / n else 0L
    }
}

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

    /** 自然年的时间范围 */
    fun yearRange(year: Int): LongRange {
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start..end
    }

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
