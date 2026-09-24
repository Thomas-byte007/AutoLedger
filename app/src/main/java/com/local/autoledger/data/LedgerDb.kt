package com.local.autoledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LedgerDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE txn (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_cents  INTEGER NOT NULL,
                direction     INTEGER NOT NULL,
                category_key  TEXT    NOT NULL,
                merchant      TEXT    NOT NULL DEFAULT '',
                pay_method    TEXT    NOT NULL DEFAULT '',
                channel       TEXT    NOT NULL DEFAULT 'other',
                occurred_at   INTEGER NOT NULL,
                auto_category INTEGER NOT NULL DEFAULT 0,
                note          TEXT    NOT NULL DEFAULT '',
                raw_text      TEXT    NOT NULL DEFAULT '',
                fingerprint   TEXT    NOT NULL DEFAULT '',
                created_at    INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_txn_time ON txn(occurred_at DESC)")
        db.execSQL("CREATE INDEX idx_txn_cat  ON txn(category_key)")
        db.execSQL("CREATE INDEX idx_txn_fp   ON txn(fingerprint)")
        db.execSQL(
            """
            CREATE TABLE rule (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                keyword    TEXT    NOT NULL,
                category_key TEXT  NOT NULL,
                direction  INTEGER NOT NULL,
                priority   INTEGER NOT NULL DEFAULT 0,
                builtin    INTEGER NOT NULL DEFAULT 0,
                UNIQUE(keyword, direction)
            )
            """.trimIndent()
        )
        createDeletedTable(db)
        createCategoryTable(db)
        createOverrideTable(db)
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        seedRules(db, onlyMissingCategories = false)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDeletedTable(db)
        if (oldVersion < 3) {
            createCategoryTable(db)
            // v3 把分类从「一层 14 个」重构成「一级类 + 二级类」，关键词的归属也跟着动了。
            // 内置规则的 category_key 必须整体刷新一次，否则「零食」还是被记到「餐饮」上。
            // 用户自己加的规则（builtin=0）原样保留。
            db.execSQL("DELETE FROM rule WHERE builtin=1")
            seedRules(db, onlyMissingCategories = false)
        }
        if (oldVersion < 4) createOverrideTable(db)
    }

    /**
     * 用户自建分类（二级类）。
     *
     * 只存二级类：一级类是代码里固定的 11 个，够用且不会飘。
     * 存 color_name 而不是资源 id —— 资源 id 会随打包漂移，名字不会。
     */
    private fun createCategoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS category (
                key        TEXT PRIMARY KEY,
                label      TEXT    NOT NULL,
                emoji      TEXT    NOT NULL DEFAULT '',
                group_key  TEXT    NOT NULL,
                direction  INTEGER NOT NULL,
                color_name TEXT    NOT NULL DEFAULT '',
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /**
     * 用户手动删掉的自动记账记录会在这里留一份「墓碑」。
     *
     * 通知栏里的那条付款通知在被系统重新投递（分组刷新、监听重连）时，
     * 会带着一模一样的金额和原文再次进来；如果没有墓碑，它就会把用户刚删掉的那笔
     * 重新写回列表 —— 表现就是「删了还在」。
     */
    private fun createDeletedTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS deleted_txn (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                channel      TEXT    NOT NULL,
                direction    INTEGER NOT NULL,
                amount_cents INTEGER NOT NULL,
                merchant     TEXT    NOT NULL DEFAULT '',
                raw_text     TEXT    NOT NULL DEFAULT '',
                occurred_at  INTEGER NOT NULL,
                deleted_at   INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_deleted_time ON deleted_txn(occurred_at DESC)")
    }

    /**
     * 用户对「内置分类 / 一级类」的改动。
     *
     * 内置分类的 key 已经写在老数据里了，不能改，所以改名、换色、换图标、挪一级类
     * 都记成补丁；删除也只是打上 hidden，账目本身改挂到兜底分类。
     * 「恢复内置分类」时把这张表清空即可。
     *
     * @param level 0 = 一级类，1 = 二级类
     */
    private fun createOverrideTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS category_override (
                key        TEXT PRIMARY KEY,
                level      INTEGER NOT NULL DEFAULT 1,
                label      TEXT    NOT NULL DEFAULT '',
                emoji      TEXT    NOT NULL DEFAULT '',
                color_name TEXT    NOT NULL DEFAULT '',
                group_key  TEXT    NOT NULL DEFAULT '',
                hidden     INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    private fun seedRules(db: SQLiteDatabase, onlyMissingCategories: Boolean) {
        val covered: Set<String> = if (!onlyMissingCategories) {
            emptySet()
        } else {
            val set = HashSet<String>()
            db.rawQuery("SELECT DISTINCT category_key FROM rule", null).use { c ->
                while (c.moveToNext()) set += c.getString(0)
            }
            set
        }
        db.beginTransaction()
        try {
            Categories.builtinRules()
                .filterNot { it.categoryKey in covered }
                .forEach { r ->
                    db.insertWithOnConflict(
                        "rule", null, r.toValues(), SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val NAME = "ledger.db"
        const val VERSION = 4

        /** category_override.level：一级类 */
        const val LEVEL_GROUP = 0

        /** category_override.level：二级类 */
        const val LEVEL_LEAF = 1
    }
}

internal fun Rule.toValues(): ContentValues = ContentValues().apply {
    put("keyword", keyword)
    put("category_key", categoryKey)
    put("direction", direction.code)
    put("priority", priority)
    put("builtin", if (builtin) 1 else 0)
}
