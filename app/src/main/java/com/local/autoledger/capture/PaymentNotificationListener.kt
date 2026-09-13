package com.local.autoledger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.local.autoledger.data.Prefs
import java.util.concurrent.Executors

/**
 * 自动记账的入口：监听微信 / 支付宝的支付结果通知。
 *
 * 需要在系统「通知使用权」里授权本应用。拿到通知后立刻解析并落库，
 * 整个链路不联网。
 *
 * 这里额外维护了一份「最近见过的通知」，用来判断时间：
 * 微信 / 支付宝会把同一条付款通知反复改写（动画、群组摘要都会触发
 * onNotificationPosted），而 StatusBarNotification.postTime 每次改写都会往前走。
 * 如果一律用 postTime 当交易时间，同一笔钱的时间就会漂；如果一律用首次时间，
 * 后面几笔真实支付又会落成同一个时刻。所以按通知内容是否变化来区分。
 */
class PaymentNotificationListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ledger-capture").apply { isDaemon = true }
    }

    /** 通知 key -> (正文, 采用的交易时间, 上次的 postTime)，最多留 64 条 */
    private data class Seen(val content: String, val occurredAt: Long, val postTime: Long)

    private val seen = object : LinkedHashMap<String, Seen>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>): Boolean =
            size > 64
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
        val nKey = "$pkg|${sbn.id}|${sbn.tag.orEmpty()}"
        val label = if (pkg == PaymentParser.PKG_WECHAT) "微信" else "支付宝"

        executor.execute {
            try {
                val occurredAt = resolveOccurredAt(nKey, combined, postTime)

                val parsed = PaymentParser.parseNotification(pkg, combined)
                if (parsed == null) {
                    // 原文照记一份，真机上「为什么这笔记不上」全指望这一行
                    CaptureLog.add("未识别", "$label｜${CaptureLog.brief(combined)}")
                    return@execute
                }
                CaptureSink.store(applicationContext, parsed, occurredAt)
            } catch (t: Throwable) {
                CaptureLog.add("异常", "$label｜${CaptureLog.brief(t.message ?: t.toString())}")
                Log.w(TAG, "解析通知失败", t)
            }
        }
    }

    /**
     * 决定这笔算在什么时间。
     *
     * - 内容没变、postTime 也没往前走：系统把老通知重放了一遍，沿用上次的时间，
     *   这样判重能精确命中原来那条记录；
     * - 其余情况都是「新的一笔」，用最新的 postTime。
     */
    private fun resolveOccurredAt(key: String, content: String, postTime: Long): Long {
        synchronized(seen) {
            val last = seen[key]
            val occurredAt =
                if (last != null && last.content == content && last.postTime >= postTime) {
                    last.occurredAt
                } else {
                    postTime
                }
            seen[key] = Seen(content, occurredAt, postTime)
            return occurredAt
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureLog.add("状态", "通知使用权已连接，自动记账就绪")
        Log.i(TAG, "通知使用权已连接，自动记账已就绪")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        CaptureLog.add("状态", "通知使用权已断开")
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
