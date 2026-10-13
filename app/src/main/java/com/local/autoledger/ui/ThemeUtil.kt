package com.local.autoledger.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

object ThemeUtil {

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    @ColorInt
    fun color(context: Context, @ColorRes res: Int): Int = ContextCompat.getColor(context, res)

    /** 把分类色揉进底色，得到柔和的图标底色 */
    @ColorInt
    fun soft(context: Context, @ColorInt color: Int): Int =
        if (isDark(context)) {
            ColorUtils.blendARGB(color, 0xFF171A21.toInt(), 0.80f)
        } else {
            ColorUtils.blendARGB(color, 0xFFFFFFFF.toInt(), 0.88f)
        }

    /** 选中态用更实的底色 */
    @ColorInt
    fun softStrong(context: Context, @ColorInt color: Int): Int =
        if (isDark(context)) {
            ColorUtils.blendARGB(color, 0xFF171A21.toInt(), 0.62f)
        } else {
            ColorUtils.blendARGB(color, 0xFFFFFFFF.toInt(), 0.68f)
        }

    @ColorInt
    fun expenseColor(context: Context): Int = color(context, com.local.autoledger.R.color.expense_red)
    fun incomeColor(context: Context): Int = color(context, com.local.autoledger.R.color.income_green)
}
