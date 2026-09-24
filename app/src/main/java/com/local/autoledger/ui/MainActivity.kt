package com.local.autoledger.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.local.autoledger.R
import com.local.autoledger.capture.NotifyLink
import com.local.autoledger.data.Prefs
import com.local.autoledger.databinding.ActivityMainBinding
import com.local.autoledger.service.KeepAliveService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不影响主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_detail -> show(FRAG_DETAIL, ::TransactionsFragment)
                R.id.nav_report -> show(FRAG_REPORT, ::ReportFragment)
                R.id.nav_mine -> show(FRAG_MINE, ::SettingsFragment)
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_detail
        }

        maybeAskNotificationPermission()
        // 无障碍服务没开就引导一次。延后一点再弹：让首屏先画出来，
        // 也避免和通知权限的系统弹窗叠在一起（两个弹窗同时出现，用户会看懵）。
        binding.root.postDelayed({ maybePromptAccessibility() }, PROMPT_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        // 从系统无障碍设置页回来时结算一次：真开了就把 App 内的辅助记账开关补上
        AccessibilityPrompt.onResume(this)
        // 用户开了「后台常驻运行」就保证前台服务在跑（在前台补一次，避免后台启动被系统拒绝）
        if (Prefs.keepAlive && !KeepAliveService.running) {
            KeepAliveService.start(this)
        }
        // 通知监听会「静默掉线」：系统以为还绑着，其实一条都收不到。
        // 每次回到前台做一次自检，断了就重新接上 —— 只在真断了时才动手，正常运行不打扰。
        runCatching { NotifyLink.watchdog(this) }
    }

    /** 首页轮播提示、以及「我的」页面都会用到切换 tab */
    fun goToTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    private fun show(tag: String, factory: () -> Fragment) {
        val fm = supportFragmentManager
        fm.commit {
            setReorderingAllowed(true)
            fm.fragments.forEach { hide(it) }
            val existing = fm.findFragmentByTag(tag)
            if (existing != null) show(existing) else add(R.id.container, factory(), tag)
        }
    }

    /** 启动引导：系统无障碍服务没开时弹一次（已开启 / 本次会话弹过都不再弹） */
    private fun maybePromptAccessibility() {
        if (isFinishing || isDestroyed) return
        runCatching { AccessibilityPrompt.maybeShow(this) }
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionsUtil.hasPostNotification(this)
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val FRAG_DETAIL = "frag_detail"
        const val FRAG_REPORT = "frag_report"
        const val FRAG_MINE = "frag_mine"

        /** 首屏画出来之后再弹引导弹窗，别和启动画面抢 */
        const val PROMPT_DELAY_MS = 600L
    }
}
