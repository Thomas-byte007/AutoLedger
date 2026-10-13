package com.local.autoledger.capture

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.Prefs
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * 无障碍辅助记账：直接读微信 / 支付宝的「支付成功」页。
 *
 * 为什么需要它：通知监听再怎么加固，也拿不到一条系统里压根不存在的通知。
 * 支付宝在不少情况下只跳支付成功页、不弹通知（小额免密、余额付款、付款时 App 在前台……
 * 都可能），这类漏记只能靠读页面补上。
 *
 * 边界（这几条是产品的底线，改代码时别放宽）：
 *  1. 只监听微信 / 支付宝两个包名（XML 里的 android:packageNames 也限死了）；
 *  2. 只取可见文本，遇到 isPassword 的节点直接整棵跳过，绝不读输入框；
 *  3. 命中「支付成功」类确定性文案 + 金额 + 收支方向才会落库，其余一律丢弃；
 *  4. 数据只进本机 SQLite，链路全程不联网。
 *
 * 延迟兜底：同一笔支付往往通知和页面都会来一次。这里不抢着写库，
 * 而是等 [CONFIRM_DELAY_MS] 再回头看一眼最近有没有同一金额的记录 ——
 * 有就说明通知链路已经记过了，直接让开。所以无障碍是「补漏」而不是「抢记」，
 * 也就不需要给账目表加来源列去判重。
 *
 * 碰一下为什么要重扫：支付宝「碰一下」的成功页金额是动画渲染的，事件刚到的那一帧
 * 还没有金额，而页面又会在付完立刻返回。见 [handleScreen]。
 */
class PaymentAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ledger-access").apply { isDaemon = true }
    }

    /** 包名 -> 上次扫描时间，用来给频繁的内容变化降频 */
    private val lastScanAt = HashMap<String, Long>(2)

    /** 页面指纹 -> 上次处理时间。同一个成功页会反复触发 CONTENT_CHANGED，靠它去重 */
    private val recent = object : LinkedHashMap<String, Long>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > 24
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        // 落盘：看门狗靠这个时间戳分辨「用户没授权」和「授权还在但服务被系统回收了」
        Prefs.lastAccessAt = System.currentTimeMillis()
        CaptureLog.add("状态", "无障碍辅助记账已开启")
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onInterrupt() {
        CaptureLog.add("状态", "无障碍服务被中断")
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    /**
     * 这里只有一层壳：**绝不让任何异常冒出去**。
     *
     * 无障碍服务是 system_server 通过 binder 调进来的，回调里抛异常就算服务崩溃，
     * 而系统会直接把崩过的无障碍服务停用 —— 用户看到的现象就是「开关自己关了」。
     * 所以解析、遍历、取节点这些都可能抛的地方，一律在最外层兜住，只记日志不冒泡。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { handle(event ?: return) }
            .onFailure { Log.w(TAG, "处理无障碍事件失败", it) }
    }

    private fun handle(ev: AccessibilityEvent) {
        if (!Prefs.accessEnabled) return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg != PaymentParser.PKG_WECHAT && pkg != PaymentParser.PKG_ALIPAY) return

        when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scanPage(pkg)

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // 顺手当通知链路的备份：高版本 Android 上这条不一定带得全内容，
                // 只能算锦上添花，主路仍然是 PaymentNotificationListener。
                val sb = StringBuilder()
                ev.text?.filterNotNull()?.forEach { sb.append(it).append(' ') }
                ev.contentDescription?.let { sb.append(it).append(' ') }
                val text = sb.toString().replace(Regex("\\s+"), " ").trim()
                if (text.isNotBlank()) submit(pkg, text, fromScreen = false)
            }
        }
    }

    private fun scanPage(pkg: String) {
        val now = System.currentTimeMillis()
        synchronized(lastScanAt) {
            val last = lastScanAt[pkg] ?: 0L
            if (now - last < SCAN_INTERVAL_MS) return
            lastScanAt[pkg] = now
        }
        // rootInActiveWindow 在窗口切换的空档会抛 IllegalStateException / DeadObjectException
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val text = runCatching { collectText(root) }.getOrDefault("")
        if (text.isBlank()) return
        submit(pkg, text, fromScreen = true)
    }

    /**
     * 广度优先遍历窗口，把可见文本拼成一句话。
     * 节点数和深度都设了上限：支付结果页很浅，列表页扫到上限就停，省电也省心。
     */
    private fun collectText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (!node.isPassword) {
                node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
                node.contentDescription?.let {
                    if (it.isNotBlank() && it.length <= 40) sb.append(it).append(' ')
                }
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull()
                    if (child != null) queue.add(child to depth + 1)
                }
            }
            if (sb.length > SCREEN_TEXT_CAP) break
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun submit(pkg: String, text: String, fromScreen: Boolean) {
        if (fromScreen) {
            handleScreen(pkg, text)
            return
        }
        val parsed = PaymentParser.parseNotification(pkg, text) ?: return
        enqueue(parsed)
    }

    /**
     * 支付成功页的入口。
     *
     * 这里多一层「金额还没渲染出来」的处理：支付宝「碰一下」的支付成功页，金额是从无到有
     * 做动画画上去的，窗口事件打过来时那一帧里根本没有金额；而且「极速模式」下付完会立刻
     * 返回原页面，页面停留极短。于是**不能**只靠后续事件碰运气 —— [scanPage] 有 800ms 节流，
     * 真等到下一个事件，页面往往已经切走了，这笔就永久漏掉。
     * 判定为「成功页但缺金额」时自己排几次短延迟重扫，拿到就落库。
     */
    private fun handleScreen(pkg: String, text: String) {
        val parsed = PaymentParser.parsePaymentScreen(pkg, text)
        if (parsed != null) {
            enqueue(parsed)
            return
        }
        if (PaymentParser.incompleteSuccessPage(pkg, text)) scheduleRescan(pkg, 0)
    }

    /** 隔一小会儿再看一眼当前窗口，直到拿到金额或重试次数用尽 */
    private fun scheduleRescan(pkg: String, attempt: Int) {
        if (attempt >= RESCAN_DELAYS_MS.size) return
        handler.postDelayed({
            if (!running || !Prefs.accessEnabled) return@postDelayed
            // 窗口可能已经切走 —— 碰一下付完就回原页面了，别把别的应用的字当成支付页
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@postDelayed
            if (root.packageName?.toString() != pkg) return@postDelayed
            val text = runCatching { collectText(root) }.getOrDefault("")
            val parsed = if (text.isBlank()) null else PaymentParser.parsePaymentScreen(pkg, text)
            if (parsed != null) enqueue(parsed) else scheduleRescan(pkg, attempt + 1)
        }, RESCAN_DELAYS_MS[attempt])
    }

    private fun enqueue(parsed: PaymentParser.Parsed) {
        val key = "${parsed.channel.key}|${parsed.amountCents}|${parsed.direction.code}"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val last = recent[key] ?: 0L
            if (now - last < REPEAT_MS) return
            recent[key] = now
        }
        // 先等一会儿：通知链路通常一两秒内就写库了，让它先赢
        handler.postDelayed({ dispatch(parsed) }, CONFIRM_DELAY_MS)
    }

    /** 确认通知链路没记过，才由无障碍这条补上 */
    private fun dispatch(parsed: PaymentParser.Parsed) {
        executor.execute {
            try {
                val since = System.currentTimeMillis() - CONFIRM_LOOKBACK_MS
                if (LedgerRepository.hasRecent(parsed.direction, parsed.amountCents, since)) {
                    CaptureLog.add(
                        "跳过",
                        "通知链路已记过同一笔｜${parsed.channel.label} ${Money.plain(parsed.amountCents)}"
                    )
                    return@execute
                }
                val id = CaptureSink.store(applicationContext, parsed, System.currentTimeMillis())
                if (id > 0) {
                    CaptureLog.add(
                        "辅助记账",
                        "${parsed.channel.label} ${Money.signed(parsed.amountCents, parsed.direction)}"
                    )
                }
            } catch (t: Throwable) {
                CaptureLog.add("异常", "辅助记账｜${CaptureLog.brief(t.message ?: t.toString())}")
                Log.w(TAG, "辅助记账失败", t)
            }
        }
    }

    companion object {
        /** 进程内存活标记，设置页据此提示「已开启 / 未开启」 */
        @Volatile
        var running: Boolean = false
            private set

        private const val TAG = "LedgerAccess"
        private const val SCAN_INTERVAL_MS = 800L
        private const val REPEAT_MS = 3 * 60 * 1000L
        private const val CONFIRM_DELAY_MS = 8_000L
        private const val CONFIRM_LOOKBACK_MS = 5 * 60 * 1000L
        private const val MAX_NODES = 240
        private const val MAX_DEPTH = 10
        private const val SCREEN_TEXT_CAP = 1200

        /**
         * 「金额还没画出来」时的重扫节奏。
         *
         * 支付宝碰一下的金额是个短动画，几百毫秒内就位；但页面可能只停留一两秒，
         * 所以头两次要快，最后再兜一次慢的。总时长刻意压在通知链路的
         * [CONFIRM_DELAY_MS] 之内，保证「通知优先」的次序不变。
         */
        private val RESCAN_DELAYS_MS = longArrayOf(350L, 900L, 1_800L)
    }
}
