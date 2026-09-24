package com.local.autoledger.ui

import android.content.Context
import android.content.res.ColorStateList
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.local.autoledger.R
import com.local.autoledger.databinding.RowActionBinding
import com.local.autoledger.databinding.RowSwitchBinding
import com.local.autoledger.databinding.RowTileBinding

/**
 * 「我的」页三种行样式的统一装配器。
 *
 * 以前每加一行都要在 Fragment 里抄四遍 backgroundTintList / imageTintList，
 * 行一多就成了复制粘贴泥团。这里收成一个函数：
 *   · 默认图标是 brand_container 浅蓝圆底 + brand_primary 描边；
 *   · danger=true 的行（清空数据）换成语义红的浅色版本，一眼能看出这是不可逆操作；
 *   · 带开关的行把整行做成可点，点标题也能切换，不用非得瞄准右边那个滑块。
 */
object SettingsRows {

    /** 开关行 */
    fun switch(
        context: Context,
        row: RowSwitchBinding,
        @DrawableRes icon: Int,
        title: String,
        sub: String,
        danger: Boolean = false
    ) {
        paint(context, row.iconBg, row.tvIcon, icon, danger)
        row.tvTitle.text = title
        row.tvSub.text = sub
        if (danger) {
            row.tvTitle.setTextColor(ThemeUtil.expenseColor(context))
        }
        // 滑块自己本来就可点，这里的整行监听只在点到标题区域时触发
        row.root.setOnClickListener { row.switchView.toggle() }
    }

    /** 跳转行 / 动作行（右侧一个箭头，可选一个数值） */
    fun action(
        context: Context,
        row: RowActionBinding,
        @DrawableRes icon: Int,
        title: String,
        sub: String,
        danger: Boolean = false
    ) {
        paint(context, row.iconBg, row.tvIcon, icon, danger)
        row.tvTitle.text = title
        row.tvSub.text = sub
        if (danger) {
            row.tvTitle.setTextColor(ThemeUtil.expenseColor(context))
        }
    }

    /** 常用工具网格里的格子 */
    fun tile(
        context: Context,
        row: RowTileBinding,
        @DrawableRes icon: Int,
        title: String,
        sub: String
    ) {
        paint(context, row.iconBg, row.tvIcon, icon, danger = false)
        row.tvTitle.text = title
        row.tvSub.text = sub
    }

    private fun paint(
        context: Context,
        badge: FrameLayout,
        icon: ImageView,
        @DrawableRes resId: Int,
        danger: Boolean
    ) {
        val soft = ThemeUtil.soft(context, ThemeUtil.expenseColor(context))
        val solid = ThemeUtil.expenseColor(context)
        badge.backgroundTintList = ColorStateList.valueOf(
            if (danger) soft else ThemeUtil.color(context, R.color.brand_container)
        )
        icon.setImageResource(resId)
        icon.imageTintList = ColorStateList.valueOf(
            if (danger) solid else ThemeUtil.color(context, R.color.brand_primary)
        )
    }
}
