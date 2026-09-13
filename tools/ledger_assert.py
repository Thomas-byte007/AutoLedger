#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把从模拟器里拉回来的 AutoLedger 账本 SQLite 打印成 JSON，供 PowerShell 断言。

用法:
    python ledger_assert.py <path-to-ledger.db> [--log <path-to-capture.log>] [--out <path-to-json>]

强烈建议用 --out 写文件而不是靠 stdout：
Windows 上 PowerShell 会用控制台编码（简中是 GBK）去解释子进程的 stdout，
UTF-8 的中文会被拆坏，严重时连 JSON 的引号都会被吃掉，导致 ConvertFrom-Json 失败。
写成文件再按 UTF-8 读就没有这个问题。

输出: 一行 JSON，形如
    {"count": 5, "deleted": 1, "rows": [ ... ], "log": [ ... ]}
"""
import json
import sqlite3
import sys
import os


def read_rows(path):
    conn = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    try:
        conn.row_factory = sqlite3.Row
        cur = conn.execute(
            "SELECT id, amount_cents, direction, category_key, merchant, pay_method, "
            "channel, occurred_at, auto_category, raw_text FROM txn ORDER BY id"
        )
        rows = [dict(r) for r in cur.fetchall()]
        try:
            deleted = conn.execute("SELECT COUNT(*) FROM deleted_txn").fetchone()[0]
        except sqlite3.Error:
            # 老版本库没有墓碑表
            deleted = -1
        return rows, deleted
    finally:
        conn.close()


def read_log(path):
    if not path or not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return [ln.rstrip("\n") for ln in fh if ln.strip()]


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "usage: ledger_assert.py <db> [--log <log>] [--out <json>]"}))
        return 2

    db = sys.argv[1]
    log = None
    out = None
    if "--log" in sys.argv:
        log = sys.argv[sys.argv.index("--log") + 1]
    if "--out" in sys.argv:
        out = sys.argv[sys.argv.index("--out") + 1]

    if not os.path.exists(db):
        print(json.dumps({"error": "db not found: %s" % db}))
        return 2

    try:
        rows, deleted = read_rows(db)
    except sqlite3.Error as exc:
        print(json.dumps({"error": "sqlite: %s" % exc}))
        return 2

    # 数值字段转成普通 int，避免 PowerShell 侧类型别扭
    for r in rows:
        for k in ("id", "amount_cents", "direction", "occurred_at", "auto_category"):
            if r.get(k) is not None:
                r[k] = int(r[k])

    payload = json.dumps(
        {"count": len(rows), "deleted": deleted, "rows": rows, "log": read_log(log)},
        ensure_ascii=False,
    )

    if out:
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(payload)
        print("ok count=%d" % len(rows))
    else:
        print(payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
