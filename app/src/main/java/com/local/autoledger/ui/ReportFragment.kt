package com.local.autoledger.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.local.autoledger.R
import com.local.autoledger.data.CategoryStat
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.MonthSummary
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.databinding.FragmentReportBinding
import com.local.autoledger.databinding.ItemCatStatBinding
import com.local.autoledger.ui.widget.BarChartView
import com.local.autoledger.ui.widget.DonutChartView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

/** 报表页：月度分类统计 + 占比 + 趋势 */
class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    private var ym: YearMonth = YearMonth.now()
    private val refresh: () -> Unit = { load() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnPrevMonth.setOnClickListener {
            ym = ym.minusMonths(1)
            load()
        }
        binding.btnNextMonth.setOnClickListener {
            if (ym < YearMonth.now()) {
                ym = ym.plusMonths(1)
                load()
            }
        }
        binding.pendingBanner.setOnClickListener {
            (activity as? MainActivity)?.goToTab(R.id.nav_detail)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        val month = ym
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val summary = LedgerRepository.monthSummary(month)
                val prev = LedgerRepository.monthSummary(month.minusMonths(1))
                val trend = LedgerRepository.trend(6, month)
                Triple(summary, prev, trend)
            }
            if (_binding == null) return@launch
            render(data.first, data.second, data.third)
        }
    }

    private fun render(
        summary: MonthSummary,
        prev: MonthSummary,
        trend: List<com.local.autoledger.data.MonthPoint>
    ) {
        val ctx = requireContext()
        binding.tvMonth.text = TimeFmt.monthLabel(ym)
        binding.btnNextMonth.alpha = if (ym < YearMonth.now()) 1f else 0.4f

        binding.tvExpense.text = Money.yuan(summary.expenseCents)
        binding.tvIncome.text = Money.yuan(summary.incomeCents)
        binding.tvCount.text = "${summary.count} 笔"

        val days = if (ym == YearMonth.now()) LocalDate.now().dayOfMonth else ym.lengthOfMonth()
        binding.tvDaily.text = Money.yuan(
            if (days > 0) summary.expenseCents / days else 0L
        )

        binding.tvCompare.text = compareText(summary, prev)

        // 待分类
        binding.pendingBanner.isVisible = summary.pendingCount > 0
        if (summary.pendingCount > 0) {
            binding.tvPending.text = "本月有 ${summary.pendingCount} 笔还没分类，点这里去处理"
        }

        // 分类占比
        val stats = summary.expenseStats
        val hasExpense = stats.isNotEmpty()
        binding.catEmpty.isVisible = !hasExpense
        binding.donut.isVisible = hasExpense

        if (hasExpense) {
            val top = stats.take(MAX_SLICES)
            val restAmount = stats.drop(MAX_SLICES).sumOf { it.amountCents }
            val slices = top.map {
                DonutChartView.Slice(
                    it.amountCents,
                    ThemeUtil.color(ctx, it.category.colorRes)
                )
            }.toMutableList()
            if (restAmount > 0) {
                slices += DonutChartView.Slice(
                    restAmount,
                    ThemeUtil.color(ctx, R.color.cat_other)
                )
            }
            binding.donut.setData(
                slices,
                Money.plain(summary.expenseCents),
                "本月支出"
            )
            buildLegend(binding.legendContainer, top, summary.expenseCents)
        } else {
            binding.donut.setData(emptyList(), "", "")
            binding.legendContainer.removeAllViews()
        }

        // 收入构成
        val incomeStats = summary.incomeStats
        binding.incomeSection.isVisible = incomeStats.isNotEmpty()
        if (incomeStats.isNotEmpty()) {
            buildLegend(binding.legendIncomeContainer, incomeStats, summary.incomeCents)
        } else {
            binding.legendIncomeContainer.removeAllViews()
        }

        // 趋势
        binding.barChart.setData(
            trend.map {
                BarChartView.Bar(
                    "${it.yearMonth.monthValue}月",
                    it.expenseCents,
                    it.incomeCents
                )
            }
        )

        // 花钱最多的对象
        val merchants = summary.topMerchants
        binding.topMerchantCard.isVisible = merchants.isNotEmpty()
        if (merchants.isNotEmpty()) {
            binding.merchantContainer.removeAllViews()
            val maxAmount = merchants.first().second.coerceAtLeast(1L)
            merchants.forEachIndexed { index, (name, amount) ->
                val row = ItemCatStatBinding.inflate(
                    layoutInflater, binding.merchantContainer, false
                )
                val color = if (index == 0) {
                    ThemeUtil.color(ctx, R.color.cat_dining)
                } else {
                    ThemeUtil.color(ctx, R.color.brand_primary)
                }
                row.dot.backgroundTintList = ColorStateList.valueOf(color)
                row.tvEmoji.text = ""
                row.tvName.text = name
                row.tvRatio.text = ""
                row.tvAmount.text = Money.plain(amount)
                row.bar.progressTintList = ColorStateList.valueOf(color)
                row.bar.progress = (amount.toFloat() / maxAmount * 1000f).roundToInt()
                binding.merchantContainer.addView(row.root)
            }
        }
    }

    private fun buildLegend(
        container: android.widget.LinearLayout,
        stats: List<CategoryStat>,
        total: Long
    ) {
        val ctx = requireContext()
        container.removeAllViews()
        stats.forEach { stat ->
            val row = ItemCatStatBinding.inflate(layoutInflater, container, false)
            val color = ThemeUtil.color(ctx, stat.category.colorRes)
            row.dot.backgroundTintList = ColorStateList.valueOf(color)
            row.tvEmoji.text = stat.category.emoji
            row.tvName.text = stat.category.label
            row.tvRatio.text = String.format(Locale.US, "%.1f%%", stat.ratio * 100)
            row.tvAmount.text = Money.plain(stat.amountCents)
            row.bar.progressTintList = ColorStateList.valueOf(color)
            row.bar.progress = (stat.ratio * 1000f).roundToInt().coerceIn(0, 1000)
            container.addView(row.root)
        }
    }

    private fun compareText(cur: MonthSummary, prev: MonthSummary): String {
        if (prev.expenseCents <= 0L) {
            return if (cur.expenseCents > 0) "上月没有支出记录" else "还没有数据"
        }
        val diff = cur.expenseCents - prev.expenseCents
        val pct = diff.toDouble() / prev.expenseCents * 100.0
        val arrow = if (diff > 0) "↑" else if (diff < 0) "↓" else "→"
        val word = if (diff > 0) "多花" else "少花"
        if (diff == 0L) return "与上月持平"
        return String.format(
            Locale.US,
            "较上月%s %s%.1f%%（%s %s）",
            arrow,
            if (pct < 0) "-" else "+",
            kotlin.math.abs(pct),
            word,
            Money.plain(kotlin.math.abs(diff))
        )
    }

    private companion object {
        const val MAX_SLICES = 8
    }
}
