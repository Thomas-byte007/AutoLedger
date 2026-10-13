package com.local.autoledger.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.local.autoledger.R
import com.local.autoledger.data.Direction
import com.local.autoledger.data.GroupStat
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.MonthPoint
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

/**
 * 报表页：月度总览 + 分类占比 + 趋势。
 *
 * 「支出分布 / 收入来源」只排一级类，点一行进下钻页
 * （[CategoryBreakdownActivity]），那里同时给出二级类构成和交易明细。
 */
class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    private var ym: YearMonth = YearMonth.now()
    private val refresh: () -> Unit = { load() }

    private var expenseGroups: List<GroupStat> = emptyList()
    private var incomeGroups: List<GroupStat> = emptyList()
    private var expenseTotal: Long = 0L
    private var incomeTotal: Long = 0L

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
        // 点月份标题：底部滑出「选择时间」面板，直接挑任意月份或全年（年度账单并入这里）
        binding.tvMonth.setOnClickListener { showPeriodPicker() }
    }

    override fun onStart() {
        super.onStart()
        LedgerBus.register(refresh)
    }

    /**
     * 回到前台必须重查一次：在编辑页删掉记录时，整个任务都停在 onStop，
     * LedgerBus 事件已经错过；只靠 onStart 重新注册是收不到"补发"的。
     */
    override fun onResume() {
        super.onResume()
        load()
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
        trend: List<MonthPoint>
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

        expenseGroups = summary.expenseGroups
        incomeGroups = summary.incomeGroups
        expenseTotal = summary.expenseCents
        incomeTotal = summary.incomeCents

        val hasExpense = expenseGroups.isNotEmpty()
        binding.catEmpty.isVisible = !hasExpense
        binding.donut.isVisible = hasExpense

        if (hasExpense) {
            val top = expenseGroups.take(MAX_SLICES)
            val restAmount = expenseGroups.drop(MAX_SLICES).sumOf { it.amountCents }
            val slices = top.map {
                DonutChartView.Slice(
                    it.amountCents,
                    ThemeUtil.color(ctx, it.group.colorRes)
                )
            }.toMutableList()
            if (restAmount > 0) {
                slices += DonutChartView.Slice(restAmount, ThemeUtil.color(ctx, R.color.cat_other))
            }
            binding.donut.setData(slices, Money.plain(summary.expenseCents), "本月支出")
        } else {
            binding.donut.setData(emptyList(), "", "")
        }
        binding.tvExpenseDistTotal.text = Money.yuan(summary.expenseCents)

        binding.incomeSection.isVisible = incomeGroups.isNotEmpty()
        binding.tvIncomeTotal.text = Money.yuan(summary.incomeCents)

        renderDistributions()

        // 趋势
        binding.barChart.setData(
            trend.map {
                BarChartView.Bar("${it.yearMonth.monthValue}月", it.expenseCents, it.incomeCents)
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
                row.ivIcon.visibility = View.GONE
                row.tvName.text = name
                row.tvRatio.text = ""
                row.tvAmount.text = Money.plain(amount)
                row.bar.progressTintList = ColorStateList.valueOf(color)
                row.bar.progress = (amount.toFloat() / maxAmount * 1000f).roundToInt()
                binding.merchantContainer.addView(row.root)
            }
        }
    }

    // ------------------------------------------------------------------ 分布列表

    private fun renderDistributions() {
        DistributionList.render(
            layoutInflater,
            binding.distExpenseContainer,
            expenseGroups,
            Direction.EXPENSE,
            ::openGroup
        )
        DistributionList.render(
            layoutInflater,
            binding.distIncomeContainer,
            incomeGroups,
            Direction.INCOME,
            ::openGroup
        )
    }

    /** 点一级类 → 下钻页（二级类构成 + 交易明细） */
    private fun openGroup(direction: Direction, groupKey: String, label: String) {
        val ratio = (if (direction == Direction.EXPENSE) expenseGroups else incomeGroups)
            .firstOrNull { it.group.key == groupKey }?.ratio ?: -1f
        startActivity(
            CategoryBreakdownActivity.intent(requireContext(), ym, direction, groupKey, ratio)
        )
    }

    private fun showPeriodPicker() {
        PeriodPickerSheet(
            activity = requireActivity(),
            ym = ym,
            onMonthPicked = { picked ->
                if (picked != ym) {
                    ym = picked
                    load()
                }
            },
            onYearPicked = { year ->
                startActivity(YearReportActivity.intent(requireContext(), year))
            }
        ).show()
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
