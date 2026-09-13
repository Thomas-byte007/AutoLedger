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
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        seedRules(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDeletedTable(db)
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

    private fun seedRules(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            Categories.builtinRules().forEach { r ->
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
        const val VERSION = 2
    }
}

internal fun Rule.toValues(): ContentValues = ContentValues().apply {
    put("keyword", keyword)
    put("category_key", categoryKey)
    put("direction", direction.code)
    put("priority", priority)
    put("builtin", if (builtin) 1 else 0)
}
