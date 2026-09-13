package com.local.autoledger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.local.autoledger.LedgerApp
import com.local.autoledger.data.Prefs
import java.util.concurrent.Executors

/**
 * 自动记账的入口：监听微信 / 支付宝的支付结果通知。
 *
 * 需要在系统「通知使用权」里授权本应用。拿到通知后立刻解析并落库，
 * 整个链路不联网。
 */
class PaymentNotificationListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ledger-capture").apply { isDaemon = true }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg != PaymentParser.PKG_WECHAT && pkg != PaymentParser.PKG_ALIPAY) return
        if (!Prefs.captureEnabled) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val combined = listOf(title, text, bigText, lines, subText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val postTime = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()

        executor.execute {
            try {
                val parsed = PaymentParser.parseNotification(pkg, combined) ?: return@execute
                // 微信 / 支付宝的支付通知通常不早于真实交易时间太多，直接用通知时间
                CaptureSink.store(applicationContext, parsed, postTime)
            } catch (t: Throwable) {
                Log.w(TAG, "解析通知失败", t)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知使用权已连接，自动记账已就绪")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "通知使用权已断开")
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LedgerNotify"
    }
}
