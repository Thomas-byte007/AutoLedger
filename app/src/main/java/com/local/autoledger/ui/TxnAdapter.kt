package com.local.autoledger.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.local.autoledger.data.Accounts
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Direction
import com.local.autoledger.data.Money
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.data.Txn
import com.local.autoledger.databinding.ItemDayHeaderBinding
import com.local.autoledger.databinding.ItemTxnBinding
import java.time.LocalDate

/** 明细列表：按天分组 */
class TxnAdapter(private val onClick: (Txn) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Row {
        data class Day(val date: LocalDate, val expense: Long, val income: Long) : Row
        data class Entry(val txn: Txn) : Row
    }

    private val rows = ArrayList<Row>()

    fun submit(list: List<Txn>) {
        rows.clear()
        list.groupBy { TimeFmt.toDate(it.occurredAt) }
            .toSortedMap(compareByDescending { it })
            .forEach { (date, dayList) ->
                val expense = dayList.filter { it.direction == Direction.EXPENSE }
                    .sumOf { it.amountCents }
                val income = dayList.filter { it.direction == Direction.INCOME }
                    .sumOf { it.amountCents }
                rows += Row.Day(date, expense, income)
                dayList.sortedByDescending { it.occurredAt }.forEach { rows += Row.Entry(it) }
            }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Day) TYPE_DAY else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DAY) {
            DayHolder(ItemDayHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryHolder(ItemTxnBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Day -> (holder as DayHolder).bind(row)
            is Row.Entry -> (holder as EntryHolder).bind(row.txn)
        }
    }

    inner class DayHolder(private val b: ItemDayHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(row: Row.Day) {
            val ctx = b.root.context
            val today = LocalDate.now()
            val prefix = when (row.date) {
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> weekdayOf(row.date)
            }
            b.tvDay.text = "${TimeFmt.dayLabel(row.date)} · $prefix"
            b.tvDayTotal.text = when {
                row.expense > 0 && row.income > 0 ->
                    "支出 ${Money.plain(row.expense)}  收入 ${Money.plain(row.income)}"
                row.expense > 0 -> "支出 ${Money.plain(row.expense)}"
                row.income > 0 -> "收入 ${Money.plain(row.income)}"
                else -> ""
            }
            b.tvDayTotal.setTextColor(ThemeUtil.color(ctx, com.local.autoledger.R.color.text_tertiary))
        }
    }

    inner class EntryHolder(private val b: ItemTxnBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(txn: Txn) {
            val ctx = b.root.context
            val cat = Categories.byKey(txn.categoryKey, txn.direction)

            CategoryIcons.bind(b.iconBg, b.ivIcon, cat)

            // 类别是主角（参考主流记账 App）：标题放分类名，商户 / 账户 / 来源弱化进副标题
            b.tvTitle.text = cat.label

            val sub = listOf(
                if (txn.payMethod.isNotBlank()) Accounts.labelOf(txn.payMethod) else "",
                txn.merchant,
                if (txn.merchant.isNotBlank()) txn.channel.short else ""
            ).filter { it.isNotBlank() }.joinToString(" · ")
            b.tvSub.text = sub.ifBlank { txn.channel.label }

            b.tvBadge.isVisible = txn.isPending

            b.tvAmount.text = Money.signed(txn.amountCents, txn.direction)
            b.tvAmount.setTextColor(
                if (txn.direction == Direction.EXPENSE) ThemeUtil.expenseColor(ctx)
                else ThemeUtil.incomeColor(ctx)
            )
            b.tvTime.text = TimeFmt.hm(txn.occurredAt)

            b.root.setOnClickListener { onClick(txn) }
        }
    }

    private fun weekdayOf(date: LocalDate): String = when (date.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

    private companion object {
        const val TYPE_DAY = 0
        const val TYPE_ITEM = 1
    }
}

/** 空状态与列表的切换 */
internal fun View.toggle(show: Boolean) {
    isVisible = show
}
