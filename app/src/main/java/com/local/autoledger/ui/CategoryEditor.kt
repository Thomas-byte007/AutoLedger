package com.local.autoledger.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Category
import com.local.autoledger.data.CategoryGroup
import com.local.autoledger.data.CategoryIconCatalog
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.databinding.DialogAddCategoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分类编辑器弹窗：新建 / 改名 / 换图标的 UI 只有这一份。
 *
 * 「记一笔」里的分类选择页和「我的 → 分类管理」都调它：
 *  - 新建二级类：[target] 传 null；
 *  - 改二级类：传要改的那个（内置分类也能改，落库写的是补丁）；
 *  - 改一级类：走 [showGroup]，少掉「归属一级类」那一段。
 */
object CategoryEditor {

    /**
     * 在指定一级类下直接新建子类（管理页每个分组底部的「+ 子类」）。
     * [groupKey] 只用于预选中人数所需的一级类，仍然可以在弹窗里改。
     */
    fun showWithGroup(
        activity: AppCompatActivity,
        direction: Direction,
        groupKey: String,
        onDone: (Category) -> Unit = {}
    ) {
        pendingGroupKey = groupKey
        show(activity, direction, null, onDone)
    }

    /** 本次弹窗预选的一级类；用完即清，避免串到下一次 */
    @Volatile
    private var pendingGroupKey: String? = null

    fun show(
        activity: AppCompatActivity,
        direction: Direction,
        target: Category?,
        onDone: (Category) -> Unit = {}
    ) {
        val binding = DialogAddCategoryBinding.inflate(activity.layoutInflater)
        val groups = Categories.groupsOf(direction)
        if (groups.isEmpty()) return

        binding.etName.setText(target?.label.orEmpty())
        binding.etName.setSelection(binding.etName.length())

        val preferred = pendingGroupKey.also { pendingGroupKey = null }
        var group: CategoryGroup = groups.firstOrNull { it.key == preferred }
            ?: groups.firstOrNull { it.key == target?.group }
            ?: groups.first()

        // 新建时默认给该一级类下第一个二级类的图标，用户大概率不用再挑
        val defaultIcon = Categories.of(direction).firstOrNull { it.group == group.key }?.iconName
            ?: CategoryIconCatalog.LEAF_FALLBACK

        val picker = Picker(
            activity, binding,
            choices = Categories.ICON_CHOICES,
            isGroup = false,
            initialIcon = target?.iconName?.takeIf { it.isNotBlank() } ?: defaultIcon,
            initialColor = target?.colorRes?.let { Categories.colorNameOf(it) }
                ?: Categories.PALETTE.first().first
        )
        wireGroupChips(activity, binding, groups, group) { picked -> group = picked }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (target == null) R.string.picker_add_title else R.string.editor_edit_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(
                if (target == null) R.string.picker_add_ok else R.string.action_save, null
            )
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = binding.etName.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                toast(activity, activity.getString(R.string.picker_add_empty_name))
                return@setOnClickListener
            }
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (target == null) {
                        LedgerRepository.addCustomCategory(
                            name, picker.icon, picker.color, group.key, direction
                        )
                    } else {
                        LedgerRepository.updateCategory(
                            target.key, direction, name, picker.icon, picker.color, group.key
                        )
                    }
                }
                if (result == null) {
                    toast(activity, activity.getString(R.string.editor_duplicate_or_invalid))
                    return@launch
                }
                dialog.dismiss()
                LedgerBus.notifyChanged()
                toast(activity, activity.getString(R.string.editor_done, result.label))
                onDone(result)
            }
        }
    }

    /** 改一级类（名字 / 图标 / 颜色），不能改它下面挂了哪些二级类 */
    fun showGroup(
        activity: AppCompatActivity,
        target: CategoryGroup,
        onDone: (CategoryGroup) -> Unit = {}
    ) {
        val binding = DialogAddCategoryBinding.inflate(activity.layoutInflater)
        binding.tvGroupTitle.visibility = View.GONE
        binding.chipGroups.visibility = View.GONE

        binding.etName.setText(target.label)
        binding.etName.setSelection(binding.etName.length())

        val picker = Picker(
            activity, binding,
            choices = CategoryIconCatalog.GROUP_NAMES,
            isGroup = true,
            initialIcon = target.iconName,
            initialColor = Categories.colorNameOf(target.colorRes)
        )

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.editor_edit_group_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = binding.etName.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                toast(activity, activity.getString(R.string.picker_add_empty_name))
                return@setOnClickListener
            }
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    LedgerRepository.updateCategoryGroup(
                        target.key, name, picker.icon, picker.color
                    )
                }
                if (result == null) {
                    toast(activity, activity.getString(R.string.editor_duplicate_or_invalid))
                    return@launch
                }
                dialog.dismiss()
                LedgerBus.notifyChanged()
                toast(activity, activity.getString(R.string.editor_done, result.label))
                onDone(result)
            }
        }
    }

    // ------------------------------------------------------------------ 组件

    private fun wireGroupChips(
        activity: AppCompatActivity,
        binding: DialogAddCategoryBinding,
        groups: List<CategoryGroup>,
        current: CategoryGroup,
        onPicked: (CategoryGroup) -> Unit
    ) {
        val chipBg = AppCompatResources.getColorStateList(activity, R.color.selector_chip_bg)
        val chipFg = AppCompatResources.getColorStateList(activity, R.color.selector_chip_text)
        binding.chipGroups.removeAllViews()
        groups.forEach { g ->
            binding.chipGroups.addView(
                Chip(activity).apply {
                    id = View.generateViewId()
                    text = g.label
                    isCheckable = true
                    isChecked = g.key == current.key
                    isCheckedIconVisible = false
                    chipStrokeWidth = 0f
                    chipBackgroundColor = chipBg
                    setTextColor(chipFg)
                    setOnClickListener { onPicked(g) }
                }
            )
        }
    }

    /** 图标网格 + 颜色网格 + 左上角预览，自己持有选中状态（每个对话框一份，不会串） */
    private class Picker(
        private val activity: AppCompatActivity,
        private val binding: DialogAddCategoryBinding,
        private val choices: List<String>,
        private val isGroup: Boolean,
        initialIcon: String,
        initialColor: String
    ) {
        var icon: String = initialIcon
            private set
        var color: String = initialColor
            private set

        private val iconCells = HashMap<String, View>()
        private val iconViews = HashMap<String, ImageView>()
        private val colorRings = HashMap<String, View>()

        init {
            buildIcons()
            buildColors()
            paint()
        }

        private fun buildIcons() {
            binding.iconGrid.removeAllViews()
            choices.forEach { name ->
                val iv = ImageView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setImageResource(CategoryIconCatalog.resOf(name, isGroup))
                }
                val cell = FrameLayout(activity).apply {
                    background = AppCompatResources.getDrawable(activity, R.drawable.shape_circle)
                    layoutParams = cellParams(activity, dp(36))
                    setOnClickListener { pick(name, color) }
                    addView(iv)
                }
                iconCells[name] = cell
                iconViews[name] = iv
                binding.iconGrid.addView(cell)
            }
        }

        private fun buildColors() {
            binding.colorGrid.removeAllViews()
            Categories.PALETTE.forEach { (name, res) ->
                val dot = View(activity).apply {
                    background = AppCompatResources.getDrawable(activity, R.drawable.shape_circle)
                    backgroundTintList = ColorStateList.valueOf(ThemeUtil.color(activity, res))
                    layoutParams = cellParams(activity, dp(30))
                }
                val ring = View(activity).apply {
                    background = AppCompatResources.getDrawable(activity, R.drawable.bg_select_ring)
                    layoutParams = cellParams(activity, dp(34))
                    isClickable = false
                }
                colorRings[name] = ring
                FrameLayout(activity).apply {
                    layoutParams = cellParams(activity, dp(38))
                    setOnClickListener { pick(icon, name) }
                    addView(dot)
                    addView(ring)
                }.also { binding.colorGrid.addView(it) }
            }
        }

        private fun pick(iconName: String, colorName: String) {
            icon = iconName
            color = colorName
            paint()
        }

        private fun paint() {
            val base = ThemeUtil.color(activity, Categories.colorResOf(color))
            val strong = ColorStateList.valueOf(ThemeUtil.softStrong(activity, base))
            val fg = ColorStateList.valueOf(base)
            val none = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            iconCells.forEach { (k, v) -> v.backgroundTintList = if (k == icon) strong else none }
            iconViews.forEach { (_, v) -> v.imageTintList = fg }
            colorRings.forEach { (k, v) -> v.visibility = if (k == color) View.VISIBLE else View.INVISIBLE }
            CategoryIcons.bind(
                binding.previewBg,
                binding.previewIcon,
                CategoryIconCatalog.resOf(icon, isGroup),
                Categories.colorResOf(color),
                strong = true
            )
        }

        private fun cellParams(activity: AppCompatActivity, size: Int): GridLayout.LayoutParams =
            GridLayout.LayoutParams().apply {
                width = size
                height = size
                setGravity(android.view.Gravity.CENTER)
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(2)
                bottomMargin = dp(2)
            }

        private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    }

    private fun toast(activity: AppCompatActivity, msg: String) =
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
}
