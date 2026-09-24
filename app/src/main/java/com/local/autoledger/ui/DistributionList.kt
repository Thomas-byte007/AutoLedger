package com.local.autoledger.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.local.autoledger.R
import com.local.autoledger.data.Direction
import com.local.autoledger.data.GroupStat
import com.local.autoledger.data.Money
import com.local.autoledger.databinding.ItemDistGroupBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 「一级类排名」列表的渲染器。
 *
 * 月度报表和年度账单用的是同一套视觉，抽出来之后两边不会各自长歪。
 *
 * 这里只画一级类：二级类不再就地展开，而是点一行跳到下钻页
 * （[CategoryBreakdownActivity]），一屏同时给出「二级类构成 + 交易明细」。
 */
object DistributionList {

    fun render(
        inflater: LayoutInflater,
        container: LinearLayout,
        groups: List<GroupStat>,
        direction: Direction,
        onOpenGroup: (Direction, String, String) -> Unit
    ) {
        container.removeAllViews()
        val ctx = container.context
        val maxAmount = groups.maxOfOrNull { it.amountCents }?.coerceAtLeast(1L) ?: 1L

        groups.forEachIndexed { index, stat ->
            val groupColor = ThemeUtil.color(ctx, stat.group.colorRes)

            val row = ItemDistGroupBinding.inflate(inflater, container, false)
            row.tvRank.text = (index + 1).toString()
            CategoryIcons.bind(row.iconBg, row.ivIcon, stat.group)
            row.tvName.text = stat.group.label
            row.tvRatio.text = String.format(Locale.US, "%.2f%%", stat.ratio * 100)
            row.tvAmount.text = Money.plain(stat.amountCents)
            row.bar.progressTintList = ColorStateList.valueOf(groupColor)
            // 进度条按「占最大分类的比例」画，第一名满格，视觉上更好分辨
            row.bar.progress = (stat.amountCents.toFloat() / maxAmount * 1000f)
                .roundToInt().coerceIn(0, 1000)
            row.root.setOnClickListener {
                onOpenGroup(direction, stat.group.key, stat.group.label)
            }
            container.addView(row.root)
        }
    }
}
