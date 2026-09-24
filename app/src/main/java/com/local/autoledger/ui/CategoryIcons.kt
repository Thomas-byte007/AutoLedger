package com.local.autoledger.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import com.local.autoledger.data.Category
import com.local.autoledger.data.CategoryGroup

/**
 * 分类图标的统一渲染口。
 *
 * 一级类、二级类、用户自建分类用的是同一套线性矢量图标
 * （见 [com.local.autoledger.data.CategoryIconCatalog]），
 * 都画在同一个柔和圆底上，只有颜色跟着分类走 —— 所以整套列表看起来是同一个语言。
 */
object CategoryIcons {

    /** 圆底 + 图标，颜色取分类色 */
    fun bind(
        frame: View,
        icon: ImageView,
        iconRes: Int,
        colorRes: Int,
        strong: Boolean = false
    ) {
        val color = ThemeUtil.color(frame.context, colorRes)
        frame.backgroundTintList = ColorStateList.valueOf(
            if (strong) {
                ThemeUtil.softStrong(frame.context, color)
            } else {
                ThemeUtil.soft(frame.context, color)
            }
        )
        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(color)
    }

    fun bind(frame: View, icon: ImageView, category: Category, strong: Boolean = false) =
        bind(frame, icon, category.iconRes, category.colorRes, strong)

    fun bind(frame: View, icon: ImageView, group: CategoryGroup, strong: Boolean = false) =
        bind(frame, icon, group.iconRes, group.colorRes, strong)
}
