package com.local.autoledger.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.local.autoledger.R
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.Prefs
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.data.Txn
import com.local.autoledger.databinding.FragmentTransactionsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/** 明细页：本月概览 + 按天分组的流水 */
class TransactionsFragment : Fragment() {

    private var _binding: FragmentTransactionsBinding? = null
    private val binding get() = _binding!!

    private var ym: YearMonth = YearMonth.now()
    private var filter: Filter = Filter.ALL

    private val adapter = TxnAdapter { txn -> openEditor(txn.id) }
    private val refresh: () -> Unit = { load() }

    enum class Filter(val label: String) {
        ALL("全部"), EXPENSE("支出"), INCOME("收入"), PENDING("待分类")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null

        binding.btnPrevMonth.setOnClickListener {
            ym = ym.minusMonths(1)
            load()
        }
        binding.btnNextMonth.setOnClickListener {
            if (ym < YearMonth.now()) {
                ym = ym.plusMonths(1)
                load()
            }
        }
        binding.fab.setOnClickListener { openEditor(null) }

        val goGrant = View.OnClickListener {
            PermissionsUtil.openNotificationAccessSettings(requireContext())
        }
        binding.btnBannerAction.setOnClickListener(goGrant)
        binding.captureBanner.setOnClickListener(goGrant)

        buildChips()
        load()
    }

    override fun onStart() {
        super.onStart()
        LedgerBus.register(refresh)
    }

    override fun onStop() {
        LedgerBus.unregister(refresh)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回时刷新授权提示
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------------ 逻辑

    private fun buildChips() {
        val ctx = requireContext()
        val bg = AppCompatResources.getColorStateList(ctx, R.color.selector_chip_bg)
        val fg = AppCompatResources.getColorStateList(ctx, R.color.selector_chip_text)
        Filter.entries.forEach { f ->
            val chip = Chip(ctx).apply {
                id = CHIP_ID_BASE + f.ordinal
                text = f.label
                isCheckable = true
                isChecked = f == filter
                isCheckedIconVisible = false
                chipStrokeWidth = 0f
                chipBackgroundColor = bg
                setTextColor(fg)
                setOnClickListener {
                    if (filter != f) {
                        filter = f
                        load()
                    }
                }
            }
            binding.chipGroup.addView(chip)
        }
    }

    private fun load() {
        val month = ym
        val f = filter
        val host = viewLifecycleOwner
        host.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val all = LedgerRepository.monthTxns(month)
                val summary = LedgerRepository.monthSummary(month)
                val list = when (f) {
                    Filter.ALL -> all
                    Filter.EXPENSE -> all.filter { it.direction == Direction.EXPENSE }
                    Filter.INCOME -> all.filter { it.direction == Direction.INCOME }
                    Filter.PENDING -> all.filter { it.isPending }
                }
                summary to list
            }
            if (_binding == null) return@launch
            render(data.first, data.second)
        }
    }

    private fun render(summary: com.local.autoledger.data.MonthSummary, list: List<Txn>) {
        val ctx = requireContext()
        binding.tvMonth.text = TimeFmt.monthLabel(ym)

        binding.tvExpense.text = Money.yuan(summary.expenseCents)
        binding.tvIncome.text = Money.yuan(summary.incomeCents)
        binding.tvBalance.text = Money.yuan(summary.balanceCents)
        binding.tvBalance.setTextColor(
            if (summary.balanceCents < 0) 0xFFFFD5D3.toInt() else 0xFFFFFFFF.toInt()
        )

        binding.btnNextMonth.alpha = if (ym < YearMonth.now()) 1f else 0.4f

        adapter.submit(list)

        val empty = list.isEmpty()
        binding.emptyView.isVisible = empty
        binding.recycler.isVisible = !empty
        if (empty) {
            when (filter) {
                Filter.ALL -> {
                    binding.tvEmptyTitle.text = getString(R.string.empty_title)
                    binding.tvEmptyDesc.text = getString(R.string.empty_desc)
                }
                Filter.PENDING -> {
                    binding.tvEmptyTitle.text = "没有待分类的记录"
                    binding.tvEmptyDesc.text = "所有账目都已经归类完成"
                }
                else -> {
                    binding.tvEmptyTitle.text = "这个月没有${filter.label}记录"
                    binding.tvEmptyDesc.text = "换个筛选条件看看，或者手动记一笔。"
                }
            }
        }

        // 未授权时的引导条
        val needGrant = !PermissionsUtil.hasNotificationAccess(ctx) && !Prefs.dismissOnboard
        binding.captureBanner.isVisible = needGrant
        if (needGrant) {
            binding.tvBanner.text = "开启「读取支付通知」后，微信和支付宝的付款会自动记进来"
        }
    }

    private fun openEditor(id: Long?) {
        val intent = Intent(requireContext(), EditTransactionActivity::class.java)
        if (id != null) intent.putExtra(EditTransactionActivity.EXTRA_ID, id)
        startActivity(intent)
    }

    private companion object {
        const val CHIP_ID_BASE = 1000
    }
}
