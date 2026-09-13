package com.local.autoledger.data

import android.content.Context
import android.content.SharedPreferences

/** 轻量设置项（全部本地） */
object Prefs {
    private const val FILE = "ledger_prefs"

    const val KEY_CAPTURE_ENABLED = "capture_enabled"
    const val KEY_SMS_ENABLED = "sms_enabled"
    const val KEY_LAST_IMPORT_AT = "last_import_at"
    const val KEY_DISMISS_ONBOARD = "dismiss_onboard"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var captureEnabled: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_CAPTURE_ENABLED, v).apply()

    var smsEnabled: Boolean
        get() = sp.getBoolean(KEY_SMS_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_SMS_ENABLED, v).apply()

    var lastImportAt: Long
        get() = sp.getLong(KEY_LAST_IMPORT_AT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_IMPORT_AT, v).apply()

    var dismissOnboard: Boolean
        get() = sp.getBoolean(KEY_DISMISS_ONBOARD, false)
        set(v) = sp.edit().putBoolean(KEY_DISMISS_ONBOARD, v).apply()
}
