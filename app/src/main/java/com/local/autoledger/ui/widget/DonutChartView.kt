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
import kotlin.math.min

/**
 * 环形占比图。中心显示总额，环上按比例分片。
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(val value: Long, val color: Int)

    private val density = resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 21f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 12f * resources.displayMetrics.scaledDensity
    }

    private val trackColor = ContextCompat.getColor(context, R.color.legend_track)
    private val oval = RectF()
    private var slices: List<Slice> = emptyList()
    private var total = 0L
    private var centerTitle = ""
    private var centerSub = ""
    private var scale = 0f
    private var animator: ValueAnimator? = null

    fun setData(items: List<Slice>, title: String, sub: String, animate: Boolean = true) {
        slices = items.filter { it.value > 0 }
        total = slices.sumOf { it.value }
        centerTitle = title
        centerSub = sub
        animator?.cancel()
        if (!animate) {
            scale = 1f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 640L
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
        if (w <= 0f || h <= 0f) return

        val stroke = min(w, h) * 0.145f
        arcPaint.strokeWidth = stroke
        val radius = min(w, h) / 2f - stroke / 2f - 2f * density
        oval.set(w / 2f - radius, h / 2f - radius, w / 2f + radius, h / 2f + radius)

        if (total <= 0L) {
            arcPaint.color = trackColor
            canvas.drawArc(oval, 0f, 360f, false, arcPaint)
        } else {
            val gap = if (slices.size > 1) 2.2f else 0f
            var start = -90f
            slices.forEach { slice ->
                val full = 360f * (slice.value.toFloat() / total.toFloat())
                val sweep = (full - gap).coerceAtLeast(0.5f) * scale
                arcPaint.color = slice.color
                canvas.drawArc(oval, start, sweep, false, arcPaint)
                start += full
            }
            // 未铺满时用淡色垫底，视觉上是个完整的环
            val drawn = 360f * scale
            if (scale < 1f) {
                arcPaint.color = trackColor
                canvas.drawArc(oval, -90f + drawn, 360f - drawn, false, arcPaint)
            }
        }

        val cy = h / 2f
        if (centerTitle.isNotEmpty()) {
            canvas.drawText(centerTitle, w / 2f, cy + 2f * density, titlePaint)
        }
        if (centerSub.isNotEmpty()) {
            canvas.drawText(centerSub, w / 2f, cy + 20f * density, subPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
