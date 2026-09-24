package com.local.autoledger.capture

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import com.local.autoledger.data.Prefs

/**
 * 通知监听链路的「活性」守护。
 *
 * Android 有个长年未修的缺陷：NotificationListenerService 所在进程被杀之后，
 * 系统**不会**重新 bind 它，但 `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`
 * 里包名还在 —— 于是权限页显示「已开启」，App 自查也通过，可是
 * `onNotificationPosted` 再也不会来。用户看到的现象就是「刚装好能记一笔，之后就没反应了」，
 * 只有重启手机、或者手动把通知使用权关掉再打开才恢复。
 *
 * 这里做三件事：
 *  1. [rebind]      —— 便宜、幂等：请求系统重新绑定（API 24+）；
 *  2. [reconnect]   —— 贵、但更狠：把组件 disable 再 enable，逼系统走一遍完整的绑定流程，
 *                      对 MIUI / ColorOS 这类「requestRebind 不生效」的 ROM 才有效，
 *                      代价是中间会有十几秒的空窗，所以只在确认断了的时候做；
 *  3. [health]      —— 给界面一个诚实的健康度，别再拿「权限开着」冒充「正在工作」。
 *
 * 判定「断了」的唯一可靠信号是**静默**：系统只要是正常绑定的，别的应用（微信消息、
 * 系统更新、广告推送……）迟早会有通知进来。所以我们记录「最近一次收到任意通知的时间」，
 * 长时间一无所获就认为链路已死。
 */
object NotifyLink {

    enum class State {
        /** 绑定正常，且近期确实收到过通知 */
        RUNNING,
        /** 绑定正常，但很久没收到任何通知 —— 可能只是手机安静，也可能已经死了 */
        SILENT,
        /** 本进程里监听服务根本没连上 */
        DEAD,
        /** 记账开关没开 / 没授权，不算故障 */
        OFF
    }

    /** 连续多久一条通知都没有，就认为可疑 */
    const val SILENT_MS = 45 * 60 * 1000L
    /** 可疑之后再静默这么久，就判定已死（此时才动用重连） */
    const val DEAD_MS = 3 * 60 * 60 * 1000L

    /** 本进程内监听服务是否已连上（onListenerConnected 置位） */
    @Volatile
    var connected: Boolean = false

    /**
     * 最近一次收到任意通知的时间（进程内存活版，落盘有节流）。
     *
     * 0 表示这条链路从没收到过 —— 界面文案和判定都要把这个值单独对待，
     * 直接拿 now - 0 去算会得出「已经两万天没收到」这种荒唐话。
     */
    @Volatile
    var lastNotifyAt: Long = 0L

    /** 最近一次重连动作的时间，避免看门狗狂点 */
    @Volatile
    var lastRebindAt: Long = 0L
        private set

    /** 进程刚起来时把落盘的心跳读回来，否则界面会误判成「从没收到过」 */
    fun seed() {
        if (lastNotifyAt == 0L) lastNotifyAt = Prefs.lastNotifyAt
    }

    /**
     * 收到任意一条通知就打一次点。
     *
     * 注意这里**不区分包名**：微信 / 支付宝之外的通知同样说明链路是活的，
     * 只统计支付通知的话，半天没花钱就会被误判成链路断了。
     */
    fun markNotify(now: Long = System.currentTimeMillis()) {
        lastNotifyAt = now
        if (now - Prefs.lastNotifyAt > 30_000L) Prefs.lastNotifyAt = now
    }

    /** 便宜的重绑：只请求系统重新绑定，幂等，开销可以忽略 */
    fun rebind(context: Context) {
        lastRebindAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, PaymentNotificationListener::class.java)
                )
            }.onFailure { Log.w(TAG, "requestRebind 失败", it) }
        }
    }

    /**
     * 强制重连：先 rebind，再把组件关掉再打开，逼系统重新走一遍绑定。
     * 这一下有十几秒空窗，所以只在 [State.DEAD] 或用户手动点「重新连接」时用。
     */
    fun reconnect(context: Context) {
        rebind(context)
        val cn = ComponentName(context, PaymentNotificationListener::class.java)
        val pm = context.packageManager
        runCatching {
            pm.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        }.onFailure { Log.w(TAG, "组件重置失败", it) }
        CaptureLog.add("状态", "已强制重新连接通知监听")
    }

    /**
     * 看门狗入口：由常驻服务心跳 / 开机广播调用。
     *
     * 只在「疑似断了」时才动手，正常运行时绝不打扰 —— 每次 rebind 都有一小段空窗，
     * 无脑定时重连反而会漏掉恰好落在空窗里的那一笔。
     */
    fun watchdog(context: Context, now: Long = System.currentTimeMillis()) {
        if (!Prefs.captureEnabled) return
        seed()
        val state = health(now = now, connected = connected, lastNotifyAt = lastNotifyAt)
        when (state) {
            State.DEAD -> {
                if (now - lastRebindAt > 5 * 60 * 1000L) reconnect(context)
            }
            State.SILENT -> {
                if (now - lastRebindAt > 15 * 60 * 1000L) rebind(context)
            }
            else -> Unit
        }
    }

    /**
     * 健康度判定（纯函数，方便单测）。
     *
     * @param connected    本进程内监听服务是否连上
     * @param lastNotifyAt 最近一次收到任意通知的时间，0 表示从未收到过
     */
    fun health(
        now: Long,
        connected: Boolean,
        lastNotifyAt: Long,
        captureEnabled: Boolean = Prefs.captureEnabled
    ): State {
        if (!captureEnabled) return State.OFF
        if (!connected) return State.DEAD
        if (lastNotifyAt <= 0L) return State.SILENT
        val quiet = now - lastNotifyAt
        return when {
            quiet < SILENT_MS -> State.RUNNING
            quiet < DEAD_MS -> State.SILENT
            else -> State.DEAD
        }
    }

    /** 给界面用的一句话说明，null 表示没什么好说的 */
    fun describe(state: State, now: Long = System.currentTimeMillis()): String? {
        val last = lastNotifyAt
        return when (state) {
            State.RUNNING -> if (last > 0) "最近一次收到通知：${ago(now - last)}" else null
            State.SILENT ->
                // lastNotifyAt 为 0 表示这条链路从没收到过任何通知。
                // 直接拿 now - 0 去算会得出「已经两万天没收到」这种荒唐话。
                if (last <= 0L) "还没收到过任何通知，付款一笔就会看到它动起来"
                else "已经 ${ago(now - last)}没收到任何通知，链接可能断了"
            State.DEAD -> "通知链接已断开，支付通知收不到"
            State.OFF -> null
        }
    }

    private fun ago(ms: Long): String {
        if (ms <= 60_000L) return "不到 1 分钟"
        val min = ms / 60_000L
        if (min < 60) return "$min 分钟"
        val h = min / 60
        return if (h < 24) "$h 小时" else "${h / 24} 天"
    }

    private const val TAG = "LedgerNotifyLink"
}
