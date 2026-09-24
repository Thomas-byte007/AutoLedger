package com.local.autoledger.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Category
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.databinding.ItemCatPickBinding
import com.local.autoledger.databinding.SheetCategoryPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 类别选择面板（BottomSheet）。
 *
 * 记一笔 / 编辑页点「分类」行时从当前页底部滑出，不再跳独立页面：
 * - 先列「最近使用」（空的时候按「对方」给一个推荐）；
 * - 再按一级类分段铺开它下面的二级类；
 * - 顶部可搜索，长按一个分类可以改名 / 删除；
 * - 底部常驻「添加分类」。
 *
 * 选中后通过 [onPicked] 回传，由调用方决定怎么用。
 */
class CategoryPickerSheet(
    private val activity: AppCompatActivity,
    private val direction: Direction,
    private var selectedKey: String?,
    private val merchant: String,
    private val onPicked: (Category) -> Unit
) {

    private lateinit var binding: SheetCategoryPickerBinding
    private val dialog: BottomSheetDialog = BottomSheetDialog(activity)

    /** 最近用过的分类。只在 IO 线程查一次，渲染时直接读缓存 */
    private var recent: List<Category> = emptyList()

    /** 按「对方」猜出来的分类，最近使用为空时顶上当推荐位 */
    private var suggested: Category? = null

    fun show() {
        binding = SheetCategoryPickerBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        binding.btnAddCategory.setOnClickListener { showAddDialog() }
        binding.btnCollapse.setOnClickListener { dialog.dismiss() }
        binding.etSearch.doAfterTextChanged { render(binding.etSearch.text?.toString().orEmpty()) }

        // 滑动交互固定：面板本体高度直接锁死在屏幕六成（= peek 高度），
        // collapsed 和 expanded 是同一个位置 —— 上滑无法再展开成整页。
        // isDraggable = false 禁掉面板本体拖拽和下滑关闭（收起走右上角箭头 / 点遮罩 / 返回键）；
        // 内部 ScrollView 的嵌套滚动因 expanded == collapsed 也推不动面板。
        // 注意不能只设 isDraggable：material 1.12.0 里嵌套滚动快速 fling 的展开分支不受它管
        dialog.behavior.isDraggable = false
        val sheetHeight = (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
        binding.root.layoutParams?.height = sheetHeight
        dialog.behavior.peekHeight = sheetHeight

        loadContextAndRender()
        dialog.show()
    }

    /**
     * 「最近使用」和「推荐」都要碰数据库，统一在 IO 线程捞完再回主线程渲染 ——
     * 分类面板是按下就要弹出来的，主线程不能有磁盘 IO。
     */
    private fun loadContextAndRender() {
        val dir = direction
        val name = merchant
        val scope = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope ?: return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val recentList = LedgerRepository.recentCategories(dir)
                val guess = if (recentList.isEmpty() && name.isNotBlank()) {
                    val match = LedgerRepository.classify(dir, name, "")
                    if (match.auto) Categories.byKeyOrNull(match.categoryKey, dir) else null
                } else {
                    null
                }
                recentList to guess
            }
            recent = loaded.first
            suggested = loaded.second
            // 面板可能还没 attach 到窗口，用 post 保证渲染一定执行
            binding.root.post { render(binding.etSearch.text?.toString().orEmpty()) }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render(keyword: String) {
        val query = keyword.trim()
        val container = binding.sectionContainer
        container.removeAllViews()

        val all = Categories.of(direction)
        val visible = if (query.isEmpty()) {
            all
        } else {
            all.filter {
                it.label.contains(query, ignoreCase = true) ||
                    Categories.groupOf(it.key, direction).label.contains(query, ignoreCase = true)
            }
        }

        if (query.isEmpty()) {
            if (recent.isNotEmpty()) {
                addSection(container, activity.getString(R.string.picker_recent), recent)
            } else {
                suggested?.let { addSection(container, "推荐", listOf(it)) }
            }
        }

        if (visible.isEmpty()) {
            val empty = TextView(activity).apply {
                text = "没有找到「$query」相关的分类，可以点下面的「添加分类」自己建一个。"
                setTextColor(ThemeUtil.color(activity, R.color.text_secondary))
                textSize = 13f
                setPadding(dp(4), dp(24), dp(4), dp(24))
            }
            container.addView(empty)
            return
        }

        Categories.groupsOf(direction).forEach { group ->
            val children = visible.filter { it.group == group.key }
            if (children.isNotEmpty()) addSection(container, group.label, children)
        }
    }

    private fun addSection(parent: LinearLayout, title: String, items: List<Category>) {
        val header = TextView(activity).apply {
            text = title
            setTextColor(ThemeUtil.color(activity, R.color.text_secondary))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(14), dp(4), dp(6))
        }
        parent.addView(header)

        val grid = GridLayout(activity).apply {
            columnCount = COLUMNS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        items.forEach { grid.addView(buildCell(grid, it)) }
        parent.addView(grid)
    }

    private fun buildCell(grid: GridLayout, category: Category): View {
        val item = ItemCatPickBinding.inflate(LayoutInflater.from(activity), grid, false)
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
        }
        item.root.layoutParams = lp

        val selected = category.key == selectedKey
        CategoryIcons.bind(item.iconBg, item.ivIcon, category, strong = selected)
        item.tvName.text = category.label
        item.tvName.setTextColor(
            ThemeUtil.color(activity, if (selected) R.color.brand_primary else R.color.text_secondary)
        )
        item.tvName.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        item.root.setOnClickListener { select(category) }
        // 长按 = 编辑 / 删除（完整的管理能力在「我的 → 分类管理」里）
        item.root.setOnLongClickListener {
            showQuickActions(category)
            true
        }
        return item.root
    }

    // ------------------------------------------------------------------ 交互

    private fun select(category: Category) {
        dialog.dismiss()
        onPicked(category)
    }

    /** 长按一个分类：改名 / 删除。内置分类也能改能删 */
    private fun showQuickActions(category: Category) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(category.label)
            .setItems(
                arrayOf(
                    activity.getString(R.string.editor_action_edit),
                    activity.getString(R.string.editor_action_delete)
                )
            ) { _, which ->
                if (which == 0) {
                    CategoryEditor.show(activity, direction, category) {
                        loadContextAndRender()
                    }
                } else {
                    confirmDelete(category)
                }
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun confirmDelete(category: Category) {
        val scope = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope ?: return
        scope.launch {
            val under = withContext(Dispatchers.IO) {
                LedgerRepository.countOf(category.key)
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.picker_delete_title, category.label))
                .setMessage(
                    if (under > 0) {
                        "这个分类下有 $under 笔账目，删除后它们会改挂到「${
                            Categories.fallbackOf(direction).label
                        }」，记录不会丢。"
                    } else {
                        activity.getString(R.string.picker_delete_msg)
                    }
                )
                .setNegativeButton(R.string.action_close, null)
                .setPositiveButton("删除") { _, _ ->
                    scope.launch {
                        val moved = withContext(Dispatchers.IO) {
                            LedgerRepository.deleteCategory(category.key, direction)
                        }
                        if (selectedKey == category.key) selectedKey = null
                        LedgerBus.notifyChanged()
                        if (moved > 0) {
                            android.widget.Toast.makeText(
                                activity,
                                "已删除，$moved 笔已改挂到「${Categories.fallbackOf(direction).label}」",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        loadContextAndRender()
                    }
                }
                .show()
        }
    }

    /** 新建分类统一走 [CategoryEditor]，改名也是同一个弹窗 */
    private fun showAddDialog() {
        CategoryEditor.show(activity, direction, null) {
            selectedKey = it.key
            loadContextAndRender()
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val COLUMNS = 5
    }
}
