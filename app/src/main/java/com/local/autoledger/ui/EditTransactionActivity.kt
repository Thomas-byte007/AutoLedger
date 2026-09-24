package com.local.autoledger.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.local.autoledger.R
import com.local.autoledger.capture.CaptureLog
import com.local.autoledger.data.Accounts
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 手动记账 / 编辑已有记录。
 *
 * 交互照「记一笔」的做法：金额由底部自带的小键盘输入（不弹系统输入法），
 * 分类走一级类 › 二级类的下钻选择，账户 / 时间 / 对方 / 备注各占一行。
 * 自动抓取失败的账目也会跳到这里让用户补分类。
 */
class EditTransactionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditTxnBinding

    private var editing: Txn? = null
    private var direction: Direction = Direction.EXPENSE
    private var categoryKey: String = Categories.otherExpense.key
    private var occurredAt: Long = System.currentTimeMillis()
    private var account: String = Accounts.DEFAULT.label
    private var userPicked = false

    /** 「来源 / 原始信息」那一行是否已展开（默认收成一行，不挤走删除按钮） */
    private var sourceExpanded = false

    /** 键盘输入的原始数字串，例如 "128.5"；空串表示还没输入 */
    private var amountInput: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditTxnBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildTabs()
        buildKeypad()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnConfirm.setOnClickListener { save() }
        binding.rowCategory.setOnClickListener { openPicker() }
        binding.rowAccount.setOnClickListener { pickAccount() }
        binding.rowTime.setOnClickListener { pickTime() }
        binding.btnNow.setOnClickListener {
            occurredAt = System.currentTimeMillis()
            renderTime()
        }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.etMerchant.doAfterTextChanged { onMerchantChanged() }

        // 输入框拿到焦点时把小键盘收起来，让系统输入法有地方站
        binding.etMerchant.setOnFocusChangeListener { _, focused -> toggleKeypad(!focused) }
        binding.etNote.setOnFocusChangeListener { _, focused -> toggleKeypad(!focused) }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id > 0L) {
            binding.tvTitle.setText(R.string.edit_entry_title)
            // 记录是异步读出来的，读完再露删除按钮 —— 否则快速点击会撞上 editing 还是 null
            binding.btnDelete.isVisible = false
            lifecycleScope.launch {
                val t = withContext(Dispatchers.IO) { LedgerRepository.byId(id) }
                if (t == null) finish() else bindExisting(t)
            }
        } else {
            binding.tvTitle.setText(R.string.quick_entry_title)
            binding.btnDelete.isVisible = false
        }

        renderDirectionChrome()
        renderCategory()
        renderAccount()
        renderTime()
        renderAmount()
    }

    // ------------------------------------------------------------------ 方向

    private fun buildTabs() {
        binding.tabDirection.addTab(
            binding.tabDirection.newTab().setText(R.string.expense), 0, true
        )
        binding.tabDirection.addTab(binding.tabDirection.newTab().setText(R.string.income), 1, false)
        binding.tabDirection.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = switchDirection(tab.position)

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun switchDirection(position: Int) {
        val dir = if (position == 1) Direction.INCOME else Direction.EXPENSE
        if (direction == dir) return
        direction = dir
        userPicked = false
        categoryKey = Categories.fallbackOf(dir).key
        // 转账红包这类分类在收入方向上不存在，切完方向顺手把兜底也换掉
        renderDirectionChrome()
        renderCategory()
        onMerchantChanged()
    }

    private fun renderDirectionChrome() {
        val expense = direction == Direction.EXPENSE
        val color = ThemeUtil.color(
            this, if (expense) R.color.brand_primary else R.color.income_green
        )
        binding.tvCurrency.setTextColor(color)
        binding.tvDirectionTag.text = getString(if (expense) R.string.expense else R.string.income)
        binding.tvDirectionTag.setTextColor(color)
    }

    // ------------------------------------------------------------------ 小键盘

    private fun buildKeypad() {
        val digits = mapOf(
            binding.key0 to "0", binding.key1 to "1", binding.key2 to "2", binding.key3 to "3",
            binding.key4 to "4", binding.key5 to "5", binding.key6 to "6", binding.key7 to "7",
            binding.key8 to "8", binding.key9 to "9"
        )
        digits.forEach { (view, d) -> view.setOnClickListener { appendDigit(d) } }
        binding.keyDot.setOnClickListener { appendDot() }
        binding.keyBackspace.setOnClickListener { backspace() }
        binding.keyMinus.setOnClickListener { nudgeAmount(-100L) }
        binding.keyPlus.setOnClickListener { nudgeAmount(100L) }
    }

    private fun appendDigit(d: String) {
        val dot = amountInput.indexOf('.')
        if (dot >= 0 && amountInput.length - dot > 2) return // 最多两位小数
        if (dot < 0 && amountInput.length >= 9) return // 整数最多 9 位
        amountInput = if (amountInput == "0") d else amountInput + d
        renderAmount()
    }

    private fun appendDot() {
        if (amountInput.contains('.')) return
        amountInput = if (amountInput.isEmpty()) "0." else "$amountInput."
        renderAmount()
    }

    private fun backspace() {
        if (amountInput.isEmpty()) return
        amountInput = amountInput.dropLast(1)
        renderAmount()
    }

    /** −/＋ 每次调 1 元，省得点半天 */
    private fun nudgeAmount(deltaCents: Long) {
        val current = Money.parseToCents(amountInput) ?: 0L
        val next = (current + deltaCents).coerceAtLeast(0L)
        amountInput = if (next == 0L) "" else Money.plain(next)
        renderAmount()
    }

    private fun renderAmount() {
        if (amountInput.isEmpty()) {
            binding.tvAmount.setText(R.string.amount_placeholder)
            binding.tvAmount.setTextColor(ThemeUtil.color(this, R.color.text_tertiary))
        } else {
            binding.tvAmount.text = amountInput
            binding.tvAmount.setTextColor(ThemeUtil.color(this, R.color.text_primary))
        }
    }

    private fun toggleKeypad(show: Boolean) {
        binding.keypad.isVisible = show
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderCategory() {
        val cat = Categories.byKeyOrNull(categoryKey, direction) ?: Categories.fallbackOf(direction)
        categoryKey = cat.key
        val group = Categories.groupOf(cat.key, direction)
        binding.tvCategory.text =
            if (cat.label == group.label) group.label else "${group.label} › ${cat.label}"
    }

    private fun renderAccount() {
        binding.tvAccount.text = Accounts.labelOf(account)
    }

    private fun renderTime() {
        val date = TimeFmt.toDate(occurredAt)
        val today = LocalDate.now()
        val prefix = when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> "${date.year}年"
        }
        binding.tvTime.text = "$prefix ${TimeFmt.dayLabel(date)} ${TimeFmt.hm(occurredAt)}"
    }

    private fun onMerchantChanged() {
        val merchant = binding.etMerchant.text?.toString()?.trim().orEmpty()
        if (userPicked || merchant.isBlank()) return
        val match = LedgerRepository.classify(direction, merchant, "")
        if (match.auto) {
            categoryKey = match.categoryKey
            renderCategory()
        }
    }

    private fun bindExisting(t: Txn) {
        editing = t
        direction = t.direction
        categoryKey = Categories.byKey(t.categoryKey, t.direction).key
        occurredAt = t.occurredAt
        account = t.payMethod.ifBlank { Accounts.DEFAULT.label }
        userPicked = true
        binding.btnDelete.isVisible = true

        binding.tabDirection.selectTab(binding.tabDirection.getTabAt(if (direction == Direction.INCOME) 1 else 0))
        amountInput = Money.plain(t.amountCents)

        binding.etMerchant.setText(t.merchant)
        binding.etNote.setText(t.note)
        binding.rowRemember.isVisible = t.merchant.isNotBlank()

        if (t.channel != Channel.MANUAL || t.rawText.isNotBlank()) {
            // 通知抓来的记录原始文本可能很长，默认只留一行提示，
            // 免得把下面的「删除这条记录」顶出首屏（删除是高频操作，必须一眼能看到）
            binding.tvSource.setOnClickListener {
                sourceExpanded = !sourceExpanded
                renderSource(t)
            }
            renderSource(t)
        }

        renderDirectionChrome()
        renderCategory()
        renderAccount()
        renderTime()
        renderAmount()
    }

    private fun renderSource(t: Txn) {
        binding.tvSource.isVisible = true
        binding.tvSource.text = if (sourceExpanded && t.rawText.isNotBlank()) {
            "来源：${t.channel.label}\n原始信息：${t.rawText}"
        } else if (t.rawText.isNotBlank()) {
            "来源：${t.channel.label} · 点这里看原始信息"
        } else {
            "来源：${t.channel.label}"
        }
    }

    // ------------------------------------------------------------------ 交互

    /** 点「分类」行：在当前页底部滑出类别选择面板，不再跳转新页面 */
    private fun openPicker() {
        CategoryPickerSheet(
            activity = this,
            direction = direction,
            selectedKey = categoryKey,
            merchant = binding.etMerchant.text?.toString()?.trim().orEmpty()
        ) { picked ->
            categoryKey = picked.key
            // 用户自己挑的，之后改对方名字不要再被自动分类顶掉
            userPicked = true
            renderCategory()
        }.show()
    }

    private fun pickAccount() {
        val labels = Accounts.ALL.map { it.label }.toTypedArray()
        val checked = Accounts.ALL.indexOfFirst { it.label == account }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.field_account)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                account = Accounts.ALL[which].label
                renderAccount()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

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
                        renderTime()
                    },
                    dt.hour, dt.minute, true
                ).show()
            },
            dt.year, dt.monthValue - 1, dt.dayOfMonth
        ).show()
    }

    private fun save() {
        val cents = Money.parseToCents(amountInput)
        if (cents == null || cents <= 0L) {
            Toast.makeText(this, R.string.toast_need_amount, Toast.LENGTH_SHORT).show()
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
                payMethod = account,
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
                payMethod = account,
                occurredAt = occurredAt,
                note = note,
                autoCategory = true
            )
        }

        val remember = binding.swRemember.isChecked && merchant.isNotBlank()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LedgerRepository.save(txn)
                if (remember) LedgerRepository.addRule(merchant, cat.key, direction)
            }
            LedgerBus.notifyChanged()
            Toast.makeText(this@EditTransactionActivity, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun confirmDelete() {
        val t = editing
        if (t == null) {
            Toast.makeText(this, "记录还在读取，稍等一下", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除这条记录？")
            .setMessage("删除后无法恢复。之后即使付款通知被系统重放，也不会把这条写回来。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val removed = withContext(Dispatchers.IO) { LedgerRepository.delete(t.id) }
                    val merchant = if (t.merchant.isNotBlank()) " · ${t.merchant}" else ""
                    CaptureLog.add(
                        "删除",
                        "命中 $removed 行 · ${t.channel.label} " +
                            "${Money.signed(t.amountCents, t.direction)}$merchant"
                    )
                    LedgerBus.notifyChanged()
                    Toast.makeText(
                        this@EditTransactionActivity,
                        if (removed > 0) R.string.toast_deleted else R.string.toast_gone,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
            .show()
    }

    /** 供外部（报表下钻等）直接打开一条记录的编辑页 */
    companion object {
        const val EXTRA_ID = "extra_txn_id"

        fun intent(context: android.content.Context, id: Long): Intent =
            Intent(context, EditTransactionActivity::class.java)
                .putExtra(EXTRA_ID, id)
    }
}
