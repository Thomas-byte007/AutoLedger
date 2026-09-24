# -*- coding: utf-8 -*-
"""
生成随手记的分类图标体系。一次跑出两样东西，保证「图标名 <-> 资源文件」永远同步：

  1. app/src/main/res/drawable/ic_cat_<名字>.xml   —— 53 个矢量图标
  2. app/src/main/java/com/local/autoledger/data/CategoryIconCatalog.kt
     —— 名字到资源 id 的映射表 + 老 emoji 兼容表

整套图标是同一套线性规格，想整体换风格只改本文件再重跑一次即可：

  * 24x24 viewBox，图形收在 3..21 的视觉安全区内，缩放后重量一致
  * 只描边不填充：省略 fillColor（透明），strokeColor 固定 #000000
  * 统一 1.7 描边 + 圆角端点 / 圆角连接
  * 不使用 emoji、不引入位图，全部是可在运行时着色的 vector

颜色由调用方用 imageTintList 注入（分类色 / 白色场景），所以这里永远是纯黑描边。

名字约定：
  * 一级类的图标名就是它自己的 key，g_ 打头（g_dining / g_income ...）
  * 二级类的图标名就是它自己的 key（dining / snack / salary ...）

用法：
    python tools/gen_cat_icons.py
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLE_DIR = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
KOTLIN_PATH = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "local", "autoledger",
    "data", "CategoryIconCatalog.kt"
)

STROKE = "1.7"

# ---------------------------------------------------------------------------
# 一级类：图标名 == 一级类 key（g_xxx）
# ---------------------------------------------------------------------------

GROUPS = {
    "g_dining": ("饮食", [
        "M4.5 11.6h15a7.5 7.5 0 0 1-15 0z",
        "M9.6 8.6c0-1.4 1.1-1.8 1.1-3.2",
        "M13.8 8.6c0-1.4 1.1-1.8 1.1-3.2",
    ]),
    "g_transport": ("出行", [
        "M5.5 4.5h13a2 2 0 0 1 2 2v8.4a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2z",
        "M3.5 11.6h17",
        "M7.5 17v2.5",
        "M16.5 17v2.5",
    ]),
    "g_daily": ("日用", [
        "M8.4 9.4a3.6 3.6 0 0 1 7.2 0",
        "M3.6 9.4h16.8",
        "M5.4 9.4l1.3 9.1a1.6 1.6 0 0 0 1.6 1.4h7.4a1.6 1.6 0 0 0 1.6-1.4l1.3-9.1",
    ]),
    "g_shopping": ("购物", [
        "M9 8.2V6.6a3 3 0 0 1 6 0v1.6",
        "M5.6 8.2h12.8a1.6 1.6 0 0 1 1.6 1.8l-.9 8.6a1.7 1.7 0 0 1-1.7 1.5H7.6a1.7 1.7 0 0 1-1.7-1.5l-.9-8.6a1.6 1.6 0 0 1 1.6-1.8z",
    ]),
    "g_school": ("学校", [
        "M3.2 9.6 12 5.6l8.8 4-8.8 4z",
        "M7.2 11.6v4.1c0 1 2.1 2 4.8 2s4.8-1 4.8-2v-4.1",
        "M20.8 9.6v5.2",
    ]),
    "g_home": ("居住", [
        "M4.2 10.6 12 4.2l7.8 6.4V19a1.6 1.6 0 0 1-1.6 1.6H5.8A1.6 1.6 0 0 1 4.2 19z",
        "M10 20.6v-5h4v5",
    ]),
    "g_health": ("医疗", [
        "M6.2 4.4h11.6a1.8 1.8 0 0 1 1.8 1.8v11.6a1.8 1.8 0 0 1-1.8 1.8H6.2a1.8 1.8 0 0 1-1.8-1.8V6.2a1.8 1.8 0 0 1 1.8-1.8z",
        "M12 8.6v6.8",
        "M8.6 12h6.8",
    ]),
    "g_comm": ("通讯", [
        "M4.6 19.4v-2.6",
        "M9.5 19.4v-5.6",
        "M14.4 19.4v-8.6",
        "M19.4 19.4v-11.6",
    ]),
    "g_hobby": ("爱好", [
        "M8 7.6h8a5 5 0 0 1 4.6 7l-.9 2.3a2 2 0 0 1-3.5.4l-1.1-1.7H8.9l-1.1 1.7a2 2 0 0 1-3.5-.4l-.9-2.3a5 5 0 0 1 4.6-7z",
        "M9.2 11v3",
        "M7.7 12.5h3",
        "M15.2 11.4h.01",
        "M16.7 13.4h.01",
    ]),
    "g_other": ("其他", [
        "M20.4 13.5A8.5 8.5 0 0 1 13.5 20.4",
        "M10.5 20.4A8.5 8.5 0 0 1 3.6 13.5",
        "M3.6 10.5A8.5 8.5 0 0 1 10.5 3.6",
        "M13.5 3.6A8.5 8.5 0 0 1 20.4 10.5",
    ]),
    "g_income": ("收入", [
        "M5 6.6h14a2 2 0 0 1 2 2v6.8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8.6a2 2 0 0 1 2-2z",
        "M9.5 12a2.5 2.5 0 1 0 5 0 2.5 2.5 0 1 0-5 0",
        "M6.2 10.4v3.2",
        "M17.8 10.4v3.2",
    ]),
}

# ---------------------------------------------------------------------------
# 二级类：图标名 == 二级类 key
# ---------------------------------------------------------------------------

LEAVES = {
    "dining": ("餐饮", [
        "M6.4 6.3a2.6 3 0 1 0 5.2 0 2.6 3 0 1 0-5.2 0",
        "M9 9.3V20.6",
        "M14 3v5.6a2.5 2.5 0 0 1 5 0V3",
        "M16.5 11.1V20.6",
    ]),
    "snack": ("零食", [
        "M3.6 12a8.4 8.4 0 1 0 16.8 0 8.4 8.4 0 1 0-16.8 0",
        "M9 9.6h.01",
        "M14.8 9h.01",
        "M9.4 15h.01",
        "M15 14.6h.01",
        "M12 12h.01",
    ]),
    "drink": ("饮料", [
        "M5.8 8.2h12.4",
        "M7 8.2l1.1 11a1.6 1.6 0 0 0 1.6 1.4h4.6a1.6 1.6 0 0 0 1.6-1.4l1.1-11",
        "M13.6 8.2 15.8 3.3",
    ]),
    "fruit": ("水果", [
        "M5.6 13.9a6.4 6.4 0 1 0 12.8 0 6.4 6.4 0 1 0-12.8 0",
        "M12 7.5V5.3",
        "M12.4 5.9c.3-1.5 1.5-2.5 3-2.4-.2 1.5-1.4 2.5-3 2.4z",
    ]),
    "dinner": ("聚餐", [
        "M8 3.4h8l-.8 5.6a3.2 3.2 0 0 1-6.4 0z",
        "M12 12.6v8",
        "M8.6 20.6h6.8",
    ]),
    "transport": ("交通", [
        "M7.2 3.6h9.6a2.6 2.6 0 0 1 2.6 2.6v8.6a2.6 2.6 0 0 1-2.6 2.6H7.2a2.6 2.6 0 0 1-2.6-2.6V6.2a2.6 2.6 0 0 1 2.6-2.6z",
        "M8 7.2h8a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1z",
        "M9.2 15h.01",
        "M14.8 15h.01",
        "M8.8 17.4 7 20.6",
        "M15.2 17.4 17 20.6",
    ]),
    "taxi": ("打车", [
        "M7.2 10.4 8.8 6.9a1.6 1.6 0 0 1 1.5-1h3.4a1.6 1.6 0 0 1 1.5 1l1.6 3.5",
        "M4.4 15.4v-2.8a2 2 0 0 1 2-2h11.2a2 2 0 0 1 2 2v2.8a1 1 0 0 1-1 1H5.4a1 1 0 0 1-1-1z",
        "M7.6 16.4v1.6",
        "M16.4 16.4v1.6",
    ]),
    "fuel": ("加油", [
        "M5 20.6V5.4a1.8 1.8 0 0 1 1.8-1.8h4.4A1.8 1.8 0 0 1 13 5.4v15.2",
        "M3.8 20.6h10.4",
        "M6.6 7.2h5.2v3.6H6.6z",
        "M13 9.4h2.4a1.6 1.6 0 0 1 1.6 1.6v6.2a1.4 1.4 0 0 0 2.8 0V9.6l-2-2.4",
    ]),
    "parking": ("停车", [
        "M6.2 3.6h11.6a2.6 2.6 0 0 1 2.6 2.6v11.6a2.6 2.6 0 0 1-2.6 2.6H6.2a2.6 2.6 0 0 1-2.6-2.6V6.2a2.6 2.6 0 0 1 2.6-2.6z",
        "M10 17.4V6.6h3.4a3.4 3.4 0 0 1 0 6.8H10",
    ]),
    "travel": ("旅行", [
        "M9.4 7.4V5.8a1.4 1.4 0 0 1 1.4-1.4h2.4a1.4 1.4 0 0 1 1.4 1.4v1.6",
        "M5.4 7.4h13.2a1.6 1.6 0 0 1 1.6 1.6v9.4a1.6 1.6 0 0 1-1.6 1.6H5.4a1.6 1.6 0 0 1-1.6-1.6V9a1.6 1.6 0 0 1 1.6-1.6z",
        "M9.6 11.4v4.6",
        "M14.4 11.4v4.6",
    ]),
    "grocery": ("日用百货", [
        "M3.4 4.6h2.2l2.2 9.6a1.6 1.6 0 0 0 1.6 1.2h7.8a1.6 1.6 0 0 0 1.6-1.2l1.4-6.2H6.2",
        "M9.4 19.4h.01",
        "M16.6 19.4h.01",
    ]),
    "clothing": ("服饰鞋包", [
        "M9 3.8 5 6.4l1.4 3.2 1.6-.8v8.8a1.6 1.6 0 0 0 1.6 1.6h4.8a1.6 1.6 0 0 0 1.6-1.6V8.8l1.6.8L19 6.4l-4-2.6a3 3 0 0 1-6 0z",
    ]),
    "appliance": ("家用电器", [
        "M9.8 3.6v4",
        "M14.2 3.6v4",
        "M7.4 7.6h9.2v2.6a4.6 4.6 0 0 1-9.2 0z",
        "M12 14.8v5.6",
    ]),
    "express": ("快递物流", [
        "M12 3.6 3.8 8v8L12 20.4 20.2 16V8z",
        "M3.8 8 12 12.4 20.2 8",
        "M12 12.4v8",
    ]),
    "shopping": ("购物", [
        "M11.4 3.6H4.8a1.2 1.2 0 0 0-1.2 1.2v6.6a1.2 1.2 0 0 0 .35.85l8.2 8.2a1.2 1.2 0 0 0 1.7 0l6.6-6.6a1.2 1.2 0 0 0 0-1.7l-8.2-8.2a1.2 1.2 0 0 0-.85-.35z",
        "M7.6 7.6h.01",
    ]),
    "digital": ("数码电子", [
        "M6 5.4h12a1.6 1.6 0 0 1 1.6 1.6v7H4.4V7A1.6 1.6 0 0 1 6 5.4z",
        "M3.2 14h17.6l-1.4 2.2a1.6 1.6 0 0 1-1.4.8H6a1.6 1.6 0 0 1-1.4-.8z",
    ]),
    "beauty": ("美妆护肤", [
        "M9.2 20.4V11.8h5.6v8.6",
        "M10.4 11.8V6.4l3.2-2.6v8z",
    ]),
    "education": ("教育培训", [
        "M12 6.6C10.4 5.2 8.2 4.6 5 4.6a1 1 0 0 0-1 1v10.6a1 1 0 0 0 1 1c3 0 5.2.5 7 1.9 1.8-1.4 4-1.9 7-1.9a1 1 0 0 0 1-1V5.6a1 1 0 0 0-1-1c-3.2 0-5.4.6-7 2z",
        "M12 6.6v12.5",
    ]),
    "stationery": ("文具书籍", [
        "M4.6 19.4l.9-4.2L15.8 4.9a2 2 0 0 1 2.8 0l.5.5a2 2 0 0 1 0 2.8L8.8 18.5z",
        "M14.2 6.5l3.3 3.3",
        "M4.6 19.4l4.2-.9",
    ]),
    "exam": ("考试报名", [
        "M7.4 5.4H6.2a1.8 1.8 0 0 0-1.8 1.8v11.6a1.8 1.8 0 0 0 1.8 1.8h11.6a1.8 1.8 0 0 0 1.8-1.8V7.2a1.8 1.8 0 0 0-1.8-1.8h-1.2",
        "M9.4 3.6h5.2v3.6H9.4z",
        "M9 14.2l2.2 2.2 4-4.4",
    ]),
    "housing": ("居住物业", [
        "M5.6 20.6V4.8a1.2 1.2 0 0 1 1.2-1.2h7.4a1.2 1.2 0 0 1 1.2 1.2v15.8",
        "M15.4 9.6h2.6a1.2 1.2 0 0 1 1.2 1.2v9.8",
        "M3.4 20.6h17.2",
        "M8.4 7.4h3.8",
        "M8.4 11h3.8",
        "M8.4 14.6h3.8",
    ]),
    "rent": ("房租", [
        "M15.8 3.6a4.6 4.6 0 1 0 0 9.2 4.6 4.6 0 0 0 0-9.2z",
        "M12.6 11.6 5.5 18.7",
        "M5.5 18.7v2.6",
        "M8.6 15.6v2.6",
    ]),
    "utility": ("水电燃气", [
        "M9.2 17.2a6.4 6.4 0 1 1 5.6 0v2a1.2 1.2 0 0 1-1.2 1.2h-3.2a1.2 1.2 0 0 1-1.2-1.2z",
        "M10.4 20.4v1.4h3.2v-1.4",
    ]),
    "furniture": ("家居家具", [
        "M6 11.4V8.2a2.2 2.2 0 0 1 2.2-2.2h7.6A2.2 2.2 0 0 1 18 8.2v3.2",
        "M4.4 11.4h15.2a1.4 1.4 0 0 1 1.4 1.4v3.4a1.4 1.4 0 0 1-1.4 1.4H4.4A1.4 1.4 0 0 1 3 16.2v-3.4a1.4 1.4 0 0 1 1.4-1.4z",
        "M6.2 17.6v1.8",
        "M17.8 17.6v1.8",
    ]),
    "medical": ("医疗", [
        "M7 3.8v4.8a5 5 0 0 0 10 0V3.8",
        "M5.4 3.8h3.2",
        "M15.4 3.8h3.2",
        "M12 13.6v3.2a3.6 3.6 0 0 0 7.2 0v-2.6",
        "M17.2 11.6a2 2 0 1 0 4 0 2 2 0 1 0-4 0",
    ]),
    "medicine": ("药品", [
        "M13.2 4.2 4.2 13.2a3.4 3.4 0 0 0 4.8 4.8l9-9a3.4 3.4 0 0 0-4.8-4.8z",
    ]),
    "checkup": ("体检", [
        "M12 3.8a2.4 2.4 0 0 1 2.4 2.4v7.3a3.8 3.8 0 1 1-4.8 0V6.2A2.4 2.4 0 0 1 12 3.8z",
        "M12 8.4v6.8",
        "M10.8 18.2a1.2 1.2 0 1 0 2.4 0 1.2 1.2 0 1 0-2.4 0",
    ]),
    "phone": ("话费", [
        "M13.6 3.6H7.4a1.8 1.8 0 0 0-1.8 1.8v13.2a1.8 1.8 0 0 0 1.8 1.8h9.2a1.8 1.8 0 0 0 1.8-1.8V8.6z",
        "M13.6 3.6V8.6h4.8",
        "M10 12.8h4v3.6h-4z",
        "M12 12.8v3.6",
    ]),
    "broadband": ("宽带", [
        "M4.2 9.6a11 11 0 0 1 15.6 0",
        "M7.2 12.8a6.8 6.8 0 0 1 9.6 0",
        "M10.2 15.9a2.6 2.6 0 0 1 3.6 0",
        "M12 19.4h.01",
    ]),
    "entertainment": ("休闲娱乐", [
        "M5.4 6.4h13.2a1.6 1.6 0 0 1 1.6 1.6v1.4a2.6 2.6 0 0 0 0 5.2V16a1.6 1.6 0 0 1-1.6 1.6H5.4A1.6 1.6 0 0 1 3.8 16v-1.4a2.6 2.6 0 0 0 0-5.2V8A1.6 1.6 0 0 1 5.4 6.4z",
        "M14.6 9.6v4.8",
    ]),
    "fitness": ("运动健身", [
        "M5 8.4h1.4a1.6 1.6 0 0 1 1.6 1.6v4a1.6 1.6 0 0 1-1.6 1.6H5a1.6 1.6 0 0 1-1.6-1.6v-4A1.6 1.6 0 0 1 5 8.4z",
        "M17.6 8.4H19a1.6 1.6 0 0 1 1.6 1.6v4a1.6 1.6 0 0 1-1.6 1.6h-1.4a1.6 1.6 0 0 1-1.6-1.6v-4a1.6 1.6 0 0 1 1.6-1.6z",
        "M8 12h8",
    ]),
    "media": ("影音会员", [
        "M4.2 15V11.8a7.8 7.8 0 0 1 15.6 0V15",
        "M3.4 14.4h1.8a1.4 1.4 0 0 1 1.4 1.4v3.4a1.4 1.4 0 0 1-1.4 1.4H3.4A1.4 1.4 0 0 1 2 19.2v-3.4a1.4 1.4 0 0 1 1.4-1.4z",
        "M17.4 14.4h1.8a1.4 1.4 0 0 1 1.4 1.4v3.4a1.4 1.4 0 0 1-1.4 1.4h-1.8a1.4 1.4 0 0 1-1.4-1.4v-3.4a1.4 1.4 0 0 1 1.4-1.4z",
    ]),
    "game": ("游戏充值", [
        "M6.6 4.4h10.8a2.2 2.2 0 0 1 2.2 2.2v10.8a2.2 2.2 0 0 1-2.2 2.2H6.6a2.2 2.2 0 0 1-2.2-2.2V6.6a2.2 2.2 0 0 1 2.2-2.2z",
        "M9 9h.01",
        "M15 9h.01",
        "M12 12h.01",
        "M9 15h.01",
        "M15 15h.01",
    ]),
    "transfer": ("转账红包", [
        "M4.6 8.4h11.2l-3-3",
        "M19.4 15.6H8.2l3 3",
    ]),
    "gift": ("人情礼金", [
        "M3.6 7.4h16.8v3.2H3.6z",
        "M5.2 10.6h13.6v8.4a1.6 1.6 0 0 1-1.6 1.6H6.8a1.6 1.6 0 0 1-1.6-1.6z",
        "M12 7.4v12.8",
        "M12 7.4c-1.6-.8-3-1.8-3-3a1.8 1.8 0 0 1 3-1 1.8 1.8 0 0 1 3 1c0 1.2-1.4 2.2-3 3z",
    ]),
    "pet": ("宠物", [
        "M12 12.4c2.6 0 5.2 1.7 5.2 4.2 0 1.6-1.2 2.6-2.8 2.6-1 0-1.6-.4-2.4-.4s-1.4.4-2.4.4c-1.6 0-2.8-1-2.8-2.6 0-2.5 2.6-4.2 5.2-4.2z",
        "M7.6 10a1.6 1.9 0 1 0 3.2 0 1.6 1.9 0 1 0-3.2 0",
        "M13.2 10a1.6 1.9 0 1 0 3.2 0 1.6 1.9 0 1 0-3.2 0",
        "M4.6 13.6a1.4 1.7 0 1 0 2.8 0 1.4 1.7 0 1 0-2.8 0",
        "M16.6 13.6a1.4 1.7 0 1 0 2.8 0 1.4 1.7 0 1 0-2.8 0",
    ]),
    "other": ("其他", [
        "M12 3.6c0 4.6 1.4 6 6 6-4.6 0-6 1.4-6 6 0-4.6-1.4-6-6-6 4.6 0 6-1.4 6-6z",
    ]),
    "salary": ("工资报酬", [
        "M4.8 6.4h14.4a1.8 1.8 0 0 1 1.8 1.8v9.6a1.8 1.8 0 0 1-1.8 1.8H4.8a1.8 1.8 0 0 1-1.8-1.8V8.2a1.8 1.8 0 0 1 1.8-1.8z",
        "M14 11.5h4.7a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1-1.1 1.1H14a1.1 1.1 0 0 1-1.1-1.1v0a1.1 1.1 0 0 1 1.1-1.1z",
    ]),
    "bonus": ("奖金提成", [
        "M12 3.6a5 5 0 1 0 0 10 5 5 0 0 0 0-10z",
        "M8.6 12.6 6.8 20.4l5.2-2.6 5.2 2.6-1.8-7.8",
    ]),
    "refund": ("退款", [
        "M20.4 19.4v-6.2a4.6 4.6 0 0 0-4.6-4.6H4.6",
        "M8.4 4.6 4.6 8.6l3.8 4",
    ]),
    "redpacket": ("红包礼金", [
        "M4.8 5.6h14.4a1.8 1.8 0 0 1 1.8 1.8v9.2a1.8 1.8 0 0 1-1.8 1.8H4.8a1.8 1.8 0 0 1-1.8-1.8V7.4a1.8 1.8 0 0 1 1.8-1.8z",
        "M3.4 7.2 12 13.2l8.6-6",
    ]),
    "other_income": ("其他收入", [
        "M12 3.6a8.4 8.4 0 1 0 0 16.8 8.4 8.4 0 0 0 0-16.8z",
        "M9.4 9.2 12 12.6l2.6-3.4",
        "M9.6 12.8h4.8",
        "M9.6 15.2h4.8",
        "M12 12.6v5.2",
    ]),
}

# ---------------------------------------------------------------------------
# 老版本把 emoji 存进过 category / category_override 表，读回来时按这两张表换名
# ---------------------------------------------------------------------------

LEGACY_LEAF = [
    ("🍜", "dining"), ("🍚", "dining"), ("🍔", "dining"), ("🍰", "snack"),
    ("☕", "drink"), ("🥤", "drink"), ("🍎", "fruit"), ("🍺", "dinner"),
    ("🍬", "snack"), ("🍻", "dinner"), ("🛒", "grocery"), ("🧺", "grocery"),
    ("👕", "clothing"), ("👟", "clothing"), ("💄", "beauty"), ("👜", "clothing"),
    ("💻", "digital"), ("📱", "phone"), ("🎧", "media"), ("🎬", "media"),
    ("🎮", "game"), ("⚽", "fitness"), ("🏃", "fitness"), ("📚", "education"),
    ("✏️", "stationery"), ("🎓", "education"), ("📝", "exam"), ("✈️", "travel"),
    ("🚕", "taxi"), ("🚇", "transport"), ("⛽", "fuel"), ("🅿️", "parking"),
    ("🏠", "housing"), ("🏘", "rent"), ("💡", "utility"), ("🛋", "furniture"),
    ("🔌", "appliance"), ("🧴", "beauty"), ("🏥", "medical"), ("💊", "medicine"),
    ("🔬", "checkup"), ("🐾", "pet"), ("🧸", "gift"), ("🎁", "gift"),
    ("🧧", "redpacket"), ("🎀", "gift"), ("🎫", "entertainment"),
    ("💰", "salary"), ("🏆", "bonus"), ("🪙", "other_income"), ("📦", "express"),
    ("💅", "beauty"), ("🛜", "broadband"), ("🛍", "shopping"), ("💸", "transfer"),
    ("✨", "other"), ("↩️", "refund"),
]

LEGACY_GROUP = [
    ("🍜", "g_dining"), ("🚌", "g_transport"), ("🧺", "g_daily"),
    ("🛍", "g_shopping"), ("📚", "g_school"), ("🏠", "g_home"),
    ("🏥", "g_health"), ("📱", "g_comm"), ("🎮", "g_hobby"),
    ("✨", "g_other"), ("💰", "g_income"),
]

# 编辑分类弹窗里可选的全部图标（一级类图标不放进来，它是给分组用的）
PICKER_ORDER = [
    "other", "dining", "snack", "drink", "fruit", "dinner",
    "transport", "taxi", "fuel", "parking", "travel", "grocery",
    "clothing", "appliance", "express", "shopping", "digital", "beauty",
    "education", "stationery", "exam", "housing", "rent", "utility",
    "furniture", "medical", "medicine", "checkup", "phone", "broadband",
    "entertainment", "fitness", "media", "game", "transfer", "gift",
    "pet", "salary", "bonus", "refund", "redpacket", "other_income",
]

DRAWABLE_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- {label}（由 tools/gen_cat_icons.py 生成，改图标请改脚本后重跑） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
{paths}</vector>
"""

DRAWABLE_PATH = """    <path
        android:pathData="{d}"
        android:strokeColor="#000000"
        android:strokeWidth="{w}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
"""

KOTLIN_TEMPLATE = """package com.local.autoledger.data

import androidx.annotation.DrawableRes
import com.local.autoledger.R

/**
 * 分类图标目录。
 *
 * **本文件由 `tools/gen_cat_icons.py` 生成，不要手改** ——
 * 改图标（新增 / 调描边 / 换造型）都改脚本再重跑一次，图标名与资源永远同步。
 *
 * 图标名约定：
 *  - 一级类：[GROUP_FALLBACK] 与 `g_` 打头的名字，与一级类 key 一一对应；
 *  - 二级类：[LEAF_FALLBACK] 与其余名字，与二级类 key 一一对应。
 *
 * 老版本把 emoji 存进过数据库，[normalize] 会把它们映射回新的图标名，
 * 所以升级上来的自建分类 / 改过图标的分类不会变成空白。
 */
object CategoryIconCatalog {{

    /** 一级类兜底图标名 */
    const val GROUP_FALLBACK = "{group_fallback}"

    /** 二级类兜底图标名 */
    const val LEAF_FALLBACK = "{leaf_fallback}"

    /** 一级类图标名（顺序与分类字典一致） */
    val GROUP_NAMES: List<String> = listOf(
{group_names}
    )

    /** 二级类全部图标名，顺序就是编辑弹窗里图标网格的顺序 */
    val LEAF_NAMES: List<String> = listOf(
{leaf_names}
    )

    private val RES: Map<String, Int> = mapOf(
{res_entries}
    )

    private val LEGACY_LEAF: Map<String, String> = mapOf(
{legacy_leaf}
    )

    private val LEGACY_GROUP: Map<String, String> = mapOf(
{legacy_group}
    )

    /** 图标名是否合法（老 emoji 不算） */
    fun isKnown(name: String?): Boolean = RES.containsKey(name)

    /**
     * 把任意来源的图标值收敛成一个合法的图标名：
     * 合法名字原样返回，老 emoji 换成对应的新图标，其余一律兜底。
     */
    fun normalize(name: String?, group: Boolean = false): String {{
        val raw = name?.trim().orEmpty()
        val fallback = if (group) GROUP_FALLBACK else LEAF_FALLBACK
        if (raw.isEmpty()) return fallback
        if (RES.containsKey(raw)) return raw
        val legacy = if (group) LEGACY_GROUP[raw] else LEGACY_LEAF[raw]
        return legacy ?: fallback
    }}

    /** 图标名 -> vector drawable */
    @DrawableRes
    fun resOf(name: String?, group: Boolean = false): Int {{
        val fallback = if (group) GROUP_FALLBACK else LEAF_FALLBACK
        return RES[normalize(name, group)] ?: RES.getValue(fallback)
    }}
}}
"""


def render_drawable(label, paths):
    body = "".join(DRAWABLE_PATH.format(d=d, w=STROKE) for d in paths)
    return DRAWABLE_TEMPLATE.format(label=label, paths=body)


def kotlin_string_list(names, indent):
    lines = []
    for i in range(0, len(names), 6):
        chunk = names[i:i + 6]
        lines.append(indent + ", ".join('"%s"' % n for n in chunk) + ",")
    return "\n".join(lines)


def kotlin_map(pairs, indent):
    return "\n".join(
        '%s"%s" to "%s",' % (indent, k, v) for k, v in pairs
    )


def main():
    if not os.path.isdir(DRAWABLE_DIR):
        sys.stderr.write("找不到 drawable 目录: %s\n" % DRAWABLE_DIR)
        return 1

    icons = list(GROUPS.items()) + list(LEAVES.items())

    for name, (label, paths) in icons:
        path = os.path.join(DRAWABLE_DIR, "ic_cat_%s.xml" % name)
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(render_drawable(label, paths))

    unknown = [n for n in PICKER_ORDER if n not in LEAVES]
    if unknown:
        sys.stderr.write("PICKER_ORDER 里有不存在的图标名: %s\n" % unknown)
        return 1

    res_entries = "\n".join(
        '        "%s" to R.drawable.ic_cat_%s,' % (name, name) for name, _ in icons
    )

    kotlin = KOTLIN_TEMPLATE.format(
        group_fallback="g_other",
        leaf_fallback="other",
        group_names=kotlin_string_list(list(GROUPS.keys()), "        "),
        leaf_names=kotlin_string_list(PICKER_ORDER, "        "),
        res_entries=res_entries,
        legacy_leaf=kotlin_map(LEGACY_LEAF, "        "),
        legacy_group=kotlin_map(LEGACY_GROUP, "        "),
    )
    with io.open(KOTLIN_PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(kotlin)

    sys.stdout.write(
        "生成 %d 个图标 + CategoryIconCatalog.kt（编辑可选 %d 个图标名）\n"
        % (len(icons), len(PICKER_ORDER))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
