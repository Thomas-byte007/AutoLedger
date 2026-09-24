package com.local.autoledger.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.local.autoledger.R
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.databinding.SheetPeriodPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * 账单周期选择面板（BottomSheet）。
 *
 * 报表页点月份标题时滑出，替代只能逐月切换的箭头：
 * - **月** 口径：年份左右切换 + 12 宫格直接点任意月份（当前月显示「本月」，未来月份置灰）；
 * - **年** 口径：从有账目的年份里直接挑一个，选中后由 [onYearPicked] 打开年度账单。
 * 这样年度账单的入口就合并进了报表页的同一个面板，不再需要单独的入口卡片。
 */
class PeriodPickerSheet(
    private val activity: Activity,
    private var ym: YearMonth,
    private val onMonthPicked: (YearMonth) -> Unit,
    private val onYearPicked: (Int) -> Unit
) {

    private lateinit var binding: SheetPeriodPickerBinding
    private val dialog: BottomSheetDialog = BottomSheetDialog(activity)

    /** 当前显示的年份（月 / 年两种口径共用） */
    private var year: Int = ym.year

    /** 月 / 年 口径，true = 年 */
    private var yearMode = false

    /** 有账目的年份（IO 线程查出来缓存），年口径的宫格用它 */
    private var yearsWithData: List<Int> = emptyList()

    fun show() {
        binding = SheetPeriodPickerBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        binding.btnClose.setOnClickListener { dialog.dismiss() }
        binding.tvTabMonth.setOnClickListener { yearMode = false; renderTabs(); render() }
        binding.tvTabYear.setOnClickListener { yearMode = true; renderTabs(); render() }
        binding.btnPrevYear.setOnClickListener {
            if (year > MIN_YEAR) { year -= 1; render() }
        }
        binding.btnNextYear.setOnClickListener {
            if (year < YearMonth.now().year) { year += 1; render() }
        }

        // 面板占屏幕约七成高；先同步渲染月份宫格，内容高度才撑得起来
        binding.root.post {
            val h = activity.resources.displayMetrics.heightPixels
            dialog.behavior.peekHeight = (h * 0.72f).toInt()
        }

        render()
        loadYearsAndRender()
        dialog.show()
    }

    private fun loadYearsAndRender() {
        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return
        scope.launch {
            yearsWithData = withContext(Dispatchers.IO) { LedgerRepository.years() }
            // 面板可能还没 attach，用 post 保证渲染一定在主线程、attach 之后执行
            binding.root.post { render() }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderTabs() {
        val sel = ContextCompat.getColor(activity, R.color.brand_primary)
        val softBg = ThemeUtil.color(activity, R.color.brand_container)
        val normal = ThemeUtil.color(activity, R.color.text_secondary)
        val normalBg = ContextCompat.getColor(activity, android.R.color.transparent)
        if (yearMode) {
            styleTab(binding.tvTabYear, sel, softBg)
            styleTab(binding.tvTabMonth, normal, normalBg)
        } else {
            styleTab(binding.tvTabMonth, sel, softBg)
            styleTab(binding.tvTabYear, normal, normalBg)
        }
    }

    private fun styleTab(tab: TextView, textColor: Int, bg: Int) {
        tab.setTextColor(textColor)
        tab.backgroundTintList = ColorStateList.valueOf(bg)
    }

    private fun render() {
        renderTabs()
        binding.tvYearLabel.text = "${year}年"
        val curYear = YearMonth.now().year
        binding.btnNextYear.alpha = if (year < curYear) 1f else 0.4f
        binding.btnPrevYear.alpha = if (year > MIN_YEAR) 1f else 0.4f
        binding.gridMonths.visibility = if (yearMode) View.GONE else View.VISIBLE
        binding.scrollYears.visibility = if (yearMode) View.VISIBLE else View.GONE
        if (yearMode) renderYearGrid() else renderMonthGrid()
    }

    /** 月口径：4 列 x 3 行的 12 个月份 */
    private fun renderMonthGrid() {
        val container = binding.gridMonths
        container.removeAllViews()
        val now = YearMonth.now()
        val dp6 = dp(6)
        for (row in 0 until 3) {
            val rowBox = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (col in 0 until 4) {
                val month = row * 4 + col + 1
                val cellYm = YearMonth.of(year, month)
                val isCurrent = cellYm == now
                val isSelected = cellYm == ym
                val future = cellYm > now
                val cell = TextView(activity).apply {
                    gravity = Gravity.CENTER
                    textSize = 14f
                    text = if (isCurrent) activity.getString(R.string.picker_period_this_month)
                    else "${month}月"
                    // 选中项用品牌色底，未来月份置灰不可点
                    if (isSelected) {
                        setTextColor(ThemeUtil.color(activity, R.color.brand_primary))
                        typeface = Typeface.DEFAULT_BOLD
                        background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
                        backgroundTintList =
                            ColorStateList.valueOf(ThemeUtil.color(activity, R.color.brand_container))
                    } else {
                        setTextColor(ThemeUtil.color(activity, R.color.text_primary))
                        typeface = Typeface.DEFAULT
                        background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
                        alpha = if (future) 0.35f else 1f
                    }
                    isEnabled = !future
                    layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                        setMargins(dp6, dp6, dp6, dp6)
                    }
                    setOnClickListener { pick(cellYm) }
                }
                rowBox.addView(cell)
            }
            container.addView(rowBox)
        }
    }

    /** 年口径：有账目的年份（含当前年）从新到旧铺开 */
    private fun renderYearGrid() {
        val container = binding.yearContainer
        container.removeAllViews()
        val set = LinkedHashSet<Int>()
        set += YearMonth.now().year
        set += year
        set += yearsWithData
        val list = set.sortedDescending()
        val dp6 = dp(6)
        list.chunked(3).forEach { rowYears ->
            val rowBox = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            rowYears.forEach { y ->
                val isSelected = y == year
                val cell = TextView(activity).apply {
                    gravity = Gravity.CENTER
                    textSize = 14f
                    text = y.toString()
                    if (isSelected) {
                        setTextColor(ThemeUtil.color(activity, R.color.brand_primary))
                        typeface = Typeface.DEFAULT_BOLD
                        backgroundTintList =
                            ColorStateList.valueOf(ThemeUtil.color(activity, R.color.brand_container))
                    } else {
                        setTextColor(ThemeUtil.color(activity, R.color.text_primary))
                        typeface = Typeface.DEFAULT
                        backgroundTintList = null
                    }
                    background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
                    layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                        setMargins(dp6, dp6, dp6, dp6)
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        onYearPicked(y)
                    }
                }
                rowBox.addView(cell)
            }
            // 最后一行不满 3 个也占满宽度（每个 cell weight=1，天然铺开）
            container.addView(rowBox)
        }
    }

    // ------------------------------------------------------------------ 交互

    private fun pick(target: YearMonth) {
        dialog.dismiss()
        ym = target
        onMonthPicked(target)
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val MIN_YEAR = 2000
    }
}
