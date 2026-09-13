package com.local.autoledger

import com.local.autoledger.data.Categories
import com.local.autoledger.data.CategoryEngine
import com.local.autoledger.data.Direction
import com.local.autoledger.data.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动分类引擎。纯逻辑，不需要设备。
 *
 * 重点保护两条原则：
 *  1. 长关键词优先（「饭店」不能被「饭」抢走）；
 *  2. 认不出来就老实说认不出来（auto=false），交给用户手动分类。
 */
class CategoryEngineTest {

    @Test
    fun `长关键词优先于短关键词`() {
        val engine = CategoryEngine(
            listOf(
                Rule(keyword = "饭", categoryKey = "dining", direction = Direction.EXPENSE, priority = 10),
                Rule(keyword = "饭店", categoryKey = "other", direction = Direction.EXPENSE, priority = 20),
            )
        )
        val m = engine.classify(Direction.EXPENSE, "某某饭店", "")
        assertEquals("other", m.categoryKey)
        assertEquals("饭店", m.keyword)
    }

    @Test
    fun `内置规则能认出常见商户`() {
        val engine = CategoryEngine(Categories.builtinRules())
        assertTrue(engine.classify(Direction.EXPENSE, "星巴克", "").auto)
        assertTrue(engine.classify(Direction.EXPENSE, "滴滴出行", "").auto)
        assertTrue(engine.classify(Direction.EXPENSE, "肯德基", "").auto)
    }

    @Test
    fun `认不出来时落到兜底分类并标记未识别`() {
        val engine = CategoryEngine(Categories.builtinRules())
        val m = engine.classify(Direction.EXPENSE, "zzz_不存在的商户_zzz", "")
        assertEquals(Categories.fallbackOf(Direction.EXPENSE).key, m.categoryKey)
        assertFalse(m.auto)
    }

    @Test
    fun `分类按收支方向分桶`() {
        // 「工资」只在收入桶里，拿支出方向去查不应命中
        val engine = CategoryEngine(
            listOf(Rule(keyword = "工资", categoryKey = "salary", direction = Direction.INCOME, priority = 20))
        )
        assertTrue(engine.classify(Direction.INCOME, "某公司代发工资", "").auto)
        val expense = engine.classify(Direction.EXPENSE, "某公司代发工资", "")
        assertFalse(expense.auto)
        assertEquals(Categories.fallbackOf(Direction.EXPENSE).key, expense.categoryKey)
    }

    @Test
    fun `原始文本也参与匹配`() {
        // 商户名是空的，但通知原文里带了商户
        val engine = CategoryEngine(
            listOf(Rule(keyword = "星巴克", categoryKey = "dining", direction = Direction.EXPENSE, priority = 30))
        )
        assertTrue(engine.classify(Direction.EXPENSE, "", "已支付¥25.00 商户名称：星巴克").auto)
    }

    @Test
    fun `空规则集不会崩`() {
        val m = CategoryEngine.EMPTY.classify(Direction.EXPENSE, "星巴克", "")
        assertFalse(m.auto)
    }

    @Test
    fun `空输入走兜底`() {
        val engine = CategoryEngine(Categories.builtinRules())
        val m = engine.classify(Direction.EXPENSE, "", "")
        assertFalse(m.auto)
    }

    @Test
    fun `记住商户用的只按商户名判断`() {
        val engine = CategoryEngine(
            listOf(Rule(keyword = "星巴克", categoryKey = "dining", direction = Direction.EXPENSE, priority = 30))
        )
        assertEquals("dining", engine.classifyMerchant(Direction.EXPENSE, "星巴克"))
        assertEquals(null, engine.classifyMerchant(Direction.INCOME, "星巴克"))
    }

    @Test
    fun `每个实义分类都有内置关键词`() {
        // 防止有人加了分类却忘了补关键词种子；兜底的「其他」不在此列
        val rules = Categories.builtinRules()
        val fallbacks = setOf(
            Categories.fallbackOf(Direction.EXPENSE).key,
            Categories.fallbackOf(Direction.INCOME).key
        )
        (Categories.EXPENSES + Categories.INCOMES)
            .map { it.key }
            .distinct()
            .filterNot { it in fallbacks }
            .forEach { key ->
                assertTrue("分类 $key 没有任何内置关键词", rules.any { it.categoryKey == key })
            }
    }
}
