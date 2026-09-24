package com.local.autoledger.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.data.Txn
import com.local.autoledger.databinding.ActivityCategoryBreakdownBinding
import com.local.autoledger.databinding.ItemDistChildBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 一级类下钻页：报表 / 年度账单里点某个一级类就落到这里。
 *
 * 一屏给两件事：
 *  1. 上面是该一级类下的**二级分类**构成（按金额倒序，带占比条）；
 *  2. 下面是这个一级类的**交易明细**（按天分组，点一条直接进编辑页）。
 *
 * 再点某个二级类会继续下钻到「只有它自己」的流水页 [CategoryDetailActivity]，
 * 于是「看占比 → 看二级类构成 → 看每一笔 → 改这一笔」这条路径始终通着。
 */
class CategoryBreakdownActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryBreakdownBinding
    private val adapter = TxnAdapter { txn -> openEditor(txn.id) }

    private var ym: YearMonth = YearMonth.now()
    private var direction: Direction = Direction.EXPENSE
    private var groupKey: String = ""
    /** 年度账单一整年；非空时忽略 [ym] */
    private var year: Int? = null
    /** 占同方向总额的比例，由上一页带过来；拿不到就不显示那颗胶囊 */
    private var ratio: Float = -1f

    private val refresh: () -> Unit = { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryBreakdownBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ym = YearMonth.of(
            intent.getIntExtra(EXTRA_YEAR, YearMonth.now().year),
            intent.getIntExtra(EXTRA_MONTH, YearMonth.now().monthValue).coerceIn(1, 12)
        )
        direction = Direction.of(intent.getIntExtra(EXTRA_DIRECTION, Direction.EXPENSE.code))
        groupKey = intent.getStringExtra(EXTRA_GROUP_KEY).orEmpty()
        ratio = intent.getFloatExtra(EXTRA_RATIO, -1f)
        intent.getIntExtra(EXTRA_YEAR_ONLY, 0).takeIf { it > 0 }?.let { year = it }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null
        binding.btnBack.setOnClickListener { finish() }

        renderHeader()
        load()
    }

    override fun onStart() {
        super.onStart()
        LedgerBus.register(refresh)
    }

    override fun onStop() {
        LedgerBus.unregister(refresh)
        super.onStop()
    }

    private fun load() {
        val month = ym
        val dir = direction
        val gk = groupKey
        val wholeYear = year
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                if (wholeYear != null) {
                    LedgerRepository.categoryTxnsOfYear(wholeYear, dir, gk, null)
                } else {
                    LedgerRepository.categoryTxns(month, dir, gk, null)
                }
            }
            render(list)
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderHeader() {
        val group = Categories.groupByKey(groupKey)
        binding.tvTitle.text = group?.label ?: getString(R.string.tab_report)
        binding.tvPeriod.text = year?.let { "${it} 年" } ?: TimeFmt.monthLabel(ym)
        // 概览卡是蓝色渐变，图标统一走「白色描边 + 半透明白圆底」，
        // 和右边那颗占比胶囊是同一套语言（分类自己的颜色留到下面的二级类列表里用）
        if (group != null) {
            binding.heroIconBg.backgroundTintList =
                ColorStateList.valueOf(HERO_ICON_BG)
            binding.heroIcon.setImageResource(group.iconRes)
            binding.heroIcon.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
        }
        binding.tvGroupName.text = group?.label.orEmpty()
        if (ratio >= 0f) {
            binding.tvGroupRatio.isVisible = true
            binding.tvGroupRatio.text = String.format(
                Locale.US,
                "占%s%s %.1f%%",
                year?.let { "全年" } ?: "本月",
                direction.label,
                ratio * 100
            )
        } else {
            binding.tvGroupRatio.isVisible = false
        }
    }

    private fun render(list: List<Txn>) {
        val total = list.sumOf { it.amountCents }
        binding.tvTotal.text = Money.yuan(total)
        binding.tvMeta.text = if (list.isEmpty()) {
            getString(R.string.breakdown_empty)
        } else {
            "共 ${list.size} 笔 · 均 ${Money.yuan(total / list.size)}"
        }

        binding.tvDetailTitle.text =
            "${getString(R.string.breakdown_txn_title)} · ${list.size} 笔"

        renderLeaves(list)

        adapter.submit(list)
        binding.emptyView.isVisible = list.isEmpty()
        binding.recycler.isVisible = list.isNotEmpty()
    }

    /** 二级类构成：按金额倒序，和报表里的一级类排名同一套视觉 */
    private fun renderLeaves(list: List<Txn>) {
        val container = binding.leafContainer
        container.removeAllViews()

        val rows = list.groupBy { it.categoryKey }
            .map { (key, txns) ->
                Triple(
                    Categories.byKey(key, direction),
                    txns.sumOf { it.amountCents },
                    txns.size
                )
            }
            .sortedByDescending { it.second }

        binding.leafCard.isVisible = rows.isNotEmpty()
        binding.tvLeafTitle.isVisible = rows.isNotEmpty()
        if (rows.isEmpty()) return

        val max = rows.first().second.coerceAtLeast(1L)
        val inflater = layoutInflater
        rows.forEach { (category, amount, count) ->
            val row = ItemDistChildBinding.inflate(inflater, container, false)
            CategoryIcons.bind(row.iconBg, row.ivIcon, category)
            row.tvName.text = category.label
            row.tvCount.text = "$count 笔"
            row.tvAmount.text = Money.plain(amount)
            row.bar.progressTintList =
                ColorStateList.valueOf(ThemeUtil.color(this, category.colorRes))
            row.bar.progress = (amount.toFloat() / max * 1000f).roundToInt().coerceIn(0, 1000)
            row.root.setOnClickListener { openLeaf(category.key, category.label) }
            container.addView(row.root)
        }
    }

    // ------------------------------------------------------------------ 交互

    private fun openLeaf(categoryKey: String, label: String) {
        val ctx: Context = this
        val wholeYear = year
        startActivity(
            if (wholeYear != null) {
                CategoryDetailActivity.intentForYear(
                    ctx, wholeYear, direction, groupKey, categoryKey, label
                )
            } else {
                CategoryDetailActivity.intent(
                    ctx, ym, direction, groupKey, categoryKey, label
                )
            }
        )
    }

    private fun openEditor(id: Long) {
        startActivity(EditTransactionActivity.intent(this, id))
    }

    companion object {
        /** 概览卡里的图标底：20% 白，压在蓝色渐变上刚好有层次又不抢戏 */
        private const val HERO_ICON_BG = 0x33FFFFFF

        private const val EXTRA_YEAR = "extra_year"
        private const val EXTRA_MONTH = "extra_month"
        private const val EXTRA_YEAR_ONLY = "extra_year_only"
        private const val EXTRA_DIRECTION = "extra_direction"
        private const val EXTRA_GROUP_KEY = "extra_group_key"
        private const val EXTRA_RATIO = "extra_ratio"

        /** 月度报表下钻：看 [ym] 这个月 */
        fun intent(
            context: Context,
            ym: YearMonth,
            direction: Direction,
            groupKey: String,
            ratio: Float
        ): Intent = Intent(context, CategoryBreakdownActivity::class.java)
            .putExtra(EXTRA_YEAR, ym.year)
            .putExtra(EXTRA_MONTH, ym.monthValue)
            .putExtra(EXTRA_DIRECTION, direction.code)
            .putExtra(EXTRA_GROUP_KEY, groupKey)
            .putExtra(EXTRA_RATIO, ratio)

        /** 年度账单下钻：看 [year] 这一整年 */
        fun intentForYear(
            context: Context,
            year: Int,
            direction: Direction,
            groupKey: String,
            ratio: Float
        ): Intent = Intent(context, CategoryBreakdownActivity::class.java)
            .putExtra(EXTRA_YEAR_ONLY, year)
            .putExtra(EXTRA_DIRECTION, direction.code)
            .putExtra(EXTRA_GROUP_KEY, groupKey)
            .putExtra(EXTRA_RATIO, ratio)
    }
}
