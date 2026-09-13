package com.local.autoledger.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.data.Txn
import com.local.autoledger.databinding.ActivityEditTxnBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * 手动记账 / 编辑已有记录。
 * 自动抓取失败的账目也会跳到这里让用户补分类，保证「无法识别时可手动分类」。
 */
class EditTransactionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditTxnBinding
    private lateinit var catAdapter: CategoryPickAdapter

    private var editing: Txn? = null
    private var direction: Direction = Direction.EXPENSE
    private var categoryKey: String = Categories.otherExpense.key
    private var occurredAt: Long = System.currentTimeMillis()
    private var userPicked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditTxnBinding.inflate(layoutInflater)
        setContentView(binding.root)

        catAdapter = CategoryPickAdapter { cat ->
            categoryKey = cat.key
            userPicked = true
        }
        binding.catRecycler.layoutManager = GridLayoutManager(this, 5)
        binding.catRecycler.adapter = catAdapter

        binding.btnClose.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.segExpense.setOnClickListener { switchDirection(Direction.EXPENSE) }
        binding.segIncome.setOnClickListener { switchDirection(Direction.INCOME) }
        binding.rowTime.setOnClickListener { pickTime() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        binding.etMerchant.doAfterTextChanged { onMerchantChanged() }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id > 0L) {
            binding.tvTitle.text = "编辑记录"
            binding.btnDelete.isVisible = true
            lifecycleScope.launch {
                val t = withContext(Dispatchers.IO) { LedgerRepository.byId(id) }
                if (t == null) {
                    finish()
                } else {
                    bindExisting(t)
                }
            }
        } else {
            binding.tvTitle.text = "记一笔"
        }

        renderSegment()
        renderCategories()
        updateTimeLabel()
    }

    // ------------------------------------------------------------------ 渲染

    private fun switchDirection(dir: Direction) {
        if (direction == dir) return
        direction = dir
        userPicked = false
        categoryKey = Categories.fallbackOf(dir).key
        renderSegment()
        renderCategories()
        onMerchantChanged()
    }

    private fun renderSegment() {
        val expense = direction == Direction.EXPENSE
        binding.segExpense.background = if (expense) thumb() else null
        binding.segIncome.background = if (!expense) thumb() else null
        binding.segExpense.setTextColor(
            ThemeUtil.color(this, if (expense) R.color.text_primary else R.color.text_secondary)
        )
        binding.segIncome.setTextColor(
            ThemeUtil.color(this, if (expense) R.color.text_secondary else R.color.text_primary)
        )
        binding.tvCurrency.setTextColor(
            ThemeUtil.color(
                this,
                if (expense) R.color.expense_red else R.color.income_green
            )
        )
    }

    private fun thumb(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 14f * resources.displayMetrics.density
        setColor(ThemeUtil.color(this@EditTransactionActivity, R.color.surface_card))
    }

    private fun renderCategories() {
        val list = if (direction == Direction.EXPENSE) Categories.EXPENSES else Categories.INCOMES
        if (list.none { it.key == categoryKey }) {
            categoryKey = Categories.fallbackOf(direction).key
        }
        catAdapter.submit(list, categoryKey)
    }

    private fun updateTimeLabel() {
        binding.tvTime.text = TimeFmt.detail(occurredAt)
    }

    private fun onMerchantChanged() {
        val merchant = binding.etMerchant.text?.toString()?.trim().orEmpty()
        binding.rowRemember.isVisible = merchant.isNotBlank()
        if (userPicked || merchant.isBlank()) return
        val match = LedgerRepository.classify(direction, merchant, "")
        if (match.auto) {
            categoryKey = match.categoryKey
            catAdapter.setSelected(categoryKey)
        }
    }

    private fun bindExisting(t: Txn) {
        editing = t
        direction = t.direction
        categoryKey = Categories.byKey(t.categoryKey, t.direction).key
        occurredAt = t.occurredAt
        userPicked = !t.autoCategory

        binding.etAmount.setText(Money.plain(t.amountCents))
        binding.etAmount.setSelection(binding.etAmount.text?.length ?: 0)
        binding.etMerchant.setText(t.merchant)
        binding.etNote.setText(t.note)
        binding.rowRemember.isVisible = t.merchant.isNotBlank()

        if (t.channel != Channel.MANUAL || t.rawText.isNotBlank()) {
            binding.tvSource.isVisible = true
            binding.tvSource.text = buildString {
                append("来源：").append(t.channel.label)
                if (t.payMethod.isNotBlank()) append(" · ").append(t.payMethod)
                if (t.rawText.isNotBlank()) append("\n原始信息：").append(t.rawText)
            }
        }

        renderSegment()
        renderCategories()
        updateTimeLabel()
    }

    // ------------------------------------------------------------------ 交互

    private fun pickTime() {
        val dt = TimeFmt.toLocal(occurredAt)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        occurredAt = TimeFmt.toMillis(
                            LocalDateTime.of(year, month + 1, day, hour, minute)
                        )
                        updateTimeLabel()
                    },
                    dt.hour, dt.minute, true
                ).show()
            },
            dt.year, dt.monthValue - 1, dt.dayOfMonth
        ).show()
    }

    private fun save() {
        val cents = Money.parseToCents(binding.etAmount.text?.toString())
        if (cents == null || cents <= 0L) {
            Toast.makeText(this, "请先输入金额", Toast.LENGTH_SHORT).show()
            binding.etAmount.requestFocus()
            return
        }
        val merchant = binding.etMerchant.text?.toString()?.trim().orEmpty()
        val note = binding.etNote.text?.toString()?.trim().orEmpty()
        val cat = Categories.byKey(categoryKey, direction)

        val base = editing
        val txn = if (base == null) {
            Txn(
                amountCents = cents,
                direction = direction,
                categoryKey = cat.key,
                merchant = merchant,
                channel = Channel.MANUAL,
                occurredAt = occurredAt,
                autoCategory = true,
                note = note
            )
        } else {
            base.copy(
                amountCents = cents,
                direction = direction,
                categoryKey = cat.key,
                merchant = merchant,
                occurredAt = occurredAt,
                note = note,
                autoCategory = true
            )
        }

        val remember = binding.swRemember.isChecked && merchant.isNotBlank()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LedgerRepository.save(txn)
                if (remember) {
                    LedgerRepository.addRule(merchant, cat.key, direction)
                }
            }
            LedgerBus.notifyChanged()
            Toast.makeText(this@EditTransactionActivity, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun confirmDelete() {
        val t = editing ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除这条记录？")
            .setMessage("删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { LedgerRepository.delete(t.id) }
                    LedgerBus.notifyChanged()
                    finish()
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "extra_txn_id"
    }
}
