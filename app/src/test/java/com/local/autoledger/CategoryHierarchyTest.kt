package com.local.autoledger

import com.local.autoledger.data.Categories
import com.local.autoledger.data.CategoryIconCatalog
import com.local.autoledger.data.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两级分类体系的自洽性。纯逻辑，不需要设备。
 *
 * 这些断言保护的是「新增二级类时容易忘的那几件事」：
 * 挂错一级类、key 写重、漏了关键词、把兜底分类也塞进选择列表。
 */
class CategoryHierarchyTest {

    @After
    fun tearDown() {
        // registerCustom 改的是全局字典，跑完必须还原，否则污染同 JVM 的其他测试
        Categories.registerCustom(emptyList())
    }

    @Test
    fun `每个二级类都挂在同方向的一级类下`() {
        Categories.ALL.forEach { cat ->
            val group = Categories.groupByKey(cat.group)
            assertNotNull("分类 ${cat.key} 的一级类 ${cat.group} 不存在", group)
            assertEquals(
                "分类 ${cat.key} 与一级类 ${cat.group} 的收支方向不一致",
                cat.direction,
                group!!.direction
            )
        }
    }

    @Test
    fun `每个一级类下面至少有一个二级类`() {
        Categories.GROUPS.forEach { group ->
            assertTrue(
                "一级类 ${group.key} 一个二级类都没有，报表里会出现空行",
                Categories.childrenOf(group.key, group.direction).isNotEmpty()
            )
        }
    }

    @Test
    fun `分类 key 不重复`() {
        val keys = Categories.ALL.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `同一个方向上内置关键词不重复`() {
        // 数据库对 (keyword, direction) 建了唯一索引，重复的会被静默忽略，
        // 表现出来就是「某个分类的关键词永远不生效」，很难查，所以在这里挡住
        val seen = HashSet<String>()
        val dup = ArrayList<String>()
        Categories.builtinRules().forEach { r ->
            val slot = "${r.direction.code}|${r.keyword}"
            if (!seen.add(slot)) dup += slot
        }
        assertTrue("同一个方向上出现重复关键词：$dup", dup.isEmpty())
    }

    @Test
    fun `兜底分类不出现在选择列表里`() {
        // 「其他」是回退目标，让用户主动选它没有意义，但它必须存在
        assertEquals("other", Categories.fallbackOf(Direction.EXPENSE).key)
        assertEquals("other_income", Categories.fallbackOf(Direction.INCOME).key)
    }

    @Test
    fun `老版本的扁平分类 key 全部还在`() {
        // v1.0.2 及以前库里只有这 14 个 key，升级后必须仍然能解析到分类，
        // 否则历史账目会整批掉进「其他」
        val legacy = listOf(
            "dining", "transport", "shopping", "grocery", "housing", "entertainment",
            "medical", "education", "travel", "transfer", "other",
            "salary", "refund", "other_income"
        )
        legacy.forEach { key ->
            val cat = Categories.byKeyOrNull(key, if (key == "salary" || key == "refund" || key == "other_income") {
                Direction.INCOME
            } else {
                Direction.EXPENSE
            })
            assertNotNull("老分类 $key 丢了", cat)
        }
    }

    @Test
    fun `自建分类会并入字典并参与一级类归组`() {
        val custom = com.local.autoledger.data.Category(
            key = "c_test",
            label = "咖啡",
            iconName = "drink",
            colorRes = Categories.PALETTE.first().second,
            direction = Direction.EXPENSE,
            group = Categories.gDining.key,
            custom = true
        )
        Categories.registerCustom(listOf(custom))

        assertTrue(Categories.EXPENSES.any { it.key == "c_test" })
        assertTrue(Categories.childrenOf(Categories.gDining.key, Direction.EXPENSE).any { it.key == "c_test" })
        assertEquals(Categories.gDining.key, Categories.groupOf("c_test", Direction.EXPENSE).key)
        // 自建分类也是矢量图标，认不出的名字会回落到兜底图标
        assertEquals(CategoryIconCatalog.resOf("drink"), custom.iconRes)
        assertEquals(CategoryIconCatalog.LEAF_FALLBACK, CategoryIconCatalog.normalize("☕乱写的"))
        assertEquals("drink", CategoryIconCatalog.normalize("☕"))
    }

    @Test
    fun `认不出来的分类回落到兜底一级类`() {
        assertNull(Categories.byKeyOrNull("不存在的 key", Direction.EXPENSE))
        assertEquals(
            Categories.gOther.key,
            Categories.groupOf("不存在的 key", Direction.EXPENSE).key
        )
        assertEquals(
            Categories.gIncome.key,
            Categories.groupOf("不存在的 key", Direction.INCOME).key
        )
    }
}
