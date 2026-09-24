package com.local.autoledger.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.autoledger.BuildConfig
import com.local.autoledger.R
import com.local.autoledger.capture.AccessWatchdog
import com.local.autoledger.capture.BillImporter
import com.local.autoledger.capture.CaptureLog
import com.local.autoledger.capture.NotifyLink
import com.local.autoledger.capture.PaymentAccessibilityService
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.Prefs
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.databinding.FragmentSettingsBinding
import com.local.autoledger.databinding.RowFoldBinding
import com.local.autoledger.service.KeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * 我的。
 *
 * 这个页面改过很多轮，每加一个能力就往里加一行，最后变成一屏 12 行长得一模一样的列表，
 * 高频的「记账开关」和不常碰的「隐私说明」混在一起。现在按使用频率重排成五段：
 *
 *   1. 状态卡   —— 记账到底有没有在干活 + 三个关键数字，整页只有一个主按钮；
 *   2. 记账方式 —— 只留最常用的两个入口，保活 / 无障碍 / 省电放行收进「稳定运行」；
 *   3. 常用工具 —— 分类、规则、导入、导出，四宫格平铺；
 *   4. 更多     —— 诊断与系统设置类低频项，默认收起；
 *   5. 清空数据 —— 唯一的破坏性操作，单独一张卡放最后。
 *
 * 功能一个没删，只是把低频的收起来、把高频的顶上去。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val refresh: () -> Unit = { if (_binding != null) render() }

    /** 通知链路当前健康度，render() 算一次，按钮点击时据此决定去授权还是去重连 */
    private var linkState: NotifyLink.State = NotifyLink.State.OFF

    /** 程序化回写开关时用来挡掉回调，避免 render 和用户点击互相触发 */
    private var syncingRows = false

    /** 两个折叠分区，状态在横竖屏切换时由下面两个 KEY 兜住 */
    private lateinit var reliableFold: FoldSection
    private lateinit var moreFold: FoldSection

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    private val createFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) doExport(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        applyMaxContentWidth()

        reliableFold = FoldSection(
            binding.foldReliable, binding.bodyReliable,
            getString(R.string.fold_title_reliable), itemCount = 4
        )
        moreFold = FoldSection(
            binding.foldMore, binding.bodyMore,
            getString(R.string.fold_title_more), itemCount = 4
        )
        reliableFold.restore(savedInstanceState?.getBoolean(KEY_OPEN_RELIABLE))
        moreFold.restore(savedInstanceState?.getBoolean(KEY_OPEN_MORE))

        setupHero()
        setupCaptureRows()
        setupToolTiles()
        setupReliableRows()
        setupMoreRows()
        setupFooter()

        render()
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
        // 每次回到这个页面都顺手请求一次重绑：便宜、幂等，
        // 而且用户打开设置页往往正是因为「好像记不上了」。
        runCatching { NotifyLink.rebind(requireContext()) }
        render()
        // 重绑是异步的，给系统一点时间，再刷新一次结论
        binding.root.postDelayed({ if (_binding != null) render() }, 1200L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::reliableFold.isInitialized) outState.putBoolean(KEY_OPEN_RELIABLE, reliableFold.expanded)
        if (::moreFold.isInitialized) outState.putBoolean(KEY_OPEN_MORE, moreFold.expanded)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------------ 装配

    /**
     * 大屏适配：手机不限宽（content_max_width = 0dp），平板 / 折叠屏展开后
     * 正文收在一条固定宽度里居中，避免一行文字横跨十几英寸。
     */
    private fun applyMaxContentWidth() {
        val max = resources.getDimensionPixelSize(R.dimen.content_max_width)
        if (max <= 0) return
        val lp = binding.content.layoutParams
        lp.width = max
        binding.content.layoutParams = lp
    }

    private fun setupHero() {
        binding.btnGrant.setOnClickListener {
            when (linkState) {
                NotifyLink.State.OFF ->
                    // 开关没开就直接替他打开（会走一遍下面的授权引导），
                    // 而不是把人送到一个他根本看不懂的系统页里
                    if (!Prefs.captureEnabled) {
                        binding.rowCapture.switchView.isChecked = true
                    } else {
                        PermissionsUtil.openNotificationAccessSettings(requireContext())
                    }
                NotifyLink.State.SILENT, NotifyLink.State.DEAD -> relink()
                NotifyLink.State.RUNNING -> Unit
            }
        }
    }

    private fun setupCaptureRows() {
        val ctx = requireContext()
        SettingsRows.switch(
            ctx, binding.rowCapture, R.drawable.ic_settings_notification,
            getString(R.string.row_capture_title), getString(R.string.row_capture_sub)
        )

        binding.rowCapture.switchView.setOnCheckedChangeListener { _, checked ->
            Prefs.captureEnabled = checked
            render()
            if (checked && !PermissionsUtil.hasNotificationAccess(requireContext())) {
                promptGrant()
            }
        }
    }

    private fun setupToolTiles() {
        val ctx = requireContext()
        SettingsRows.tile(
            ctx, binding.tileCategories, R.drawable.ic_grid,
            getString(R.string.tile_categories), getString(R.string.tile_categories_sub, 0)
        )
        SettingsRows.tile(
            ctx, binding.tileRules, R.drawable.ic_settings_rules,
            getString(R.string.tile_rules), getString(R.string.tile_rules_sub, 0)
        )
        SettingsRows.tile(
            ctx, binding.tileImport, R.drawable.ic_settings_import,
            getString(R.string.tile_import), getString(R.string.tile_import_sub)
        )
        SettingsRows.tile(
            ctx, binding.tileExport, R.drawable.ic_settings_export,
            getString(R.string.tile_export), getString(R.string.tile_export_sub)
        )

        binding.tileCategories.root.setOnClickListener {
            startActivity(Intent(ctx, CategoryManageActivity::class.java))
        }
        binding.tileRules.root.setOnClickListener {
            startActivity(Intent(ctx, RulesActivity::class.java))
        }
        binding.tileImport.root.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        binding.tileExport.root.setOnClickListener {
            createFile.launch("账本导出_${LocalDate.now()}.csv")
        }
    }

    /** 折叠区里的四行：平时用不上，但一旦漏记就得靠它们排查 */
    private fun setupReliableRows() {
        val ctx = requireContext()
        SettingsRows.switch(
            ctx, binding.rowKeepAlive, R.drawable.ic_settings_stats,
            getString(R.string.keepalive_title), getString(R.string.keepalive_sub)
        )
        SettingsRows.switch(
            ctx, binding.rowAccess, R.drawable.ic_settings_access,
            getString(R.string.access_title), getString(R.string.access_sub)
        )
        SettingsRows.action(
            ctx, binding.rowBattery, R.drawable.ic_settings_battery,
            getString(R.string.battery_title), getString(R.string.battery_sub)
        )
        SettingsRows.action(
            ctx, binding.rowRelink, R.drawable.ic_settings_gear,
            getString(R.string.relink_title), ""
        )

        binding.rowKeepAlive.switchView.setOnCheckedChangeListener { _, checked ->
            Prefs.keepAlive = checked
            if (checked) KeepAliveService.start(ctx) else KeepAliveService.stop(ctx)
            render()
            toast(getString(if (checked) R.string.keepalive_on else R.string.keepalive_off))
        }
        binding.rowAccess.switchView.setOnCheckedChangeListener { _, checked ->
            if (syncingRows) return@setOnCheckedChangeListener
            if (checked && !PermissionsUtil.hasAccessibility(ctx)) {
                // 系统授权是用户亲手开的东西，这里只能把他送过去，不能替他开
                syncingRows = true
                binding.rowAccess.switchView.isChecked = false
                syncingRows = false
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("开启辅助记账")
                    .setMessage(
                        "需要在系统「无障碍」里打开「随手记 · 辅助记账」。\n\n" +
                            "它只在微信 / 支付宝的支付成功页读取金额，用来补上没弹通知的那一笔；" +
                            "不读聊天内容、不读密码框，也不联网。"
                    )
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去开启") { _, _ ->
                        PermissionsUtil.openAccessibilitySettings(ctx)
                    }
                    .show()
                return@setOnCheckedChangeListener
            }
            Prefs.accessEnabled = checked
            render()
            toast(getString(if (checked) R.string.access_on else R.string.access_off))
        }
        binding.rowBattery.root.setOnClickListener {
            PermissionsUtil.requestBatteryUnrestricted(ctx)
        }
        binding.rowRelink.root.setOnClickListener { relink() }
    }

    private fun setupMoreRows() {
        val ctx = requireContext()
        SettingsRows.action(
            ctx, binding.rowStats, R.drawable.ic_settings_stats,
            getString(R.string.row_stats_title), getString(R.string.row_stats_sub)
        )
        SettingsRows.action(
            ctx, binding.rowLog, R.drawable.ic_settings_log,
            getString(R.string.row_log_title), getString(R.string.row_log_sub)
        )
        SettingsRows.action(
            ctx, binding.rowNotifyAccess, R.drawable.ic_settings_gear,
            getString(R.string.row_notify_access_title), getString(R.string.row_notify_access_sub)
        )
        SettingsRows.action(
            ctx, binding.rowPrivacy, R.drawable.ic_settings_privacy,
            getString(R.string.settings_privacy), getString(R.string.row_privacy_sub)
        )

        binding.rowStats.root.setOnClickListener { showStats() }
        binding.rowLog.root.setOnClickListener {
            startActivity(Intent(ctx, LogActivity::class.java))
        }
        binding.rowNotifyAccess.root.setOnClickListener {
            PermissionsUtil.openNotificationAccessSettings(ctx)
        }
        binding.rowPrivacy.root.setOnClickListener { showPrivacy() }
    }

    private fun setupFooter() {
        SettingsRows.action(
            requireContext(), binding.rowClear, R.drawable.ic_settings_delete,
            getString(R.string.row_clear_title), getString(R.string.row_clear_sub), danger = true
        )
        binding.rowClear.root.setOnClickListener { confirmClear() }
        binding.tvVersion.text = getString(R.string.mine_version, BuildConfig.VERSION_NAME)
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        if (_binding == null) return
        renderStatusCard()
        renderRowState()
        loadData()
    }

    private fun renderStatusCard() {
        val ctx = requireContext()
        val granted = PermissionsUtil.hasNotificationAccess(ctx)
        val capture = Prefs.captureEnabled

        // 「授权开着」不等于「正在干活」。系统会静默断链：权限页显示已开启，
        // 但一条通知都送不过来。所以状态卡的结论交给 NotifyLink 的活性判定。
        NotifyLink.seed()
        val state = if (!granted || !capture) {
            NotifyLink.State.OFF
        } else {
            NotifyLink.health(
                now = System.currentTimeMillis(),
                connected = NotifyLink.connected,
                lastNotifyAt = NotifyLink.lastNotifyAt,
                captureEnabled = capture
            )
        }
        linkState = state
        val detail = NotifyLink.describe(state)

        when (state) {
            NotifyLink.State.OFF -> {
                binding.tvStatusTitle.text = "记账未开启"
                binding.tvStatusPill.text = "未开启"
                binding.tvStatusDesc.text = if (!capture) {
                    "记账开关已关闭。打开后，微信和支付宝的付款会自动出现在明细里。"
                } else {
                    "还差一步：给本应用「通知使用权」，之后微信 / 支付宝的付款通知会被自动记成一笔账。"
                }
                binding.btnGrant.isVisible = true
                binding.btnGrant.text = if (!capture) "开启自动记账" else "去开启通知使用权"
            }
            NotifyLink.State.RUNNING -> {
                binding.tvStatusTitle.text = "记账运行中"
                binding.tvStatusPill.text = "已开启"
                binding.tvStatusDesc.text =
                    "微信 / 支付宝付款时会自动记账。" + if (detail != null) "\n$detail" else ""
                binding.btnGrant.isVisible = false
            }
            NotifyLink.State.SILENT, NotifyLink.State.DEAD -> {
                binding.tvStatusTitle.text =
                    if (state == NotifyLink.State.DEAD) "通知链接已断开" else "记账可能已中断"
                binding.tvStatusPill.text = if (state == NotifyLink.State.DEAD) "已断开" else "待检查"
                binding.tvStatusDesc.text =
                    "${detail ?: "监听链接可能已经断开"}。系统的通知监听会在后台静默掉线，" +
                        "点一下就能重新接上，不用重启手机。"
                binding.btnGrant.isVisible = true
                binding.btnGrant.text = "重新连接"
            }
        }
    }

    private fun renderRowState() {
        val ctx = requireContext()
        binding.rowCapture.switchView.isChecked = Prefs.captureEnabled
        binding.rowKeepAlive.switchView.isChecked = Prefs.keepAlive
        syncingRows = true
        binding.rowAccess.switchView.isChecked =
            Prefs.accessEnabled && PermissionsUtil.hasAccessibility(ctx)
        syncingRows = false

        // 「系统里开着」和「服务此刻真在跑」必须分开说：
        // force-stop 会把无障碍授权摘掉，也可能只杀进程、授权还在，两种情况的处置完全不同。
        binding.rowAccess.tvSub.text = AccessWatchdog.describe(
            AccessWatchdog.health(
                now = System.currentTimeMillis(),
                granted = PermissionsUtil.hasAccessibility(ctx),
                running = PaymentAccessibilityService.running,
                lastConnectedAt = Prefs.lastAccessAt
            )
        ) ?: getString(R.string.access_sub)

        binding.rowRelink.tvSub.text = when (linkState) {
            NotifyLink.State.RUNNING -> NotifyLink.describe(linkState) ?: "监听正常"
            NotifyLink.State.SILENT -> NotifyLink.describe(linkState) ?: "长时间没收到通知"
            NotifyLink.State.DEAD -> NotifyLink.describe(linkState) ?: "监听未连接"
            NotifyLink.State.OFF -> "记账开关或通知使用权未开启"
        }
        binding.rowBattery.tvValue.text = when {
            !PermissionsUtil.isBatteryUnrestricted(ctx) -> getString(R.string.battery_todo)
            // 电池优化放行了、但系统还压着后台活动：这正是「设成无限制还是失效」的典型状态
            PermissionsUtil.isBackgroundRestricted(ctx) -> getString(R.string.battery_bg_restricted)
            else -> getString(R.string.battery_ok)
        }

        // 折叠区的摘要让用户不展开也知道里面开了几个
        val on = listOf(Prefs.keepAlive, Prefs.accessEnabled).count { it }
        reliableFold.summary(
            if (on > 0) getString(R.string.fold_items_on, on)
            else getString(R.string.fold_collapsed, 4)
        )
        moreFold.summary("")
    }

    /** 所有要从库里数出来的数字一次算完，避免开一堆协程来回抖 */
    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                Snapshot(
                    rules = LedgerRepository.rules().size,
                    logs = CaptureLog.count(),
                    categories = Categories.of(Direction.EXPENSE).size +
                        Categories.of(Direction.INCOME).size,
                    total = LedgerRepository.count(),
                    pending = LedgerRepository.pendingCount(),
                    monthExpenseCents = LedgerRepository.monthSummary(YearMonth.now()).expenseCents
                )
            }
            if (_binding == null) return@launch
            binding.tileRules.tvSub.text = getString(R.string.tile_rules_sub, data.rules)
            binding.tileCategories.tvSub.text =
                getString(R.string.tile_categories_sub, data.categories)
            binding.rowLog.tvValue.text =
                if (data.logs > 0) getString(R.string.mine_count_records, data.logs) else ""

            binding.tvMetricTotal.text = getString(R.string.mine_count_records, data.total)
            binding.tvMetricMonth.text = Money.yuan(data.monthExpenseCents)
            binding.tvMetricPending.text = getString(R.string.mine_count_records, data.pending)
            // 待分类为 0 时数字本身就说明了问题，不用再拿颜色强调
            binding.tvMetricPending.alpha = if (data.pending > 0) 1f else 0.45f
        }
    }

    /** 手动重新连接通知监听 */
    private fun relink() {
        NotifyLink.reconnect(requireContext())
        toast(getString(R.string.relink_done))
        binding.root.postDelayed({ if (_binding != null) render() }, 1500L)
    }

    private fun promptGrant() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("开启随手记")
            .setMessage(
                "请在接下来的系统页面里找到「随手记」，打开它的开关。\n\n" +
                    "开启后，微信和支付宝的付款通知会被读取并自动记成一笔账。"
            )
            .setNegativeButton("稍后", null)
            .setPositiveButton("去开启") { _, _ ->
                PermissionsUtil.openNotificationAccessSettings(requireContext())
            }
            .show()
    }

    // ------------------------------------------------------------------ 功能

    private fun doImport(uri: Uri) {
        val ctx = requireContext().applicationContext
        toast("正在解析账单…")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { BillImporter.import(ctx, uri) }
            LedgerBus.notifyChanged()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (result.error == null) "导入完成" else "导入失败")
                .setMessage(
                    if (result.error != null) {
                        result.error
                    } else {
                        "来源：${result.source}\n新增 ${result.added} 笔，跳过重复 ${result.skipped} 笔（共识别 ${result.total} 条）"
                    }
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun doExport(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        out.write(LedgerRepository.exportCsv().toByteArray(Charsets.UTF_8))
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            toast(if (ok) "导出成功" else "导出失败，请换个目录再试")
        }
    }

    private fun showStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                val all = LedgerRepository.recent(1)
                val total = LedgerRepository.count()
                val pending = LedgerRepository.pendingCount()
                val size = requireContext().getDatabasePath("ledger.db")
                    .takeIf { it.exists() }?.length() ?: 0L
                val oldest = LedgerRepository.oldestOccurredAt()
                arrayOf(
                    total.toString(),
                    pending.toString(),
                    oldest?.let { TimeFmt.detail(it) } ?: "—",
                    all.firstOrNull()?.let { TimeFmt.detail(it.occurredAt) } ?: "—",
                    size.toString()
                )
            }
            if (_binding == null) return@launch
            val text = buildString {
                append("累计记录：").append(info[0]).append(" 笔\n")
                append("待分类：").append(info[1]).append(" 笔\n")
                append("最早记录：").append(info[2]).append("\n")
                append("最近记录：").append(info[3]).append("\n")
                append("占用空间：")
                    .append(String.format(java.util.Locale.US, "%.1f", info[4].toLong() / 1024.0))
                    .append(" KB")
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("数据概览")
                .setMessage(text)
                .setPositiveButton("好", null)
                .show()
        }
    }

    private fun showPrivacy() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("隐私说明")
            .setMessage(
                "本应用没有申请网络权限，所有账目都只保存在本机的 SQLite 数据库里。\n\n" +
                    "· 支付通知只用来提取金额、商户和支付方式；\n" +
                    "· 辅助记账（无障碍）默认关闭，开启后也只读取微信 / 支付宝支付成功页上的金额，" +
                    "不读聊天内容、不读密码输入框；\n" +
                    "· 没有任何数据会被上传到服务器；\n" +
                    "· 卸载应用，本机数据即被彻底删除。\n\n" +
                    "如果需要换机或备份，请用「导出备份」生成 CSV 文件自行保管。"
            )
            .setPositiveButton("明白了", null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清空全部数据？")
            .setMessage("本机的所有账目都会被删除，此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清空") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { LedgerRepository.deleteAll() }
                    LedgerBus.notifyChanged()
                    render()
                    toast("已清空")
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        if (_binding != null) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    /** loadData 一次取回的所有数字 */
    private class Snapshot(
        val rules: Int,
        val logs: Int,
        val categories: Int,
        val total: Int,
        val pending: Int,
        val monthExpenseCents: Long
    )

    /**
     * 一个可折叠分区：表头一行 + 内容若干行。
     *
     * 展开态用箭头旋转表达（0° 朝下、180° 朝上），内容整体 VISIBLE / GONE。
     * 不换成 Dialog或二级页面，是因为这些项要能「一眼看到当前开着几个」，
     * 就地展开比跳转更省事。
     */
    private class FoldSection(
        private val header: RowFoldBinding,
        private val body: LinearLayout,
        label: String,
        private val itemCount: Int
    ) {
        var expanded: Boolean = false
            private set

        init {
            header.tvFoldTitle.text = label
            header.root.setOnClickListener { setExpanded(!expanded) }
            setExpanded(false, animate = false)
        }

        fun setExpanded(value: Boolean, animate: Boolean = true) {
            expanded = value
            val arrow = header.ivFoldArrow
            val angle = if (value) 180f else 0f
            if (animate) {
                arrow.animate().rotation(angle).setDuration(180L).start()
            } else {
                arrow.rotation = angle
            }
            body.isVisible = value
            header.root.contentDescription = if (value) {
                header.root.context.getString(R.string.cd_collapse_section, header.tvFoldTitle.text)
            } else {
                header.root.context.getString(
                    R.string.cd_expand_section, header.tvFoldTitle.text, itemCount
                )
            }
        }

        /** 右侧灰色摘要，不展开也能看出里面的状态 */
        fun summary(text: String) {
            header.tvFoldValue.text = text
        }

        fun restore(saved: Boolean?) = setExpanded(saved == true, animate = false)
    }

    private companion object {
        const val KEY_OPEN_RELIABLE = "fold_reliable_open"
        const val KEY_OPEN_MORE = "fold_more_open"
    }
}
