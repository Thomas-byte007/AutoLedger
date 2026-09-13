package com.local.autoledger.data

/**
 * 自动分类引擎。
 *
 * 策略：把「交易对方 + 原始文本」拼成待匹配串，按关键词长度（priority）从长到短命中，
 * 长关键词优先，避免「饭」抢先命中「饭店」这类问题。
 * 命不中时回落到「其他」，并把 auto = false 交给用户手动分类。
 */
class CategoryEngine(rules: List<Rule>) {

    data class Match(val categoryKey: String, val auto: Boolean, val keyword: String?)

    private val buckets: Map<Int, List<Rule>> =
        rules.groupBy { it.direction.code }
            .mapValues { (_, list) -> list.sortedByDescending { it.priority } }

    fun classify(
        direction: Direction,
        merchant: String,
        rawText: String
    ): Match {
        val hay = (merchant + " " + rawText).lowercase()
        if (hay.isBlank()) return fallback(direction)
        for (rule in buckets[direction.code].orEmpty()) {
            val kw = rule.keyword.lowercase()
            if (kw.isNotBlank() && hay.contains(kw)) {
                return Match(rule.categoryKey, auto = true, keyword = rule.keyword)
            }
        }
        return fallback(direction)
    }

    /** 只根据商户名判断，用于「记住这个商户」的推荐 */
    fun classifyMerchant(direction: Direction, merchant: String): String? =
        buckets[direction.code].orEmpty()
            .firstOrNull { merchant.lowercase().contains(it.keyword.lowercase()) }
            ?.categoryKey

    private fun fallback(direction: Direction): Match =
        Match(Categories.fallbackOf(direction).key, auto = false, keyword = null)

    companion object {
        val EMPTY = CategoryEngine(emptyList())
    }
}
