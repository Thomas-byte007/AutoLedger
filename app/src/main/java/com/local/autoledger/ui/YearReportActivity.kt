package com.local.autoledger.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.MonthPoint
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.data.YearSummary
import com.local.autoledger.databinding.ActivityYearReportBinding
import com.local.autoledger.databinding.ItemCatStatBinding
import com.local.autoledger.ui.widget.BarChartView
import com.local.autoledger.ui.widget.DonutChartView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 年度账单：把一整年的收支摊开看。
 *
 * 构成和月度报表是同一套语言（同样的一级类 → 二级类下钻），
 * 只是统计区间从一个月换成一年，并且多了「月份走势」和「全年概况」两块。
 */
class YearReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityYearReportBinding

    private var year: Int = YearMonth.now().year
    private val refresh: () -> Unit = { load() }

    private var expenseGroups = emptyList<com.local.autoledger.data.GroupStat>()
    private var incomeGroups = emptyList<com.local.autoledger.data.GroupStat>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityYearReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        year = intent.getIntExtra(EXTRA_YEAR, YearMonth.now().year)

        binding.btnBackArea.setOnClickListener { finish() }
        binding.btnPrevYear.setOnClickListener {
            year -= 1
            load()
        }
        binding.btnNextYear.setOnClickListener {
            if (year < YearMonth.now().year) {
                year += 1
                load()
            }
        }

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
        val y = year
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val summary = LedgerRepository.yearSummary(y)
                val prev = LedgerRepository.yearSummary(y - 1)
                val years = LedgerRepository.years()
                Triple(summary, prev, years)
            }
            render(data.first, data.second, data.third)
        }
    }

    private fun render(summary: YearSummary, prev: YearSummary, years: List<Int>) {
        val hasData = summary.count > 0
        binding.tvYear.text = "${summary.year} 年"
        binding.btnNextYear.alpha = if (year < YearMonth.now().year) 1f else 0.4f

        binding.contentRoot.isVisible = true
        binding.emptyView.isVisible = !hasData
        binding.contentRoot.post { scrollTop() }

        binding.tvExpense.isVisible = true
        binding.tvExpense.text = Money.yuan(summary.expenseCents)
        binding.tvIncome.text = Money.yuan(summary.incomeCents)
        binding.tvBalance.text = Money.yuan(summary.balanceCents)
        binding.tvAvg.text = Money.yuan(summary.avgExpenseCents)
        binding.tvCompare.text = compareText(summary, prev, years)

        binding.tvMonths.text = "${summary.monthCount} 个月"
        binding.tvCount.text = "${summary.count} 笔"
        binding.tvDays.text = "${summary.activeDays} 天"

        binding.rowBusiest.isVisible = summary.busiestMonth != null || summary.maxTxn != null
        binding.tvBusiestMonth.text = summary.busiestMonth?.let {
            "${it.yearMonth.monthValue} 月 · ${Money.yuan(it.expenseCents)}"
        } ?: "—"
        binding.maxTxnBox.isVisible = summary.maxTxn != null
        binding.tvMaxTxn.text = summary.maxTxn?.let {
            "${Money.yuan(it.amountCents)} · ${it.merchant.ifBlank { titleOf(it.categoryKey) }}"
        } ?: "—"

        // 12 个月走势
        binding.barChart.setData(
            summary.months.map {
                BarChartView.Bar("${it.yearMonth.monthValue}月", it.expenseCents, it.incomeCents)
            }
        )

        expenseGroups = summary.expenseGroups
        incomeGroups = summary.incomeGroups

        renderDistribution()
        renderDonut(summary)
        renderMerchants(summary)

        binding.incomeSection.isVisible = incomeGroups.isNotEmpty()
        binding.tvIncomeTotal.text = Money.yuan(summary.incomeCents)
    }

    // ------------------------------------------------------------------ 各区块

    private fun renderDonut(summary: YearSummary) {
        val hasExpense = expenseGroups.isNotEmpty()
        binding.catEmpty.isVisible = !hasExpense
        binding.donut.isVisible = hasExpense
        if (!hasExpense) {
            binding.donut.setData(emptyList(), "", "")
            return
        }
        val top = expenseGroups.take(MAX_SLICES)
        val restAmount = expenseGroups.drop(MAX_SLICES).sumOf { it.amountCents }
        val slices = top.map {
            DonutChartView.Slice(it.amountCents, ThemeUtil.color(this, it.group.colorRes))
        }.toMutableList()
        if (restAmount > 0) {
            slices += DonutChartView.Slice(restAmount, ThemeUtil.color(this, R.color.cat_other))
        }
        binding.donut.setData(slices, Money.plain(summary.expenseCents), "全年支出")
        binding.tvDistTotal.text = Money.yuan(summary.expenseCents)
    }

    private fun renderDistribution() {
        DistributionList.render(
            layoutInflater, binding.distExpenseContainer,
            expenseGroups, Direction.EXPENSE, ::openGroup
        )
        DistributionList.render(
            layoutInflater, binding.distIncomeContainer,
            incomeGroups, Direction.INCOME, ::openGroup
        )
    }

    private fun renderMerchants(summary: YearSummary) {
        val merchants = summary.topMerchants
        binding.topMerchantCard.isVisible = merchants.isNotEmpty()
        if (merchants.isEmpty()) return
        binding.merchantContainer.removeAllViews()
        val maxAmount = merchants.first().second.coerceAtLeast(1L)
        merchants.forEachIndexed { index, (name, amount) ->
            val row = ItemCatStatBinding.inflate(layoutInflater, binding.merchantContainer, false)
            val color = if (index == 0) {
                ThemeUtil.color(this, R.color.cat_dining)
            } else {
                ThemeUtil.color(this, R.color.brand_primary)
            }
            row.dot.backgroundTintList = ColorStateList.valueOf(color)
            row.ivIcon.visibility = View.GONE
            row.tvName.text = name
            row.tvRatio.text = ""
            row.tvAmount.text = Money.plain(amount)
            row.bar.progressTintList = ColorStateList.valueOf(color)
            row.bar.progress = (amount.toFloat() / maxAmount * 1000f).roundToInt()
            binding.merchantContainer.addView(row.root)
        }
    }

    private fun titleOf(categoryKey: String): String =
        Categories.byKey(categoryKey, Direction.EXPENSE).label

    /** 点一级类 → 下钻页（这一整年的二级类构成 + 交易明细） */
    private fun openGroup(direction: Direction, groupKey: String, label: String) {
        val ratio = (if (direction == Direction.EXPENSE) expenseGroups else incomeGroups)
            .firstOrNull { it.group.key == groupKey }?.ratio ?: -1f
        startActivity(
            CategoryBreakdownActivity.intentForYear(this, year, direction, groupKey, ratio)
        )
    }

    private fun compareText(cur: YearSummary, prev: YearSummary, years: List<Int>): String {
        val older = years.filter { it < cur.year }
        if (prev.expenseCents <= 0L || older.isEmpty()) {
            return getString(R.string.year_compare_first)
        }
        val diff = cur.expenseCents - prev.expenseCents
        val pct = diff.toDouble() / prev.expenseCents * 100.0
        if (diff == 0L) return "与去年持平"
        val arrow = if (diff > 0) "↑" else "↓"
        val word = if (diff > 0) "多花" else "少花"
        return String.format(
            Locale.US,
            "较去年%s %s%.1f%%（%s %s）",
            arrow,
            if (pct < 0) "-" else "+",
            kotlin.math.abs(pct),
            word,
            Money.plain(kotlin.math.abs(diff))
        )
    }

    private fun scrollTop() {
        binding.scroll.smoothScrollTo(0, 0)
    }

    companion object {
        private const val EXTRA_YEAR = "extra_year"
        private const val MAX_SLICES = 8

        fun intent(context: Context, year: Int = YearMonth.now().year): Intent =
            Intent(context, YearReportActivity::class.java).putExtra(EXTRA_YEAR, year)
    }
}
