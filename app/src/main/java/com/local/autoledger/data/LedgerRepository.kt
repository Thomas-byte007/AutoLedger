package com.local.autoledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.time.YearMonth
import java.util.Locale

/**
 * 账本仓库。所有读写都直接落本机 SQLite，不做任何网络请求。
 * 出于简化，方法都是同步阻塞的，调用方请放在 Dispatchers.IO。
 */
object LedgerRepository {

    /** 同一条通知被反复投递的判重窗口。窗口要短，否则「连续两笔同金额」会被误合并 */
    private const val SAME_SOURCE_WINDOW_SEC = 20L

    /** 跨来源（支付通知 / 银行短信 / 账单导入）判重窗口，要留够短信延迟的余量 */
    private const val CROSS_SOURCE_WINDOW_SEC = 240L

    /** 删除后多久之内拒绝同原文的记录重放。宁可少记（日志里看得见），也不要让删掉的记录自己长回来 */
    private const val SUPPRESS_WINDOW_MS = 30 * 60 * 1000L

    /** 墓碑保留时长 */
    private const val TOMBSTONE_KEEP_MS = 3L * 24 * 60 * 60 * 1000

    private lateinit var helper: LedgerDb

    @Volatile
    private var engine: CategoryEngine = CategoryEngine.EMPTY

    @Volatile
    private var rulesLoaded = false

    fun init(context: Context) {
        helper = LedgerDb(context.applicationContext)
    }

    // ------------------------------------------------------------------ 规则

    @Synchronized
    private fun ensureRules() {
        if (rulesLoaded) return
        var list = rawRules()
        if (list.isEmpty()) {
            seedBuiltinRules()
            list = rawRules()
        }
        engine = CategoryEngine(list)
        rulesLoaded = true
    }

    private fun seedBuiltinRules() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            Categories.builtinRules().forEach {
                db.insertWithOnConflict(
                    "rule", null, it.toValues(), SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun reloadRules() {
        engine = CategoryEngine(rawRules())
        rulesLoaded = true
    }

    private fun rawRules(): List<Rule> {
        val out = ArrayList<Rule>()
        helper.readableDatabase.rawQuery(
            "SELECT id, keyword, category_key, direction, priority, builtin FROM rule", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Rule(
                    id = c.getLong(0),
                    keyword = c.getString(1),
                    categoryKey = c.getString(2),
                    direction = Direction.of(c.getInt(3)),
                    priority = c.getInt(4),
                    builtin = c.getInt(5) == 1
                )
            }
        }
        return out
    }

    fun rules(): List<Rule> {
        ensureRules()
        return rawRules().sortedWith(
            compareByDescending<Rule> { it.priority }.thenBy { it.categoryKey }
        )
    }

    @Synchronized
    fun addRule(keyword: String, categoryKey: String, direction: Direction): Boolean {
        val kw = keyword.trim()
        if (kw.isEmpty()) return false
        val values = Rule(
            keyword = kw,
            categoryKey = categoryKey,
            direction = direction,
            priority = kw.length * 10 + 5, // 自定义规则略优先于同长度内置规则
            builtin = false
        ).toValues()
        val ok = helper.writableDatabase.insertWithOnConflict(
            "rule", null, values, 5 /* REPLACE */
        ) > 0
        ensureRules()
        reloadRules()
        return ok
    }

    @Synchronized
    fun deleteRule(id: Long) {
        helper.writableDatabase.delete("rule", "id=?", arrayOf(id.toString()))
        reloadRules()
    }

    /** 把某分类的自动判定结果回写：所有未分类 / 同商户的记录一并更新 */
    @Synchronized
    fun applyCategoryToMerchant(merchant: String, categoryKey: String, direction: Direction): Int {
        if (merchant.isBlank()) return 0
        val cv = ContentValues().apply {
            put("category_key", categoryKey)
            put("auto_category", 1)
        }
        return helper.writableDatabase.update(
            "txn", cv, "merchant=? AND direction=?",
            arrayOf(merchant, direction.code.toString())
        )
    }

    // ------------------------------------------------------------------ 分类

    fun classify(direction: Direction, merchant: String, rawText: String): CategoryEngine.Match {
        ensureRules()
        return engine.classify(direction, merchant, rawText)
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 插入结果。[id] > 0 表示写入成功，否则 [reason] 说明为什么被丢弃。
     * 采集日志会原样记录这个 reason，方便真机上定位「为什么这笔没记上」。
     */
    data class InsertResult(val id: Long, val reason: String)

    private fun fingerprint(t: Txn): String {
        val raw = listOf(
            t.channel.key, t.direction.code.toString(), t.amountCents.toString(),
            t.merchant.lowercase(Locale.ROOT), (t.occurredAt / 60_000L).toString()
        ).joinToString("|")
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 判断这笔该不该丢，返回 null 表示可以写入。
     *
     * 判重分三档，顺序不能换：
     *  ① 墓碑：用户手动删掉的自动记账记录，不接受同原文的通知重放；
     *  ② 同来源：同一条通知被系统重复投递，要求原文一字不差（窗口很短）；
     *  ③ 跨来源：同一笔钱被通知 / 短信 / 账单分别描述，金额与方向一致即视为重复。
     *
     * ②③ 分开是关键。之前只按「金额 + 收支方向 + 时间窗」判重，
     * 结果用户连着付几笔同金额的款（测试时最常见）只会记下第一笔。
     */
    private fun rejectReason(t: Txn, sameWindowSec: Long, crossWindowSec: Long): String? {
        val db = helper.writableDatabase

        if (t.rawText.isNotBlank()) {
            db.rawQuery(
                "SELECT id FROM deleted_txn WHERE channel=? AND amount_cents=? AND direction=? " +
                    "AND raw_text=? AND occurred_at BETWEEN ? AND ? LIMIT 1",
                arrayOf(
                    t.channel.key, t.amountCents.toString(), t.direction.code.toString(), t.rawText,
                    (t.occurredAt - SUPPRESS_WINDOW_MS).toString(),
                    (t.occurredAt + SUPPRESS_WINDOW_MS).toString()
                )
            ).use { if (it.moveToFirst()) return "这笔已被你删除，忽略通知重放" }

            db.rawQuery(
                "SELECT id FROM txn WHERE channel=? AND amount_cents=? AND direction=? AND raw_text=? " +
                    "AND occurred_at BETWEEN ? AND ? LIMIT 1",
                arrayOf(
                    t.channel.key, t.amountCents.toString(), t.direction.code.toString(), t.rawText,
                    (t.occurredAt - sameWindowSec * 1000).toString(),
                    (t.occurredAt + sameWindowSec * 1000).toString()
                )
            ).use { if (it.moveToFirst()) return "同一条通知重复投递（${sameWindowSec}s 内）" }
        }

        db.rawQuery(
            "SELECT id FROM txn WHERE channel<>? AND amount_cents=? AND direction=? " +
                "AND occurred_at BETWEEN ? AND ? LIMIT 1",
            arrayOf(
                t.channel.key, t.amountCents.toString(), t.direction.code.toString(),
                (t.occurredAt - crossWindowSec * 1000).toString(),
                (t.occurredAt + crossWindowSec * 1000).toString()
            )
        ).use { if (it.moveToFirst()) return "其他来源已记过同一笔（金额与收支方向相同）" }

        return null
    }

    /**
     * 插入一条账目，并说明判重结果。
     *
     * @param dedupWindowSec      跨来源判重的时间窗
     * @param sameSourceWindowSec 同来源（同一条通知被重复投递）的时间窗
     */
    @Synchronized
    fun insertDetailed(
        txn: Txn,
        dedupWindowSec: Long = CROSS_SOURCE_WINDOW_SEC,
        skipDedup: Boolean = false,
        sameSourceWindowSec: Long = SAME_SOURCE_WINDOW_SEC
    ): InsertResult {
        val db = helper.writableDatabase
        if (!skipDedup) {
            rejectReason(txn, sameSourceWindowSec, dedupWindowSec)?.let { return InsertResult(-1L, it) }
        }
        val cv = txn.toValues().apply {
            put("fingerprint", fingerprint(txn))
            put("created_at", System.currentTimeMillis())
        }
        val rowId = db.insert("txn", null, cv)
        return if (rowId > 0L) InsertResult(rowId, "已记账") else InsertResult(-1L, "写入数据库失败")
    }

    /**
     * 插入一条账目。
     *
     * @return 新记录 id；判重命中时返回 -1
     */
    @Synchronized
    fun insert(txn: Txn, dedupWindowSec: Long = CROSS_SOURCE_WINDOW_SEC, skipDedup: Boolean = false): Long =
        insertDetailed(txn, dedupWindowSec, skipDedup).id

    /** UI 里的新增 / 编辑，不做去重 */
    @Synchronized
    fun save(txn: Txn): Long {
        val db = helper.writableDatabase
        val cv = txn.toValues().apply {
            put("fingerprint", fingerprint(txn))
            put("created_at", System.currentTimeMillis())
        }
        return if (txn.id > 0L) {
            db.update("txn", cv, "id=?", arrayOf(txn.id.toString()))
            txn.id
        } else {
            insert(txn, skipDedup = true)
        }
    }

    /** 批量导入，返回 (新增数, 跳过数) */
    @Synchronized
    fun insertBatch(list: List<Txn>, dedupWindowSec: Long = 300): Pair<Int, Int> {
        var added = 0
        var skipped = 0
        helper.writableDatabase.beginTransaction()
        try {
            list.forEach { if (insert(it, dedupWindowSec) > 0) added++ else skipped++ }
            helper.writableDatabase.setTransactionSuccessful()
        } finally {
            helper.writableDatabase.endTransaction()
        }
        return added to skipped
    }

    /**
     * 删除一条账目，并留下「墓碑」。
     *
     * 墓碑的作用：付款通知还挂在通知栏里时，系统可能重新投递一次，
     * 那次重放会带着同样的金额和原文进来。没有墓碑的话，插入判重查不到
     * 已被删掉的行，就会把用户刚删的这笔又写回去 —— 看起来就是「删了还在」。
     *
     * @return 实际删掉的行数，0 表示这条 id 已经不在库里
     */
    @Synchronized
    fun delete(id: Long): Int {
        val db = helper.writableDatabase
        db.rawQuery(
            "SELECT channel, direction, amount_cents, merchant, raw_text, occurred_at FROM txn WHERE id=?",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                val values = ContentValues().apply {
                    put("channel", c.getString(0))
                    put("direction", c.getInt(1))
                    put("amount_cents", c.getLong(2))
                    put("merchant", c.getString(3) ?: "")
                    put("raw_text", c.getString(4) ?: "")
                    put("occurred_at", c.getLong(5))
                    put("deleted_at", System.currentTimeMillis())
                }
                if (!values.getAsString("raw_text").isNullOrBlank()) {
                    db.insert("deleted_txn", null, values)
                }
            }
        }
        val removed = db.delete("txn", "id=?", arrayOf(id.toString()))
        // 墓碑只用来挡通知重放，留三天足够
        db.delete(
            "deleted_txn", "deleted_at<?",
            arrayOf((System.currentTimeMillis() - TOMBSTONE_KEEP_MS).toString())
        )
        return removed
    }

    @Synchronized
    fun deleteAll() {
        helper.writableDatabase.delete("txn", null, null)
        helper.writableDatabase.delete("deleted_txn", null, null)
    }

    @Synchronized
    fun updateCategory(id: Long, categoryKey: String, auto: Boolean = false) {
        val cv = ContentValues().apply {
            put("category_key", categoryKey)
            put("auto_category", if (auto) 1 else 0)
        }
        helper.writableDatabase.update("txn", cv, "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------------ 读取

    fun between(fromMs: Long, toMs: Long): List<Txn> =
        query("occurred_at BETWEEN ? AND ?", arrayOf(fromMs.toString(), toMs.toString()))

    fun monthTxns(ym: YearMonth): List<Txn> {
        val r = TimeFmt.monthRange(ym)
        return between(r.first, r.last)
    }

    fun recent(limit: Int = 20): List<Txn> =
        query(null, null, "occurred_at DESC", limit.toString())

    fun byId(id: Long): Txn? {
        val list = query("id=?", arrayOf(id.toString()))
        return list.firstOrNull()
    }

    fun count(): Int {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM txn", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun pendingCount(): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM txn WHERE auto_category=0", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun oldestOccurredAt(): Long? {
        helper.readableDatabase.rawQuery("SELECT MIN(occurred_at) FROM txn", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun query(
        where: String?,
        args: Array<String>?,
        order: String = "occurred_at DESC",
        limit: String? = null
    ): List<Txn> {
        val sql = buildString {
            append("SELECT * FROM txn")
            if (where != null) append(" WHERE ").append(where)
            append(" ORDER BY ").append(order)
            if (limit != null) append(" LIMIT ").append(limit)
        }
        val out = ArrayList<Txn>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += c.toTxn()
        }
        return out
    }

    private fun Cursor.toTxn(): Txn = Txn(
        id = getLong(getColumnIndexOrThrow("id")),
        amountCents = getLong(getColumnIndexOrThrow("amount_cents")),
        direction = Direction.of(getInt(getColumnIndexOrThrow("direction"))),
        categoryKey = getString(getColumnIndexOrThrow("category_key")) ?: "other",
        merchant = getString(getColumnIndexOrThrow("merchant")) ?: "",
        payMethod = getString(getColumnIndexOrThrow("pay_method")) ?: "",
        channel = Channel.of(getString(getColumnIndexOrThrow("channel"))),
        occurredAt = getLong(getColumnIndexOrThrow("occurred_at")),
        autoCategory = getInt(getColumnIndexOrThrow("auto_category")) == 1,
        note = getString(getColumnIndexOrThrow("note")) ?: "",
        rawText = getString(getColumnIndexOrThrow("raw_text")) ?: ""
    )

    private fun Txn.toValues(): ContentValues = ContentValues().apply {
        put("amount_cents", amountCents)
        put("direction", direction.code)
        put("category_key", categoryKey)
        put("merchant", merchant)
        put("pay_method", payMethod)
        put("channel", channel.key)
        put("occurred_at", occurredAt)
        put("auto_category", if (autoCategory) 1 else 0)
        put("note", note)
        put("raw_text", rawText)
    }

    // ------------------------------------------------------------------ 统计

    fun monthSummary(ym: YearMonth): MonthSummary {
        val list = monthTxns(ym)
        if (list.isEmpty()) return MonthSummary(ym)
        return summarize(ym, list)
    }

    private fun summarize(ym: YearMonth, list: List<Txn>): MonthSummary {
        val expenses = list.filter { it.direction == Direction.EXPENSE }
        val incomes = list.filter { it.direction == Direction.INCOME }
        val expenseTotal = expenses.sumOf { it.amountCents }
        val incomeTotal = incomes.sumOf { it.amountCents }

        fun statsOf(src: List<Txn>, total: Long): List<CategoryStat> =
            src.groupBy { it.categoryKey }
                .map { (key, rows) ->
                    val amount = rows.sumOf { it.amountCents }
                    CategoryStat(
                        category = Categories.byKey(key),
                        amountCents = amount,
                        count = rows.size,
                        ratio = if (total > 0) amount.toFloat() / total else 0f
                    )
                }
                .sortedByDescending { it.amountCents }

        val topMerchants = expenses
            .filter { it.merchant.isNotBlank() }
            .groupBy { it.merchant }
            .map { (m, rows) -> m to rows.sumOf { it.amountCents } }
            .sortedByDescending { it.second }
            .take(5)

        return MonthSummary(
            yearMonth = ym,
            expenseCents = expenseTotal,
            incomeCents = incomeTotal,
            count = list.size,
            pendingCount = list.count { it.isPending },
            expenseStats = statsOf(expenses, expenseTotal),
            incomeStats = statsOf(incomes, incomeTotal),
            topMerchants = topMerchants,
            maxTxn = expenses.maxByOrNull { it.amountCents },
            activeDays = expenses.map { TimeFmt.toDate(it.occurredAt) }.distinct().size
        )
    }

    /** 近 [months] 个月的趋势（含当月），按时间正序 */
    fun trend(months: Int = 6, endMonth: YearMonth = YearMonth.now()): List<MonthPoint> {
        val start = endMonth.minusMonths((months - 1).toLong())
        val range = TimeFmt.monthRange(start)
        val list = between(range.first, TimeFmt.monthRange(endMonth).last)
        val grouped = list.groupBy { YearMonth.from(TimeFmt.toDate(it.occurredAt)) }
        return (0 until months).map { i ->
            val ym = start.plusMonths(i.toLong())
            val rows = grouped[ym].orEmpty()
            MonthPoint(
                yearMonth = ym,
                expenseCents = rows.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents },
                incomeCents = rows.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents }
            )
        }
    }

    /** 导出 CSV（UTF-8 BOM，Excel 直接打开不乱码） */
    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.append("时间,收支,金额,分类,交易对方,支付方式,来源,备注\r\n")
        query(null, null, "occurred_at ASC").forEach { t ->
            sb.append(TimeFmt.detail(t.occurredAt)).append(',')
                .append(t.direction.label).append(',')
                .append(Money.plain(t.amountCents)).append(',')
                .append(Categories.byKey(t.categoryKey).label).append(',')
                .append(csvCell(t.merchant)).append(',')
                .append(csvCell(t.payMethod)).append(',')
                .append(t.channel.label).append(',')
                .append(csvCell(t.note)).append("\r\n")
        }
        return sb.toString()
    }

    private fun csvCell(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
