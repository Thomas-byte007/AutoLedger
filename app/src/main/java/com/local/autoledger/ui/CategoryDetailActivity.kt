package com.local.autoledger.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.databinding.ActivityCategoryDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * 某个分类（一级类或二级类）在指定月份的流水明细。
 *
 * 报表页「支出分布」展开后点一行就会走到这里，再点一条记录直接进编辑页，
 * 于是「看占比 → 看分类构成 → 看每一笔 → 改这一笔」这条路径是通的。
 */
class CategoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryDetailBinding
    private val adapter = TxnAdapter { txn -> openEditor(txn.id) }

    private var ym: YearMonth = YearMonth.now()
    private var direction: Direction = Direction.EXPENSE
    private var groupKey: String? = null
    private var categoryKey: String? = null
    private var title: String = ""

    /** 年度账单下钻时是「一整年」而不是某个月 */
    private var year: Int? = null

    private val refresh: () -> Unit = { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ym = YearMonth.of(
            intent.getIntExtra(EXTRA_YEAR, YearMonth.now().year),
            intent.getIntExtra(EXTRA_MONTH, YearMonth.now().monthValue).coerceIn(1, 12)
        )
        direction = Direction.of(intent.getIntExtra(EXTRA_DIRECTION, Direction.EXPENSE.code))
        groupKey = intent.getStringExtra(EXTRA_GROUP_KEY)
        categoryKey = intent.getStringExtra(EXTRA_CATEGORY_KEY)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        intent.getIntExtra(EXTRA_YEAR_ONLY, 0).takeIf { it > 0 }?.let { year = it }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null
        binding.btnBack.setOnClickListener { finish() }

        renderChip()
    }

    override fun onStart() {
        super.onStart()
        LedgerBus.register(refresh)
    }

    /** 在编辑页删除记录后返回时总线事件已错过（当时整个任务在 onStop），回前台必须重查 */
    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onStop() {
        LedgerBus.unregister(refresh)
        super.onStop()
    }

    private fun load() {
        val month = ym
        val dir = direction
        val gk = groupKey
        val ck = categoryKey
        val wholeYear = year
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                if (wholeYear != null) {
                    LedgerRepository.categoryTxnsOfYear(wholeYear, dir, gk, ck)
                } else {
                    LedgerRepository.categoryTxns(month, dir, gk, ck)
                }
            }
            render(list)
        }
    }

    private fun render(list: List<com.local.autoledger.data.Txn>) {
        val total = list.sumOf { it.amountCents }
        binding.tvTitle.text = title.ifBlank { getString(R.string.tab_report) }
        binding.tvMonth.text = (year?.let { "${it} 年 · " } ?: "${TimeFmt.monthLabel(ym)} · ") +
            "${list.size} 笔"
        binding.tvTotal.text = Money.yuan(total)

        adapter.submit(list)
        binding.emptyView.isVisible = list.isEmpty()
        binding.recycler.isVisible = list.isNotEmpty()
    }

    private fun renderChip() {
        val leaf = categoryKey?.let { Categories.byKeyOrNull(it, direction) }
        val group = Categories.groupByKey(groupKey)
        binding.tvChipLabel.text = when {
            leaf != null && group != null -> "${group.label} › ${leaf.label}"
            leaf != null -> leaf.label
            group != null -> group.label
            else -> ""
        }
        // 胶囊底色是半透明白，图标统一走白色描边，一级 / 二级类都是矢量图标
        when {
            leaf != null -> binding.ivChipIcon.setImageResource(leaf.iconRes)
            group != null -> binding.ivChipIcon.setImageResource(group.iconRes)
            else -> binding.categoryChipRow.isVisible = false
        }
    }

    private fun openEditor(id: Long) {
        startActivity(EditTransactionActivity.intent(this, id))
    }

    companion object {
        private const val EXTRA_YEAR = "extra_year"
        private const val EXTRA_MONTH = "extra_month"
        private const val EXTRA_YEAR_ONLY = "extra_year_only"
        private const val EXTRA_DIRECTION = "extra_direction"
        private const val EXTRA_GROUP_KEY = "extra_group_key"
        private const val EXTRA_CATEGORY_KEY = "extra_category_key"
        private const val EXTRA_TITLE = "extra_title"

        fun intent(
            context: Context,
            ym: YearMonth,
            direction: Direction,
            groupKey: String?,
            categoryKey: String?,
            title: String
        ): Intent = Intent(context, CategoryDetailActivity::class.java)
            .putExtra(EXTRA_YEAR, ym.year)
            .putExtra(EXTRA_MONTH, ym.monthValue)
            .putExtra(EXTRA_DIRECTION, direction.code)
            .putExtra(EXTRA_GROUP_KEY, groupKey)
            .putExtra(EXTRA_CATEGORY_KEY, categoryKey)
            .putExtra(EXTRA_TITLE, title)

        /** 年度账单专用：看这一整年，而不是某个月 */
        fun intentForYear(
            context: Context,
            year: Int,
            direction: Direction,
            groupKey: String?,
            categoryKey: String?,
            title: String
        ): Intent = Intent(context, CategoryDetailActivity::class.java)
            .putExtra(EXTRA_YEAR_ONLY, year)
            .putExtra(EXTRA_DIRECTION, direction.code)
            .putExtra(EXTRA_GROUP_KEY, groupKey)
            .putExtra(EXTRA_CATEGORY_KEY, categoryKey)
            .putExtra(EXTRA_TITLE, title)
    }
}
