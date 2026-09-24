package com.local.autoledger.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.local.autoledger.R
import com.local.autoledger.capture.AccessWatchdog
import com.local.autoledger.capture.NotifyLink
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.Prefs
import com.local.autoledger.data.TimeFmt
import com.local.autoledger.ui.MainActivity

/**
 * 后台常驻。
 *
 * 用户打开「后台常驻运行」后，这里起一个前台服务并在通知栏留一条常驻入口：
 * 前台服务会把进程优先级抬起来，所以从最近任务里划掉 App、或者系统回收内存时，
 * 记账链路（尤其是监听支付通知的服务）不会被一起带走。
 *
 * WHATWG 式的「保活黑科技」一概不用：没有双进程拉扯、没有无声播放、没有自启动广播轰炸。
 * 只有三件老实事：
 *  1. 前台服务本身（stopWithTask=false，划掉任务不停服务）；
 *  2. 开机 / 应用升级后自己再起来一次（[KeepAliveReceiver]）；
 *  3. 一个 15 分钟的闹钟做兜底巡检，万一被杀也能在下一个闹钟重新拉起。
 *
 * 常驻不是没有代价：通知栏会一直有一条入口，并且提供「停止后台」按钮，用户随时能关。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** 每隔一会儿刷新一下通知上的「今天花了多少」，顺便让服务保持心跳 */
    private val ticker = object : Runnable {
        override fun run() {
            refreshNotification()
            // 通知监听是整套自动记账的命门，但它会「静默断链」：
            // 系统认为还绑着、权限页也显示已开启，实际上一条通知都收不到。
            // 心跳里做一次活性自检，断了就重新连接。
            runCatching { NotifyLink.watchdog(this@KeepAliveService) }
            // 无障碍那条链路同样会「授权还在、服务没了」，补一次自检（只能记录，不能自愈）
            runCatching { AccessWatchdog.watchdog(this@KeepAliveService) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForegroundCompat()
        handler.postDelayed(ticker, TICK_MS)
        KeepAliveReceiver.scheduleWatchdog(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.keepAlive = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                Prefs.keepAlive = true
                KeepAliveReceiver.scheduleWatchdog(this)
                // 服务被拉起来说明进程刚活过来，顺手把通知监听也重新绑一次
                runCatching { NotifyLink.rebind(this) }
            }
        }
        startForegroundCompat()
        if (!running) {
            running = true
            handler.postDelayed(ticker, TICK_MS)
        }
        // 被系统杀掉后尽量再起来：这是 STOP WITH TASK 之外最重要的一行
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        running = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉卡片时不要停服：这正是「清理了也还在后台跑」要的效果。
        // 什么都不做，系统保留前台服务。
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIFY_ID, buildNotification(LedgerRepository.todayExpenseCents()),
                FOREGROUND_SERVICE_TYPE
            )
        }.onFailure {
            // Android 12+ 后台起前台服务被拒：退化为不带类型的老接口再试一次
            runCatching { startForeground(NOTIFY_ID, buildNotification(0L)) }
        }
    }

    /** 通知文案要读库，丢到后台线程算，算完回主线程更新 */
    private fun refreshNotification() {
        if (!hasNotifyPermission()) return
        Thread {
            val today = LedgerRepository.todayExpenseCents()
            handler.post {
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFY_ID, buildNotification(today))
                }
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun hasNotifyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(todayCents: Long): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ledger)
            .setContentTitle(getString(R.string.keepalive_notify_title))
            .setContentText(summaryText(todayCents))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.keepalive_action_stop), stop)
            .build()
    }

    /** 通知上那句话：顺手把今天花了多少递给用户 */
    private fun summaryText(todayCents: Long): String {
        if (todayCents <= 0) return getString(R.string.keepalive_notify_idle)
        return getString(
            R.string.keepalive_notify_text,
            TimeFmt.dayLabel(TimeFmt.toDate(System.currentTimeMillis())),
            Money.yuan(todayCents)
        )
    }

    companion object {
        const val CHANNEL_ID = "keep_alive"
        const val ACTION_START = "com.local.autoledger.action.KEEP_ALIVE_START"
        const val ACTION_STOP = "com.local.autoledger.action.KEEP_ALIVE_STOP"

        private const val NOTIFY_ID = 9001
        private const val TICK_MS = 10 * 60 * 1000L

        /**
         * 前台服务类型：Android 14+ 必须是 manifest 里声明过的类型之一，否则 SecurityException。
         *
         * 用 specialUse 而不是 dataSync，是因为 Android 15 禁止在 BOOT_COMPLETED 里起 dataSync
         * 型前台服务；34 以下不认这个标志位，传 0 即可（老版本按 manifest 声明走）。
         */
        private val FOREGROUND_SERVICE_TYPE: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

        /** 进程内存活标记（进程被杀后自然变 false，看门狗据此判断要不要拉起） */
        @Volatile
        var running: Boolean = false
            private set

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.keepalive_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持随手记在后台运行，随时等待支付通知"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        /** 打开开关：优先走前台服务通道，被系统限制时退化为普通启动 */
        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                runCatching { context.startService(intent) }
            }
            schedule(context)
        }

        fun stop(context: Context) {
            Prefs.keepAlive = false
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
            KeepAliveReceiver.cancelWatchdog(context)
        }

        /** 闹钟兜底：进程万一被打死，下一个闹钟把它拉回来 */
        fun schedule(context: Context) {
            if (!Prefs.keepAlive) return
            KeepAliveReceiver.scheduleWatchdog(context)
        }
    }
}

/**
 * 兜底巡检接收器 + 开机自启。
 *
 * 只有用户开了「后台常驻运行」才干活，没开就什么都不做。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 通知监听不受「后台常驻」开关管辖：记账开着就该顺手把它接回去。
        // 开机和应用升级是最典型的两个「监听断了没人知道」的时点。
        if (Prefs.captureEnabled) {
            runCatching { NotifyLink.watchdog(context) }
        }
        if (Prefs.accessEnabled) {
            // 无障碍那条也顺手自检一次：授权还在但服务没了，日志里要留痕
            runCatching { AccessWatchdog.watchdog(context) }
        }
        if (!Prefs.keepAlive) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                KeepAliveService.start(context)
            }
            ACTION_WATCHDOG -> {
                if (!KeepAliveService.running) KeepAliveService.start(context)
                // 精确闹钟是一次性的，必须在每次执行后自己排下一次，否则链断一次就再也不响了
                scheduleWatchdog(context)
            }
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.local.autoledger.action.KEEP_ALIVE_WATCHDOG"
        private const val REQ = 9101

        /** 巡检间隔：精确闹钟 + Doze 节流（约 9 分钟）之间取个稳妥值 */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

        /**
         * 排下一次巡检。
         *
         * **必须用精确闹钟**：Android 12+ 只有「精确闹钟触发」才属于「从后台启动前台服务」的
         * 豁免场景，inexact 闹钟不算。以前这里用 `setInexactRepeating`，看门狗被唤醒后
         * `startForegroundService()` 直接抛 ForegroundServiceStartNotAllowedException，
         * 于是「进程死了再拉起来」这条兜底从来没生效过。
         *
         * 精确闹钟会在 Doze 下被节流（约 9 分钟一次），这是系统硬限制，取 15 分钟间隔更稳。
         * 用户若在系统里关掉了「闹钟与提醒」特殊权限，退回 allowWhileIdle 的普通闹钟：
         * 那样起不了前台服务，但至少还能跑一次通知链路自检。
         */
        fun scheduleWatchdog(context: Context) {
            val mgr = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = pending(context, PendingIntent.FLAG_UPDATE_CURRENT)
            val at = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !mgr.canScheduleExactAlarms()) {
                runCatching { mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                return
            }
            runCatching { mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                .onFailure {
                    runCatching { mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                }
        }

        fun cancelWatchdog(context: Context) {
            val mgr = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching { mgr.cancel(pending(context, PendingIntent.FLAG_NO_CREATE)) }
        }

        private fun pending(context: Context, flags: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, REQ,
                Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_WATCHDOG),
                flags or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
