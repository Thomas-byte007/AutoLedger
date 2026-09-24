package com.local.autoledger.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Direction
import com.local.autoledger.data.Rule
import com.local.autoledger.databinding.ItemRuleBinding

class RuleAdapter(private val onDelete: (Rule) -> Unit) :
    RecyclerView.Adapter<RuleAdapter.Holder>() {

    private var items: List<Rule> = emptyList()

    fun submit(list: List<Rule>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val b: ItemRuleBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(rule: Rule) {
            val ctx = b.root.context
            val cat = Categories.byKey(rule.categoryKey, rule.direction)
            b.ivIcon.setImageResource(cat.iconRes)
            b.ivIcon.imageTintList = ColorStateList.valueOf(
                ThemeUtil.color(ctx, cat.colorRes)
            )
            b.iconBg.backgroundTintList =
                ColorStateList.valueOf(ThemeUtil.soft(ctx, ThemeUtil.color(ctx, cat.colorRes)))
            b.tvKeyword.text = rule.keyword
            b.tvSub.text = buildString {
                append(if (rule.direction == Direction.EXPENSE) "支出" else "收入")
                append(" → ").append(cat.label)
                if (rule.builtin) append(" · 内置")
            }
            b.btnDelete.setOnClickListener { onDelete(rule) }
        }
    }
}
