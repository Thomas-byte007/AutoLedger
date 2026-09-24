package com.local.autoledger.data

import android.content.Context
import android.content.SharedPreferences

/** 轻量设置项（全部本地） */
object Prefs {
    private const val FILE = "ledger_prefs"

    const val KEY_CAPTURE_ENABLED = "capture_enabled"
    const val KEY_KEEP_ALIVE = "keep_alive"
    const val KEY_LAST_IMPORT_AT = "last_import_at"
    const val KEY_DISMISS_ONBOARD = "dismiss_onboard"
    const val KEY_ACCESS_ENABLED = "access_enabled"
    const val KEY_LAST_NOTIFY_AT = "last_notify_at"
    const val KEY_LAST_ACCESS_AT = "last_access_connected_at"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var captureEnabled: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_CAPTURE_ENABLED, v).apply()

    var lastImportAt: Long
        get() = sp.getLong(KEY_LAST_IMPORT_AT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_IMPORT_AT, v).apply()

    /**
     * 后台常驻：前台服务 + 常驻通知。
     *
     * 默认关。常驻通知会一直挂在通知栏上，属于用户明确可见的行为，
     * 所以由用户自己在「我的 → 后台常驻运行」里打开，不偷偷开。
     */
    var keepAlive: Boolean
        get() = sp.getBoolean(KEY_KEEP_ALIVE, false)
        set(v) = sp.edit().putBoolean(KEY_KEEP_ALIVE, v).apply()

    var dismissOnboard: Boolean
        get() = sp.getBoolean(KEY_DISMISS_ONBOARD, false)
        set(v) = sp.edit().putBoolean(KEY_DISMISS_ONBOARD, v).apply()

    /**
     * 无障碍辅助记账：读微信 / 支付宝的「支付成功」页。
     *
     * 默认关 —— 它要多一个系统级授权，必须由用户在无障碍设置里亲手打开，
     * 而且开关和系统授权是两回事，两个都满足才干活。
     */
    var accessEnabled: Boolean
        get() = sp.getBoolean(KEY_ACCESS_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ACCESS_ENABLED, v).apply()

    /**
     * 最近一次收到任意通知的时间戳。
     *
     * 这是判断「通知监听还活着」的唯一可靠依据：系统只要正常绑定，
     * 手机上迟早会有别的通知进来。详见 [com.local.autoledger.capture.NotifyLink]。
     */
    var lastNotifyAt: Long
        get() = sp.getLong(KEY_LAST_NOTIFY_AT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_NOTIFY_AT, v).apply()

    /**
     * 最近一次无障碍服务 onServiceConnected 的时间戳。
     *
     * 「系统设置里开关开着」和「服务此刻真的在跑」是两回事，后面这个值只可能由
     * 服务自己写；进程被杀后它停在旧值，看门狗据此发现「授权还在、服务没了」。
     * 详见 [com.local.autoledger.capture.AccessWatchdog]。
     */
    var lastAccessAt: Long
        get() = sp.getLong(KEY_LAST_ACCESS_AT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_ACCESS_AT, v).apply()
}
