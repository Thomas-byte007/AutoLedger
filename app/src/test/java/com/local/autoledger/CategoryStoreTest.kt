package com.local.autoledger

import androidx.test.core.app.ApplicationProvider
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Txn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

/**
 * 分类体系的持久化与汇总。用 Robolectric 起真实 SQLite，不需要设备。
 *
 * 重点保护三件事：
 *  1. 用户自建的二级类能落库、能读回，重启 App 不丢；
 *  2. 删掉自建分类不会连带丢掉账目，记录会改挂到兜底分类；
 *  3. 报表按一级类汇总时，二级类明细的金额加起来必须等于一级类的金额。
 */
@RunWith(RobolectricTestRunner::class)
class CategoryStoreTest {

    private val now: Long get() = System.currentTimeMillis()

    @Before
    fun setUp() {
        LedgerRepository.init(ApplicationProvider.getApplicationContext())
        LedgerRepository.deleteAll()
    }

    @After
    fun tearDown() {
        LedgerRepository.deleteAll()
        Categories.clearLayer()
    }

    private fun txn(
        cents: Long,
        categoryKey: String,
        direction: Direction = Direction.EXPENSE,
        at: Long = now,
        merchant: String = "",
        raw: String = ""
    ) = Txn(
        amountCents = cents,
        direction = direction,
        categoryKey = categoryKey,
        merchant = merchant,
        channel = Channel.MANUAL,
        occurredAt = at,
        autoCategory = true,
        rawText = raw
    )

    // ------------------------------------------------ 自建分类

    @Test
    fun `自建分类可以落库并重新读回`() {
        val created = LedgerRepository.addCustomCategory(
            label = "咖啡",
            icon = "drink",
            colorName = "cat_dining",
            groupKey = Categories.gDining.key,
            direction = Direction.EXPENSE
        )
        assertNotNull(created)
        assertTrue(created!!.key.startsWith(Categories.CUSTOM_PREFIX))
        assertTrue(created.custom)
        assertEquals(Categories.gDining.key, created.group)

        // 模拟「重启 App」：内存里的字典清空，重新从数据库建一遍
        Categories.clearLayer()
        LedgerRepository.refreshCategories()
        val reloaded = Categories.byKeyOrNull(created.key, Direction.EXPENSE)
        assertNotNull("重启后自建分类丢了", reloaded)
        assertEquals("咖啡", reloaded!!.label)
        assertEquals("drink", reloaded.iconName)
        assertEquals(Categories.gDining.key, reloaded.group)
        assertEquals(1, LedgerRepository.customCategoryCount())
    }

    @Test
    fun `同一个一级类下不允许建同名分类`() {
        assertNotNull(
            LedgerRepository.addCustomCategory("咖啡", "drink", "cat_dining", Categories.gDining.key, Direction.EXPENSE)
        )
        assertNull(
            LedgerRepository.addCustomCategory("咖啡", "snack", "cat_drink", Categories.gDining.key, Direction.EXPENSE)
        )
        assertEquals(1, LedgerRepository.customCategoryCount())
    }

    @Test
    fun `一级类与收支方向不匹配时拒绝创建`() {
        // 「收入」这个一级类不能拿来挂支出分类
        assertNull(
            LedgerRepository.addCustomCategory("怪东西", "snack", "cat_snack", Categories.gIncome.key, Direction.EXPENSE)
        )
        assertNull(
            LedgerRepository.addCustomCategory("", "snack", "cat_snack", Categories.gDining.key, Direction.EXPENSE)
        )
        assertEquals(0, LedgerRepository.customCategoryCount())
    }

    @Test
    fun `删除自建分类会把账目改挂到兜底分类`() {
        val created = LedgerRepository.addCustomCategory(
            "咖啡", "drink", "cat_dining", Categories.gDining.key, Direction.EXPENSE
        )!!
        LedgerRepository.save(txn(2800, created.key))
        LedgerRepository.save(txn(1500, created.key))

        val moved = LedgerRepository.deleteCategory(created.key, Direction.EXPENSE)

        assertEquals(2, moved)
        assertEquals(0, LedgerRepository.customCategoryCount())
        assertEquals(2, LedgerRepository.count())
        val summary = LedgerRepository.monthSummary(YearMonth.now())
        assertEquals(4300L, summary.expenseCents)
        assertTrue(summary.expenseStats.any { it.category.key == "other" })
    }

    @Test
    fun `不存在的分类删不动`() {
        assertEquals(-1, LedgerRepository.deleteCategory("根本没这个 key", Direction.EXPENSE))
    }

    @Test
    fun `内置二级类也能改也能删，并且改得回来`() {
        LedgerRepository.save(txn(1200, "transport"))

        // 改名 + 换图标 + 挪到别的一级类：内置 key 不能动，落的是补丁
        val renamed = LedgerRepository.updateCategory(
            key = "transport",
            direction = Direction.EXPENSE,
            label = "通勤",
            icon = "transport",
            colorName = "cat_snack",
            groupKey = Categories.gDining.key
        )
        assertNotNull(renamed)
        assertEquals("通勤", renamed!!.label)
        assertEquals("transport", renamed.iconName)
        assertEquals(Categories.gDining.key, renamed.group)
        assertEquals("内置分类的 key 永远不变", "transport", renamed.key)
        assertFalse(renamed.custom)

        // 模拟重启后仍然生效
        Categories.clearLayer()
        LedgerRepository.refreshCategories()
        assertEquals("通勤", Categories.byKey("transport", Direction.EXPENSE).label)

        // 删掉：账目改挂到兜底分类，它自己从字典里消失
        val moved = LedgerRepository.deleteCategory("transport", Direction.EXPENSE)
        assertEquals(1, moved)
        assertNull(Categories.byKeyOrNull("transport", Direction.EXPENSE))
        assertEquals(1, LedgerRepository.count())
        assertEquals("other", LedgerRepository.recent(1).first().categoryKey)

        // 恢复内置：名字回到出厂值
        val restored = LedgerRepository.restoreBuiltinCategories()
        assertTrue(restored > 0)
        assertEquals("交通", Categories.byKey("transport", Direction.EXPENSE).label)
    }

    @Test
    fun `改分类遇到同名会被拒绝`() {
        assertNull(
            LedgerRepository.updateCategory(
                "snack", Direction.EXPENSE, "饮料", "snack", "cat_snack", Categories.gDining.key
            )
        )
        assertEquals("零食", Categories.byKey("snack", Direction.EXPENSE).label)
    }

    @Test
    fun `一级类改名后报表分组跟着改`() {
        LedgerRepository.save(txn(2000, "snack"))
        LedgerRepository.updateCategoryGroup(
            Categories.gDining.key, label = "吃喝", icon = "g_dining", colorName = "cat_dining"
        )
        val s = LedgerRepository.monthSummary(YearMonth.now())
        assertEquals(
            "吃喝",
            s.expenseGroups.first { it.group.key == Categories.gDining.key }.group.label
        )
    }

    @Test
    fun `删除一级类会把它的二级类整体挪走，账目不会丢`() {
        LedgerRepository.save(txn(1000, "snack"))
        LedgerRepository.deleteCategoryGroup(Categories.gDining.key, Direction.EXPENSE)

        assertTrue(Categories.groupsOf(Direction.EXPENSE).none { it.key == Categories.gDining.key })
        assertEquals(Categories.gOther.key, Categories.byKey("snack", Direction.EXPENSE).group)

        assertEquals(1, LedgerRepository.count())
        val s = LedgerRepository.monthSummary(YearMonth.now())
        assertEquals(1000L, s.expenseCents)
        assertTrue(s.expenseGroups.all { it.group.key == Categories.gOther.key })

        LedgerRepository.restoreBuiltinCategories()
        assertTrue(Categories.groupsOf(Direction.EXPENSE).any { it.key == Categories.gDining.key })
    }

    @Test
    fun `兜底一级类不允许删除`() {
        assertEquals(-1, LedgerRepository.deleteCategoryGroup(Categories.gOther.key, Direction.EXPENSE))
        assertTrue(Categories.groupsOf(Direction.EXPENSE).any { it.key == Categories.gOther.key })
    }

    // ------------------------------------------------ 一级类汇总 + 下钻

    @Test
    fun `月度统计按一级类汇总且明细金额能对上`() {
        LedgerRepository.save(txn(3000, "dining"))
        LedgerRepository.save(txn(2000, "snack"))
        LedgerRepository.save(txn(1000, "transport"))
        LedgerRepository.save(txn(5000, "salary", Direction.INCOME))

        val s = LedgerRepository.monthSummary(YearMonth.now())

        // 支出一共 60 元，两个一级类：饮食 50、出行 10
        assertEquals(6000L, s.expenseCents)
        assertEquals(2, s.expenseGroups.size)
        val dining = s.expenseGroups.first { it.group.key == Categories.gDining.key }
        assertEquals(5000L, dining.amountCents)
        assertEquals(2, dining.children.size)
        assertEquals(
            "二级类明细加起来必须等于一级类的金额",
            dining.amountCents,
            dining.children.sumOf { it.amountCents }
        )
        assertEquals(5000L, dining.children.sumOf { it.amountCents })

        val allGroups = s.expenseGroups.sumOf { it.amountCents }
        assertEquals("一级类加起来必须等于本月支出", s.expenseCents, allGroups)

        // 收入侧同理
        assertEquals(1, s.incomeGroups.size)
        assertEquals(5000L, s.incomeGroups.first().amountCents)
        assertEquals(Categories.gIncome.key, s.incomeGroups.first().group.key)
    }

    @Test
    fun `按二级类下钻只看这一个分类`() {
        LedgerRepository.save(txn(3000, "dining", merchant = "海底捞"))
        LedgerRepository.save(txn(2000, "snack", merchant = "良品铺子"))

        val leaves = LedgerRepository.categoryTxns(
            YearMonth.now(), Direction.EXPENSE, groupKey = Categories.gDining.key, categoryKey = "snack"
        )
        assertEquals(1, leaves.size)
        assertEquals("良品铺子", leaves.first().merchant)

        val group = LedgerRepository.categoryTxns(
            YearMonth.now(), Direction.EXPENSE, groupKey = Categories.gDining.key, categoryKey = null
        )
        assertEquals(2, group.size)
    }

    @Test
    fun `下钻不会串到别的一级类或别的方向`() {
        LedgerRepository.save(txn(1000, "transport"))
        LedgerRepository.save(txn(2000, "salary", Direction.INCOME))

        val dining = LedgerRepository.categoryTxns(
            YearMonth.now(), Direction.EXPENSE, Categories.gDining.key, null
        )
        assertTrue(dining.isEmpty())
    }

    @Test
    fun `最近使用的分类按时间倒序且不重复`() {
        LedgerRepository.save(txn(1000, "dining", at = now - 3 * 60_000))
        LedgerRepository.save(txn(1000, "snack", at = now - 2 * 60_000))
        LedgerRepository.save(txn(1000, "dining", at = now - 60_000))

        val recent = LedgerRepository.recentCategories(Direction.EXPENSE)
        assertEquals(listOf("dining", "snack"), recent.map { it.key })
    }

    @Test
    fun `自建分类被删掉后不会再出现在最近使用里`() {
        val created = LedgerRepository.addCustomCategory(
            "咖啡", "drink", "cat_dining", Categories.gDining.key, Direction.EXPENSE
        )!!
        LedgerRepository.save(txn(1800, created.key))
        assertEquals(created.key, LedgerRepository.recentCategories(Direction.EXPENSE).first().key)

        LedgerRepository.deleteCategory(created.key, Direction.EXPENSE)
        assertTrue(
            LedgerRepository.recentCategories(Direction.EXPENSE).none { it.key == created.key }
        )
    }

    @Test
    fun `清空全部数据会连自建分类一起清掉`() {
        LedgerRepository.addCustomCategory("咖啡", "drink", "cat_dining", Categories.gDining.key, Direction.EXPENSE)
        assertEquals(1, LedgerRepository.customCategoryCount())
        LedgerRepository.deleteAll()
        assertEquals(0, LedgerRepository.customCategoryCount())
        assertEquals(0, Categories.customCategories.size)
    }

    // ------------------------------------------------ 自动分类落到新二级类

    @Test
    fun `内置规则会把零食饮料分到各自的二级类`() {
        assertEquals("snack", LedgerRepository.classify(Direction.EXPENSE, "三只松鼠零食", "").categoryKey)
        assertEquals("drink", LedgerRepository.classify(Direction.EXPENSE, "元气森林", "").categoryKey)
        assertEquals("taxi", LedgerRepository.classify(Direction.EXPENSE, "滴滴出行", "").categoryKey)
        assertEquals("travel", LedgerRepository.classify(Direction.EXPENSE, "亚朵酒店", "").categoryKey)
    }
}
