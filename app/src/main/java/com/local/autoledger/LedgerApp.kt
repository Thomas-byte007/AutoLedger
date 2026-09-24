package com.local.autoledger

import android.app.Application
import com.local.autoledger.capture.CaptureLog
import com.local.autoledger.capture.NotifyLink
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Prefs
import com.local.autoledger.service.KeepAliveService

/**
 * 应用入口。整包无网络权限，所有数据只落在本机 SQLite。
 */
class LedgerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        LedgerRepository.init(this)
        CaptureLog.init(this)
        NotifyLink.seed()

        // 进程被系统「为了无障碍 / 通知监听」重新拉起来时，Activity 还没影，
        // 这里是唯一能自愈的地方：趁早把常驻服务接回去，顺手把巡检闹钟排上。
        // 注意此刻仍是后台启动，只有「用户已关闭电池优化」这一条豁免能让它成功，
        // 失败就静默放过 —— 兜底靠 MainActivity.onResume 在前台补启动。
        if (Prefs.keepAlive && !KeepAliveService.running) {
            runCatching { KeepAliveService.start(this) }
        }

        // 后台把内置分类规则灌进数据库，避免首次记账时卡主线程
        Thread {
            runCatching { LedgerRepository.rules() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    companion object {
        lateinit var instance: LedgerApp
            private set
    }
}
