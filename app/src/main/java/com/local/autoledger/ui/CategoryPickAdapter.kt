package com.local.autoledger.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.local.autoledger.data.Category
import com.local.autoledger.databinding.ItemCatPickBinding

/** 分类九宫格选择器 */
class CategoryPickAdapter(
    private val onPick: (Category) -> Unit
) : RecyclerView.Adapter<CategoryPickAdapter.Holder>() {

    private var items: List<Category> = emptyList()
    private var selectedKey: String? = null

    fun submit(list: List<Category>, selected: String?) {
        items = list
        selectedKey = selected
        notifyDataSetChanged()
    }

    fun setSelected(key: String?) {
        if (selectedKey == key) return
        selectedKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCatPickBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val b: ItemCatPickBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: Category) {
            val ctx = b.root.context
            val base = ThemeUtil.color(ctx, item.colorRes)
            val selected = item.key == selectedKey

            b.tvIcon.text = item.emoji
            b.tvIcon.backgroundTintList = ColorStateList.valueOf(
                if (selected) ThemeUtil.softStrong(ctx, base) else ThemeUtil.soft(ctx, base)
            )
            b.tvName.text = item.label
            b.tvName.setTextColor(
                if (selected) ThemeUtil.color(ctx, com.local.autoledger.R.color.brand_primary)
                else ThemeUtil.color(ctx, com.local.autoledger.R.color.text_secondary)
            )
            b.tvName.setTypeface(
                null,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            b.root.setOnClickListener {
                setSelected(item.key)
                onPick(item)
            }
        }
    }
}
