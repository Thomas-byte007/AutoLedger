package com.local.autoledger.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Category
import com.local.autoledger.data.CategoryGroup
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.databinding.ActivityCategoryManageBinding
import com.local.autoledger.databinding.ItemManageCatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分类管理：一级类 / 二级类都能改名、换图标、换色，也能删。
 *
 * 内置分类的 key 已经写进历史账目了，不能真的删掉：
 *  - 改名 / 换色 / 换图标 / 挪位置 → 写补丁表；
 *  - 删除 → 记账目改挂到兜底分类 + 打隐藏标记；
 *  - 「恢复内置」清空补丁表，回到出厂字典。
 * 自建分类是真删（连带它自己的自定义关键词）。
 */
class CategoryManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryManageBinding

    private var direction: Direction = Direction.EXPENSE

    /** 二级类 key -> 账目笔数，渲染时用来告诉用户「删掉它会动到多少记录」 */
    private var usage: Map<String, Int> = emptyMap()

    private val refresh: () -> Unit = { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        direction = Direction.of(intent.getIntExtra(EXTRA_DIRECTION, Direction.EXPENSE.code))

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRestore.setOnClickListener { confirmRestore() }
        binding.btnAdd.setOnClickListener {
            CategoryEditor.show(this, direction, null) { load() }
        }

        binding.tabDirection.addTab(binding.tabDirection.newTab().setText(R.string.expense))
        binding.tabDirection.addTab(binding.tabDirection.newTab().setText(R.string.income))
        binding.tabDirection.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                direction = if (tab.position == 1) Direction.INCOME else Direction.EXPENSE
                load()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.tabDirection.getTabAt(
            if (direction == Direction.INCOME) 1 else 0
        )?.select()

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

    // ------------------------------------------------------------------ 渲染

    private fun load() {
        val dir = direction
        lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                Categories.of(dir).map { it.key to LedgerRepository.countOf(it.key) }.toMap()
            }
            usage = counts
            render()
        }
    }

    private fun render() {
        val container = binding.groupContainer
        container.removeAllViews()

        val groups = Categories.groupsOf(direction)
        val leaves = Categories.of(direction)
        binding.emptyView.isVisible = groups.isEmpty()
        binding.tvManageHint.text =
            "${direction.label} · 一级类 ${groups.size} 个 · 二级类 ${leaves.size} 个"

        groups.forEachIndexed { index, group ->
            val children = leaves.filter { it.group == group.key }
            val childTxns = children.sumOf { usage[it.key] ?: 0 }
            if (index > 0) container.addView(divider())

            container.addView(
                groupRow(group, children.size, childTxns) { action ->
                    when (action) {
                        RowAction.EDIT -> CategoryEditor.showGroup(this, group) { load() }
                        RowAction.DELETE -> confirmDeleteGroup(group)
                    }
                }
            )

            children.forEach { child ->
                container.addView(
                    leafRow(child) { action ->
                        when (action) {
                            RowAction.EDIT -> CategoryEditor.show(this, direction, child) { load() }
                            RowAction.DELETE -> confirmDeleteLeaf(child)
                        }
                    }
                )
            }

            container.addView(addChildRow(group))
        }
    }

    private enum class RowAction { EDIT, DELETE }

    private fun groupRow(
        group: CategoryGroup,
        childCount: Int,
        txnCount: Int,
        onAction: (RowAction) -> Unit
    ): android.view.View {
        val row = ItemManageCatBinding.inflate(LayoutInflater.from(this), binding.groupContainer, false)
        CategoryIcons.bind(row.iconBg, row.ivIcon, group)
        row.tvName.text = group.label
        row.tvSource.text = getString(R.string.manage_group_tag)
        row.tvSub.text = "$childCount 个子类 · $txnCount 笔"
        row.btnEdit.setOnClickListener { onAction(RowAction.EDIT) }
        row.btnDelete.setOnClickListener { onAction(RowAction.DELETE) }
        return row.root
    }

    private fun leafRow(child: Category, onAction: (RowAction) -> Unit): android.view.View {
        val row = ItemManageCatBinding.inflate(LayoutInflater.from(this), binding.groupContainer, false)
        CategoryIcons.bind(row.iconBg, row.ivIcon, child)
        row.tvName.text = child.label
        row.tvSource.text = getString(if (child.custom) R.string.manage_custom else R.string.manage_builtin)
        row.tvSub.text = getString(R.string.manage_txn_count, usage[child.key] ?: 0)
        row.btnEdit.setOnClickListener { onAction(RowAction.EDIT) }
        row.btnDelete.setOnClickListener { onAction(RowAction.DELETE) }
        row.root.setPadding(
            dp(30), row.root.paddingTop, row.root.paddingRight, row.root.paddingBottom
        )
        return row.root
    }

    private fun addChildRow(group: CategoryGroup): android.view.View =
        android.widget.TextView(this).apply {
            text = getString(R.string.manage_add_sub)
            setTextColor(ThemeUtil.color(this@CategoryManageActivity, R.color.brand_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this@CategoryManageActivity, R.drawable.ripple_item)
            setPadding(dp(30), dp(10), dp(10), dp(10))
            setOnClickListener {
                // 预选中当前一级类：编辑器默认落在这个组里
                CategoryEditor.showWithGroup(this@CategoryManageActivity, direction, group.key) { load() }
            }
        }

    private fun divider(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(ThemeUtil.color(this@CategoryManageActivity, R.color.divider))
    }

    // ------------------------------------------------------------------ 交互

    private fun confirmDeleteLeaf(child: Category) {
        val used = usage[child.key] ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.picker_delete_title, child.label))
            .setMessage(
                if (used > 0) {
                    "「${child.label}」下有 $used 笔账目，删除后它们会改挂到「${
                        Categories.fallbackOf(direction).label
                    }」，记录不会丢。"
                } else {
                    getString(R.string.picker_delete_msg)
                }
            )
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(R.string.editor_action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { LedgerRepository.deleteCategory(child.key, direction) }
                    LedgerBus.notifyChanged()
                    toast(getString(R.string.editor_done, child.label))
                    load()
                }
            }
            .show()
    }

    private fun confirmDeleteGroup(group: CategoryGroup) {
        val fallback = Categories.fallbackGroupOf(direction)
        if (group.key == fallback.key) {
            toast(getString(R.string.manage_cannot_delete))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.manage_delete_group_title, group.label))
            .setMessage(getString(R.string.manage_delete_group_msg, fallback.label))
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(R.string.editor_action_delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        LedgerRepository.deleteCategoryGroup(group.key, direction) >= 0
                    }
                    if (!ok) {
                        toast(getString(R.string.manage_cannot_delete))
                        return@launch
                    }
                    LedgerBus.notifyChanged()
                    toast(getString(R.string.manage_group_deleted))
                    load()
                }
            }
            .show()
    }

    private fun confirmRestore() {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { LedgerRepository.overrideCount() }
            if (n <= 0) {
                toast(getString(R.string.manage_nothing_to_restore))
                return@launch
            }
            MaterialAlertDialogBuilder(this@CategoryManageActivity)
                .setTitle(R.string.manage_restore_title)
                .setMessage(R.string.manage_restore_msg)
                .setNegativeButton(R.string.action_close, null)
                .setPositiveButton(R.string.manage_restore) { _, _ ->
                    lifecycleScope.launch {
                        val restored = withContext(Dispatchers.IO) {
                            LedgerRepository.restoreBuiltinCategories()
                        }
                        LedgerBus.notifyChanged()
                        toast(getString(R.string.manage_restore_done, restored))
                        load()
                    }
                }
                .show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_DIRECTION = "extra_direction"
    }
}
