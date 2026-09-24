package com.local.autoledger.data

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
object CategoryIconCatalog {

    /** 一级类兜底图标名 */
    const val GROUP_FALLBACK = "g_other"

    /** 二级类兜底图标名 */
    const val LEAF_FALLBACK = "other"

    /** 一级类图标名（顺序与分类字典一致） */
    val GROUP_NAMES: List<String> = listOf(
        "g_dining", "g_transport", "g_daily", "g_shopping", "g_school", "g_home",
        "g_health", "g_comm", "g_hobby", "g_other", "g_income",
    )

    /** 二级类全部图标名，顺序就是编辑弹窗里图标网格的顺序 */
    val LEAF_NAMES: List<String> = listOf(
        "other", "dining", "snack", "drink", "fruit", "dinner",
        "transport", "taxi", "fuel", "parking", "travel", "grocery",
        "clothing", "appliance", "express", "shopping", "digital", "beauty",
        "education", "stationery", "exam", "housing", "rent", "utility",
        "furniture", "medical", "medicine", "checkup", "phone", "broadband",
        "entertainment", "fitness", "media", "game", "transfer", "gift",
        "pet", "salary", "bonus", "refund", "redpacket", "other_income",
    )

    private val RES: Map<String, Int> = mapOf(
        "g_dining" to R.drawable.ic_cat_g_dining,
        "g_transport" to R.drawable.ic_cat_g_transport,
        "g_daily" to R.drawable.ic_cat_g_daily,
        "g_shopping" to R.drawable.ic_cat_g_shopping,
        "g_school" to R.drawable.ic_cat_g_school,
        "g_home" to R.drawable.ic_cat_g_home,
        "g_health" to R.drawable.ic_cat_g_health,
        "g_comm" to R.drawable.ic_cat_g_comm,
        "g_hobby" to R.drawable.ic_cat_g_hobby,
        "g_other" to R.drawable.ic_cat_g_other,
        "g_income" to R.drawable.ic_cat_g_income,
        "dining" to R.drawable.ic_cat_dining,
        "snack" to R.drawable.ic_cat_snack,
        "drink" to R.drawable.ic_cat_drink,
        "fruit" to R.drawable.ic_cat_fruit,
        "dinner" to R.drawable.ic_cat_dinner,
        "transport" to R.drawable.ic_cat_transport,
        "taxi" to R.drawable.ic_cat_taxi,
        "fuel" to R.drawable.ic_cat_fuel,
        "parking" to R.drawable.ic_cat_parking,
        "travel" to R.drawable.ic_cat_travel,
        "grocery" to R.drawable.ic_cat_grocery,
        "clothing" to R.drawable.ic_cat_clothing,
        "appliance" to R.drawable.ic_cat_appliance,
        "express" to R.drawable.ic_cat_express,
        "shopping" to R.drawable.ic_cat_shopping,
        "digital" to R.drawable.ic_cat_digital,
        "beauty" to R.drawable.ic_cat_beauty,
        "education" to R.drawable.ic_cat_education,
        "stationery" to R.drawable.ic_cat_stationery,
        "exam" to R.drawable.ic_cat_exam,
        "housing" to R.drawable.ic_cat_housing,
        "rent" to R.drawable.ic_cat_rent,
        "utility" to R.drawable.ic_cat_utility,
        "furniture" to R.drawable.ic_cat_furniture,
        "medical" to R.drawable.ic_cat_medical,
        "medicine" to R.drawable.ic_cat_medicine,
        "checkup" to R.drawable.ic_cat_checkup,
        "phone" to R.drawable.ic_cat_phone,
        "broadband" to R.drawable.ic_cat_broadband,
        "entertainment" to R.drawable.ic_cat_entertainment,
        "fitness" to R.drawable.ic_cat_fitness,
        "media" to R.drawable.ic_cat_media,
        "game" to R.drawable.ic_cat_game,
        "transfer" to R.drawable.ic_cat_transfer,
        "gift" to R.drawable.ic_cat_gift,
        "pet" to R.drawable.ic_cat_pet,
        "other" to R.drawable.ic_cat_other,
        "salary" to R.drawable.ic_cat_salary,
        "bonus" to R.drawable.ic_cat_bonus,
        "refund" to R.drawable.ic_cat_refund,
        "redpacket" to R.drawable.ic_cat_redpacket,
        "other_income" to R.drawable.ic_cat_other_income,
    )

    private val LEGACY_LEAF: Map<String, String> = mapOf(
        "🍜" to "dining",
        "🍚" to "dining",
        "🍔" to "dining",
        "🍰" to "snack",
        "☕" to "drink",
        "🥤" to "drink",
        "🍎" to "fruit",
        "🍺" to "dinner",
        "🍬" to "snack",
        "🍻" to "dinner",
        "🛒" to "grocery",
        "🧺" to "grocery",
        "👕" to "clothing",
        "👟" to "clothing",
        "💄" to "beauty",
        "👜" to "clothing",
        "💻" to "digital",
        "📱" to "phone",
        "🎧" to "media",
        "🎬" to "media",
        "🎮" to "game",
        "⚽" to "fitness",
        "🏃" to "fitness",
        "📚" to "education",
        "✏️" to "stationery",
        "🎓" to "education",
        "📝" to "exam",
        "✈️" to "travel",
        "🚕" to "taxi",
        "🚇" to "transport",
        "⛽" to "fuel",
        "🅿️" to "parking",
        "🏠" to "housing",
        "🏘" to "rent",
        "💡" to "utility",
        "🛋" to "furniture",
        "🔌" to "appliance",
        "🧴" to "beauty",
        "🏥" to "medical",
        "💊" to "medicine",
        "🔬" to "checkup",
        "🐾" to "pet",
        "🧸" to "gift",
        "🎁" to "gift",
        "🧧" to "redpacket",
        "🎀" to "gift",
        "🎫" to "entertainment",
        "💰" to "salary",
        "🏆" to "bonus",
        "🪙" to "other_income",
        "📦" to "express",
        "💅" to "beauty",
        "🛜" to "broadband",
        "🛍" to "shopping",
        "💸" to "transfer",
        "✨" to "other",
        "↩️" to "refund",
    )

    private val LEGACY_GROUP: Map<String, String> = mapOf(
        "🍜" to "g_dining",
        "🚌" to "g_transport",
        "🧺" to "g_daily",
        "🛍" to "g_shopping",
        "📚" to "g_school",
        "🏠" to "g_home",
        "🏥" to "g_health",
        "📱" to "g_comm",
        "🎮" to "g_hobby",
        "✨" to "g_other",
        "💰" to "g_income",
    )

    /** 图标名是否合法（老 emoji 不算） */
    fun isKnown(name: String?): Boolean = RES.containsKey(name)

    /**
     * 把任意来源的图标值收敛成一个合法的图标名：
     * 合法名字原样返回，老 emoji 换成对应的新图标，其余一律兜底。
     */
    fun normalize(name: String?, group: Boolean = false): String {
        val raw = name?.trim().orEmpty()
        val fallback = if (group) GROUP_FALLBACK else LEAF_FALLBACK
        if (raw.isEmpty()) return fallback
        if (RES.containsKey(raw)) return raw
        val legacy = if (group) LEGACY_GROUP[raw] else LEGACY_LEAF[raw]
        return legacy ?: fallback
    }

    /** 图标名 -> vector drawable */
    @DrawableRes
    fun resOf(name: String?, group: Boolean = false): Int {
        val fallback = if (group) GROUP_FALLBACK else LEAF_FALLBACK
        return RES[normalize(name, group)] ?: RES.getValue(fallback)
    }
}
