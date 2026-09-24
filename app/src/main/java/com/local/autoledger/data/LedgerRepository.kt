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
        // helper 换了一个，规则缓存也必须跟着失效（否则单测里第二次 init 后分类会全部落空）
        rulesLoaded = false
        refreshCategories()
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

    /** 把数据库里的自建分类与内置补丁灌进分类字典（启动时、任何一次改动后各调一次） */
    @Synchronized
    fun refreshCategories() {
        val db = helper.readableDatabase

        // 注：这两张表里存图标的列历史上叫 emoji（那时确实存 emoji），
        // 现在存的是图标名；老库里残留的 emoji 由 CategoryIconCatalog 兼容映射。
        val customs = ArrayList<Category>()
        db.rawQuery(
            "SELECT key, label, emoji, group_key, direction, color_name FROM category " +
                "ORDER BY sort_order, created_at, key",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val direction = Direction.of(c.getInt(4))
                val groupKey = c.getString(3).orEmpty().let {
                    if (Categories.groupByKey(it)?.direction == direction) {
                        it
                    } else {
                        Categories.fallbackGroupOf(direction).key
                    }
                }
                customs += Category(
                    key = key,
                    label = c.getString(1).orEmpty(),
                    colorRes = Categories.colorResOf(c.getString(5)),
                    direction = direction,
                    group = groupKey,
                    custom = true,
                    iconName = CategoryIconCatalog.normalize(c.getString(2))
                )
            }
        }

        val patches = HashMap<String, CategoryPatch>()
        val groupPatches = HashMap<String, GroupPatch>()
        val hiddenLeaves = HashSet<String>()
        val hiddenGroups = HashSet<String>()
        db.rawQuery(
            "SELECT key, level, label, emoji, color_name, group_key, hidden FROM category_override",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val level = c.getInt(1)
                val label = c.getString(2).orEmpty().takeIf { it.isNotBlank() }
                val icon = c.getString(3).orEmpty().takeIf { it.isNotBlank() }
                val colorRes = c.getString(4).orEmpty()
                    .takeIf { it.isNotBlank() }?.let { Categories.colorResOf(it) }
                val hidden = c.getInt(6) == 1
                when (level) {
                    LedgerDb.LEVEL_GROUP -> if (hidden) {
                        hiddenGroups += key
                    } else {
                        groupPatches[key] = GroupPatch(label, icon, colorRes)
                    }
                    else -> if (hidden) {
                        hiddenLeaves += key
                    } else {
                        patches[key] = CategoryPatch(
                            label = label,
                            iconName = icon,
                            colorRes = colorRes,
                            group = c.getString(5).orEmpty().takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
        }

        Categories.applyLayer(
            CategoryLayer(
                customs = customs,
                patches = patches,
                hiddenLeaves = hiddenLeaves,
                groupPatches = groupPatches,
                hiddenGroups = hiddenGroups
            )
        )
    }

    fun customCategories(): List<Category> = Categories.customCategories

    /**
     * 新建一个二级类。key 由「前缀 + 时间戳」拼出来，
     * 与内置 key 不会撞车，也不用担心中文分类名进不了 SQL。
     *
     * @return 新分类；名字为空或所属一级类不合法时返回 null
     */
    @Synchronized
    fun addCustomCategory(
        label: String,
        icon: String,
        colorName: String,
        groupKey: String,
        direction: Direction
    ): Category? {
        val name = label.trim().take(6)
        if (name.isEmpty()) return null
        val group = Categories.groupByKey(groupKey)?.takeIf { it.direction == direction } ?: return null
        if (Categories.of(direction).any { it.label == name && it.group == group.key }) return null

        val key = Categories.CUSTOM_PREFIX + System.currentTimeMillis().toString(36)
        val cv = ContentValues().apply {
            put("key", key)
            put("label", name)
            put("emoji", CategoryIconCatalog.normalize(icon))
            put("group_key", group.key)
            put("direction", direction.code)
            put("color_name", colorName)
            put("sort_order", 100)
            put("created_at", System.currentTimeMillis())
        }
        if (helper.writableDatabase.insert("category", null, cv) <= 0L) return null
        refreshCategories()
        return Categories.byKeyOrNull(key, direction)
    }

    /**
     * 修改一个二级类：改名 / 换图标 / 换色 / 挪到别的一级类下。
     * 内置分类改不动 key，所以记成补丁；自建分类直接改 `category` 行。
     *
     * @return null 表示这个 key 在当前方向下不存在
     */
    @Synchronized
    fun updateCategory(
        key: String,
        direction: Direction,
        label: String,
        icon: String,
        colorName: String,
        groupKey: String
    ): Category? {
        val base = Categories.byKeyOrNull(key, direction) ?: return null
        val name = label.trim().take(6)
        if (name.isEmpty()) return null
        val group = Categories.groupByKey(groupKey)?.takeIf { it.direction == direction } ?: return null
        if (Categories.of(direction).any { it.key != key && it.label == name && it.group == group.key }) {
            return null // 同一个一级类下已经有一个同名分类了
        }
        val iconName = CategoryIconCatalog.normalize(icon)

        val db = helper.writableDatabase
        if (base.custom) {
            db.update(
                "category",
                ContentValues().apply {
                    put("label", name)
                    put("emoji", iconName)
                    put("color_name", colorName)
                    put("group_key", group.key)
                },
                "key=?",
                arrayOf(key)
            )
        } else {
            putOverride(
                key, LedgerDb.LEVEL_LEAF,
                ContentValues().apply {
                    put("label", name)
                    put("emoji", iconName)
                    put("color_name", colorName)
                    put("group_key", group.key)
                    put("hidden", 0)
                }
            )
        }
        refreshCategories()
        return Categories.byKeyOrNull(key, direction)
    }

    /** 改一级类的展示名 / 图标 / 颜色。内置一级类同样走补丁表 */
    @Synchronized
    fun updateCategoryGroup(
        key: String,
        label: String,
        icon: String,
        colorName: String
    ): CategoryGroup? {
        val base = Categories.groupByKey(key) ?: return null
        val name = label.trim().take(6)
        if (name.isEmpty()) return null
        putOverride(
            key, LedgerDb.LEVEL_GROUP,
            ContentValues().apply {
                put("label", name)
                put("emoji", CategoryIconCatalog.normalize(icon, group = true))
                put("color_name", colorName)
                put("hidden", 0)
            }
        )
        refreshCategories()
        return Categories.groupByKey(base.key)
    }

    /**
     * 删除一个二级类（内置 / 自建都行），它名下的账目改挂到该方向的兜底分类
     * （「其他」/「其他收入」），记录不会丢。
     *
     * @return 被改挂的账目数；-1 表示这个 key 不存在（例如已经删过了）
     */
    @Synchronized
    fun deleteCategory(key: String, direction: Direction): Int {
        val base = Categories.byKeyOrNull(key, direction) ?: return -1
        val db = helper.writableDatabase
        val moved = moveTxnsToFallback(key, direction)

        if (base.custom) {
            db.delete("category", "key=?", arrayOf(key))
            // 自建分类连带清掉它自己的自定义关键词，免得留下指向空分类的规则
            db.delete("rule", "category_key=? AND builtin=0", arrayOf(key))
        } else {
            putOverride(key, LedgerDb.LEVEL_LEAF, ContentValues().apply { put("hidden", 1) })
        }
        refreshCategories()
        return moved
    }

    /**
     * 删除一个一级类：先把它下面的二级类整体挪到该方向的兜底一级类，再隐藏它自己。
     * 账目一条都不会丢，只是换了个地方待着。
     */
    @Synchronized
    fun deleteCategoryGroup(key: String, direction: Direction): Int {
        val group = Categories.groupByKey(key)?.takeIf { it.direction == direction } ?: return -1
        val target = Categories.fallbackGroupOf(direction).key
        if (target == group.key) return -1 // 兜底一级类不能删

        Categories.childrenOf(group.key, direction).forEach { child ->
            val db = helper.writableDatabase
            if (child.custom) {
                db.update(
                    "category",
                    ContentValues().apply { put("group_key", target) },
                    "key=?",
                    arrayOf(child.key)
                )
            } else {
                putOverride(
                    child.key, LedgerDb.LEVEL_LEAF,
                    ContentValues().apply { put("group_key", target) }
                )
            }
        }
        putOverride(key, LedgerDb.LEVEL_GROUP, ContentValues().apply { put("hidden", 1) })
        refreshCategories()
        return 0
    }

    /**
     * 恢复出厂字典：清空所有对内置分类的改动与删除，自建分类保留。
     *
     * @return 恢复出来的内置分类 + 一级类个数
     */
    @Synchronized
    fun restoreBuiltinCategories(): Int {
        val db = helper.writableDatabase
        val restored = Categories.currentLayer.hiddenLeaves.size +
            Categories.currentLayer.patches.size +
            Categories.currentLayer.hiddenGroups.size +
            Categories.currentLayer.groupPatches.size
        db.delete("category_override", null, null)
        refreshCategories()
        return restored
    }

    /** 账目改挂到该方向的兜底分类 */
    private fun moveTxnsToFallback(key: String, direction: Direction): Int {
        val fallback = Categories.fallbackOf(direction)
        return helper.writableDatabase.update(
            "txn",
            ContentValues().apply {
                put("category_key", fallback.key)
                put("auto_category", 0)
            },
            "category_key=?",
            arrayOf(key)
        )
    }

    private fun putOverride(key: String, level: Int, values: ContentValues) {
        val cv = ContentValues(values).apply {
            put("key", key)
            put("level", level)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict(
            "category_override", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun customCategoryCount(): Int {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM category", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 用户对内置分类做了多少处改动（管理页「恢复内置分类」的按钮要不要亮） */
    fun overrideCount(): Int {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM category_override", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
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
     * 最近是否已经记过「同方向、同金额」的一笔。
     *
     * 无障碍链路专用：它读的是支付成功页，和通知链路看到的是同一笔钱，
     * 但两者的 channel 相同、raw_text 又不一样，走不进 rejectReason 的判重。
     * 于是让无障碍晚一步出场，先问一句「最近有没有同额记录」，有就让开，
     * 省得给账目表加一列来源。
     */
    @Synchronized
    fun hasRecent(direction: Direction, amountCents: Long, sinceMs: Long): Boolean {
        val db = helper.readableDatabase
        db.rawQuery(
            "SELECT id FROM txn WHERE direction=? AND amount_cents=? AND occurred_at>=? LIMIT 1",
            arrayOf(direction.code.toString(), amountCents.toString(), sinceMs.toString())
        ).use { return it.moveToFirst() }
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
        // 「清空全部数据」= 恢复出厂，自建分类也一并清掉，否则会剩下一堆空分类
        helper.writableDatabase.delete("category", null, null)
        refreshCategories()
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

    /** 今天已经支出多少分（后台常驻通知文案用） */
    fun todayExpenseCents(): Long {
        val today = java.time.LocalDate.now()
        val r = TimeFmt.dayStartMs(today)..(TimeFmt.dayStartMs(today.plusDays(1)) - 1)
        return between(r.first, r.last)
            .filter { it.direction == Direction.EXPENSE }
            .sumOf { it.amountCents }
    }

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

    /** 某个二级类下有多少笔账目（删分类前给用户看一眼影响面） */
    fun countOf(categoryKey: String): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM txn WHERE category_key=?", arrayOf(categoryKey)
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

        val topMerchants = topMerchantsOf(expenses)

        return MonthSummary(
            yearMonth = ym,
            expenseCents = expenseTotal,
            incomeCents = incomeTotal,
            count = list.size,
            pendingCount = list.count { it.isPending },
            expenseStats = statsOf(expenses, expenseTotal),
            incomeStats = statsOf(incomes, incomeTotal),
            expenseGroups = groupStatsOf(expenses, expenseTotal, Direction.EXPENSE),
            incomeGroups = groupStatsOf(incomes, incomeTotal, Direction.INCOME),
            topMerchants = topMerchants,
            maxTxn = expenses.maxByOrNull { it.amountCents },
            activeDays = expenses.map { TimeFmt.toDate(it.occurredAt) }.distinct().size
        )
    }

    /** 花钱最多的对象 TOP [limit] */
    private fun topMerchantsOf(expenses: List<Txn>, limit: Int = 5): List<Pair<String, Long>> =
        expenses
            .filter { it.merchant.isNotBlank() }
            .groupBy { it.merchant }
            .map { (m, rows) -> m to rows.sumOf { it.amountCents } }
            .sortedByDescending { it.second }
            .take(limit)

    /**
     * 按一级类汇总，并把每个一级类下面的二级类明细一起带上。
     * 报表页「支出分布」展开一级类时看到的就是 [GroupStat.children]。
     */
    private fun groupStatsOf(
        src: List<Txn>,
        total: Long,
        direction: Direction
    ): List<GroupStat> =
        src.groupBy { Categories.groupOf(it.categoryKey, direction).key }
            .map { (groupKey, rows) ->
                val amount = rows.sumOf { it.amountCents }
                GroupStat(
                    group = Categories.groupByKey(groupKey) ?: Categories.fallbackGroupOf(direction),
                    amountCents = amount,
                    count = rows.size,
                    ratio = if (total > 0) amount.toFloat() / total else 0f,
                    children = rows.groupBy { it.categoryKey }
                        .map { (key, sub) ->
                            val subAmount = sub.sumOf { it.amountCents }
                            CategoryStat(
                                category = Categories.byKey(key, direction),
                                amountCents = subAmount,
                                count = sub.size,
                                ratio = if (total > 0) subAmount.toFloat() / total else 0f
                            )
                        }
                        .sortedByDescending { it.amountCents }
                )
            }
            .sortedByDescending { it.amountCents }

    /**
     * 按分类捞某个自然月的账目，供报表下钻用。
     *
     * [categoryKey] 不为空时只看这一个二级类；否则看 [groupKey] 下面所有二级类。
     * 两个都为 null 就是该方向的全部流水。
     */
    fun categoryTxns(
        ym: YearMonth,
        direction: Direction,
        groupKey: String? = null,
        categoryKey: String? = null
    ): List<Txn> = monthTxns(ym).filter { t ->
        if (t.direction != direction) return@filter false
        when {
            categoryKey != null -> t.categoryKey == categoryKey
            groupKey != null -> Categories.byKey(t.categoryKey, direction).group == groupKey
            else -> true
        }
    }

    /**
     * 「最近使用」的分类：按最近记账时间倒序取用过的二级类，
     * 只保留当前方向下还有效的，避免自建分类被删掉后还留在列表里。
     */
    fun recentCategories(direction: Direction, limit: Int = 8): List<Category> {
        val seen = LinkedHashSet<String>()
        query(null, null, "occurred_at DESC", "400").forEach { t ->
            if (seen.size >= limit * 4) return@forEach
            if (t.direction != direction) return@forEach
            if (Categories.byKeyOrNull(t.categoryKey, direction) != null) seen += t.categoryKey
        }
        return seen.mapNotNull { Categories.byKeyOrNull(it, direction) }.take(limit)
    }

    /** 近 [months] 个月的趋势（含当月），按时间正序 */
    fun trend(months: Int = 6, endMonth: YearMonth = YearMonth.now()): List<MonthPoint> {        val start = endMonth.minusMonths((months - 1).toLong())
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

    // ------------------------------------------------------------------ 年度账单

    /** 库里有账目的年份，倒序（新的一年在最前） */
    fun years(): List<Int> {
        val out = ArrayList<Int>()
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT strftime('%Y', occurred_at / 1000, 'unixepoch', 'localtime') " +
                "FROM txn ORDER BY 1 DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.toIntOrNull()?.let { out += it }
            }
        }
        return out
    }

    fun yearTxns(year: Int): List<Txn> {
        val r = TimeFmt.yearRange(year)
        return between(r.first, r.last)
    }

    /** 某一年的一级类账目：一级类下钻 / 二级类下钻都用它 */
    fun categoryTxnsOfYear(
        year: Int,
        direction: Direction,
        groupKey: String? = null,
        categoryKey: String? = null
    ): List<Txn> = yearTxns(year).filter { t ->
        if (t.direction != direction) return@filter false
        when {
            categoryKey != null -> t.categoryKey == categoryKey
            groupKey != null -> Categories.byKey(t.categoryKey, direction).group == groupKey
            else -> true
        }
    }

    fun yearSummary(year: Int): YearSummary {
        val list = yearTxns(year)
        val months = emptyMonths(year, list)
        if (list.isEmpty()) return YearSummary(year, months = months)

        val expenses = list.filter { it.direction == Direction.EXPENSE }
        val incomes = list.filter { it.direction == Direction.INCOME }
        val expenseTotal = expenses.sumOf { it.amountCents }
        val incomeTotal = incomes.sumOf { it.amountCents }

        return YearSummary(
            year = year,
            expenseCents = expenseTotal,
            incomeCents = incomeTotal,
            count = list.size,
            expenseGroups = groupStatsOf(expenses, expenseTotal, Direction.EXPENSE),
            incomeGroups = groupStatsOf(incomes, incomeTotal, Direction.INCOME),
            months = months,
            topMerchants = topMerchantsOf(expenses, 5),
            maxTxn = expenses.maxByOrNull { it.amountCents },
            busiestMonth = months.filter { it.expenseCents > 0 }.maxByOrNull { it.expenseCents },
            activeDays = expenses.map { TimeFmt.toDate(it.occurredAt) }.distinct().size
        )
    }

    /** 一年的 12 根柱子，没账目的月份补 0，避免柱状图画不满 12 格 */
    private fun emptyMonths(year: Int, list: List<Txn> = emptyList()): List<MonthPoint> {
        val grouped = list.groupBy { YearMonth.from(TimeFmt.toDate(it.occurredAt)) }
        return (1..12).map { m ->
            val ym = YearMonth.of(year, m)
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
        sb.append("时间,收支,金额,一级类,二级类,交易对方,账户,来源,备注\r\n")
        query(null, null, "occurred_at ASC").forEach { t ->
            val cat = Categories.byKey(t.categoryKey, t.direction)
            sb.append(TimeFmt.detail(t.occurredAt)).append(',')
                .append(t.direction.label).append(',')
                .append(Money.plain(t.amountCents)).append(',')
                .append(Categories.groupOf(t.categoryKey, t.direction).label).append(',')
                .append(cat.label).append(',')
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
