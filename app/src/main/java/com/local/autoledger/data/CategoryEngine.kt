package com.local.autoledger.data

/**
 * 自动分类引擎。
 *
 * 策略：把「交易对方 + 原始文本」拼成待匹配串，按关键词长度（priority）从长到短命中，
 * 长关键词优先，避免「饭」抢先命中「饭店」这类问题。
 * 长度相同时（「滴滴」对「出行」）优先取在串里出现得更靠前的那个 ——
 * 商户名通常以品牌打头，这条规则能让「滴滴出行」落到「打车」而不是泛泛的「交通」。
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

        var best: Rule? = null
        var bestIndex = Int.MAX_VALUE
        for (rule in buckets[direction.code].orEmpty()) {
            val kw = rule.keyword.lowercase()
            if (kw.isBlank()) continue
            val idx = hay.indexOf(kw)
            if (idx < 0) continue
            val current = best
            if (current == null) {
                best = rule
                bestIndex = idx
                continue
            }
            // 列表按 priority 降序，后面的只会更短，不可能翻盘
            if (rule.priority < current.priority) break
            if (idx < bestIndex) {
                best = rule
                bestIndex = idx
            }
        }
        return best?.let { Match(it.categoryKey, auto = true, keyword = it.keyword) }
            ?: fallback(direction)
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
