package com.local.autoledger.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.local.autoledger.R
import com.local.autoledger.data.Money
import kotlin.math.max
import kotlin.math.min

/**
 * 近几个月的收支柱状图：每月两根柱子（支出 / 收入）。
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Bar(val label: String, val expense: Long, val income: Long)

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 11f * scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 10f * scaledDensity
    }

    private val expenseColor = ContextCompat.getColor(context, R.color.brand_primary)
    private val incomeColor = ContextCompat.getColor(context, R.color.income_green)
    private val trackColor = ContextCompat.getColor(context, R.color.legend_track)

    private val rect = RectF()
    private var bars: List<Bar> = emptyList()
    private var scale = 0f
    private var animator: ValueAnimator? = null

    fun setData(list: List<Bar>, animate: Boolean = true) {
        bars = list
        animator?.cancel()
        if (!animate) {
            scale = 1f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || bars.isEmpty()) return

        val labelH = 20f * density
        val valueH = 14f * density
        val baseY = h - labelH
        val topY = valueH
        val chartH = baseY - topY
        if (chartH <= 0f) return

        // 基线
        barPaint.color = trackColor
        canvas.drawRect(0f, baseY, w, baseY + 1f * density, barPaint)

        val maxValue = max(bars.maxOf { max(it.expense, it.income) }, 1L)
        val groupW = w / bars.size
        val barW = min(groupW * 0.26f, 16f * density)
        val gap = 3f * density
        val radius = barW / 2f

        bars.forEachIndexed { index, bar ->
            val cx = groupW * index + groupW / 2f

            // 支出
            var bh = chartH * (bar.expense.toFloat() / maxValue) * scale
            if (bar.expense > 0) bh = bh.coerceAtLeast(2f * density)
            rect.set(cx - barW - gap / 2f, baseY - bh, cx - gap / 2f, baseY)
            barPaint.color = expenseColor
            canvas.drawRoundRect(rect, radius, radius, barPaint)

            // 收入
            var ih = chartH * (bar.income.toFloat() / maxValue) * scale
            if (bar.income > 0) ih = ih.coerceAtLeast(2f * density)
            rect.set(cx + gap / 2f, baseY - ih, cx + gap / 2f + barW, baseY)
            barPaint.color = incomeColor
            canvas.drawRoundRect(rect, radius, radius, barPaint)

            // 月份
            canvas.drawText(bar.label, cx, h - 5f * density, labelPaint)

            // 支出金额
            // 柱子很矮（某个月对比悬殊）时，标注会被贴到坐标轴上和月份挤在一起；
            // 更极端的情况是同一组里的收入柱很高，会把标注盖住。
            // 所以先按「柱顶 + 4dp」放，放不下就抬到整组最高的那根柱子上面。
            if (bar.expense > 0 && scale > 0.9f) {
                val minY = baseY - 13f * density
                var ly = baseY - bh - 4f * density
                if (ly > minY) ly = baseY - max(bh, ih) - 4f * density
                if (ly > minY) ly = minY
                canvas.drawText(
                    shorten(bar.expense),
                    cx - barW / 2f - gap / 2f,
                    ly,
                    valuePaint
                )
            }
        }
    }

    private fun shorten(cents: Long): String {
        val yuan = cents / 100.0
        return when {
            yuan >= 10000 -> String.format(java.util.Locale.US, "%.1fw", yuan / 10000)
            yuan >= 1000 -> String.format(java.util.Locale.US, "%.0f", yuan)
            else -> Money.plain(cents).substringBefore('.')
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
