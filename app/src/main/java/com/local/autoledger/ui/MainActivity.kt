package com.local.autoledger.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.local.autoledger.R
import com.local.autoledger.databinding.ActivityMainBinding

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
    }
}
