package com.local.autoledger

import android.app.Application
import com.local.autoledger.capture.CaptureLog
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Prefs

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
