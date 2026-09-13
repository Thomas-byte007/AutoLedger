package com.local.autoledger.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.autoledger.BuildConfig
import com.local.autoledger.R
import com.local.autoledger.capture.BillImporter
import com.local.autoledger.capture.CaptureLog
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.Prefs
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 我的：授权状态、分类规则、导入导出、隐私 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val refresh: () -> Unit = { render() }

    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = result[Manifest.permission.RECEIVE_SMS] == true
            Prefs.smsEnabled = ok
            binding.rowSms.switchView.isChecked = ok
            render()
            if (!ok) toast("没有短信权限，就无法从银行卡短信记账")
        }

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
        binding.btnGrant.setOnClickListener {
            PermissionsUtil.openNotificationAccessSettings(requireContext())
        }

        binding.rowCapture.tvIcon.text = "🔔"
        binding.rowCapture.tvTitle.text = "读取支付通知"
        binding.rowCapture.tvSub.text = "从微信 / 支付宝的付款通知自动记账"
        binding.rowSms.tvIcon.text = "✉️"
        binding.rowSms.tvTitle.text = "读取银行短信"
        binding.rowSms.tvSub.text = "银行卡消费、入账短信也自动记一笔"

        binding.rowRules.tvIcon.text = "🏷"
        binding.rowRules.tvTitle.text = "分类规则"
        binding.rowRules.tvSub.text = "调整关键词，让自动分类更准"
        binding.rowImport.tvIcon.text = "📥"
        binding.rowImport.tvTitle.text = "导入微信 / 支付宝账单"
        binding.rowImport.tvSub.text = "支持官方导出的 CSV，历史账目一次补齐"
        binding.rowExport.tvIcon.text = "📤"
        binding.rowExport.tvTitle.text = "导出账本数据"
        binding.rowExport.tvSub.text = "导出为 CSV 自行备份"
        binding.rowStats.tvIcon.text = "📊"
        binding.rowStats.tvTitle.text = "数据概览"
        binding.rowStats.tvSub.text = "记录条数、时间范围与占用空间"

        binding.rowNotifyAccess.tvIcon.text = "⚙️"
        binding.rowNotifyAccess.tvTitle.text = "通知使用权设置"
        binding.rowNotifyAccess.tvSub.text = "在系统设置里手动开关"
        binding.rowLog.tvIcon.text = "🧾"
        binding.rowLog.tvTitle.text = "采集日志"
        binding.rowLog.tvSub.text = "看看每条付款通知有没有被识别"
        binding.rowPrivacy.tvIcon.text = "🔒"
        binding.rowPrivacy.tvTitle.text = "隐私说明"
        binding.rowPrivacy.tvSub.text = "不联网，数据只在本机"
        binding.rowClear.tvIcon.text = "🗑"
        binding.rowClear.tvTitle.text = "清空全部数据"
        binding.rowClear.tvSub.text = "删除本机所有账目，不可恢复"

        binding.rowCapture.switchView.setOnCheckedChangeListener { _, checked ->
            Prefs.captureEnabled = checked
            render()
            if (checked && !PermissionsUtil.hasNotificationAccess(requireContext())) {
                promptGrant()
            }
        }

        binding.rowSms.switchView.setOnCheckedChangeListener { _, checked ->
            if (checked && !PermissionsUtil.hasSmsPermission(requireContext())) {
                smsPermission.launch(
                    arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                )
            } else {
                Prefs.smsEnabled = checked
            }
        }

        binding.rowRules.root.setOnClickListener {
            startActivity(Intent(requireContext(), RulesActivity::class.java))
        }
        binding.rowImport.root.setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        binding.rowExport.root.setOnClickListener {
            createFile.launch("账本导出_${LocalDate.now()}.csv")
        }
        binding.rowStats.root.setOnClickListener { showStats() }
        binding.rowLog.root.setOnClickListener {
            startActivity(Intent(requireContext(), LogActivity::class.java))
        }
        binding.rowNotifyAccess.root.setOnClickListener {
            PermissionsUtil.openNotificationAccessSettings(requireContext())
        }
        binding.rowPrivacy.root.setOnClickListener { showPrivacy() }
        binding.rowClear.root.setOnClickListener { confirmClear() }

        binding.tvVersion.text = "自动记账 v${BuildConfig.VERSION_NAME}\n数据全部保存在本机，不联网"
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
        render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val ctx = requireContext()
        val granted = PermissionsUtil.hasNotificationAccess(ctx)
        val capture = Prefs.captureEnabled

        binding.tvStatusTitle.text = if (granted && capture) "自动记账运行中" else "自动记账未开启"
        binding.tvStatusPill.text = if (granted && capture) "已开启" else "未开启"
        binding.tvStatusDesc.text = when {
            !capture -> "自动记账开关已关闭。打开后，微信和支付宝的付款会自动出现在明细里。"
            !granted -> "还差一步：给本应用「通知使用权」，之后微信 / 支付宝的付款通知会被自动记成一笔账。"
            else -> "微信 / 支付宝付款时会自动记账，记账后你会收到一条轻提示。"
        }
        binding.btnGrant.isVisible = !(granted && capture)
        binding.btnGrant.text = if (!granted) "去开启通知使用权" else "去开启自动记账"

        binding.rowCapture.switchView.isChecked = capture
        binding.rowSms.switchView.isChecked = Prefs.smsEnabled

        lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                LedgerRepository.rules().size to CaptureLog.count()
            }
            if (_binding != null) {
                binding.rowRules.tvValue.text = "${counts.first} 条"
                binding.rowLog.tvValue.text = if (counts.second > 0) "${counts.second} 条" else ""
            }
        }
    }

    private fun promptGrant() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("开启自动记账")
            .setMessage(
                "请在接下来的系统页面里找到「自动记账」，打开它的开关。\n\n" +
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
        lifecycleScope.launch {
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
        lifecycleScope.launch {
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
        lifecycleScope.launch {
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
                append("占用空间：").append(String.format(java.util.Locale.US, "%.1f", info[4].toLong() / 1024.0)).append(" KB")
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
                    "· 银行短信仅在本地解析，而且需要你主动开启；\n" +
                    "· 没有任何数据会被上传到服务器；\n" +
                    "· 卸载应用，本机数据即被彻底删除。\n\n" +
                    "如果需要换机或备份，请用「导出账本数据」生成 CSV 文件自行保管。"
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
                lifecycleScope.launch {
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
}
