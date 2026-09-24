package com.local.autoledger.capture

import android.content.Context
import com.local.autoledger.data.Prefs
import com.local.autoledger.ui.PermissionsUtil

/**
 * 无障碍链路的「活性」守护（跟 [NotifyLink] 是同一套思路，只是对象换成了无障碍服务）。
 *
 * 为什么要它：无障碍服务跑在**本 app 的进程里**，进程一死服务就没了。而系统设置里的开关
 * 常常还开着（也可能被 force-stop 一并摘掉），于是出现两种用户根本分不清的状态：
 *   1. 开关开着、服务没跑 —— 记账悄悄失效，界面却显示「已开启」；
 *   2. 开关被系统关了 —— 只能由用户去设置页重新开。
 * 这里把两者分开，让界面有话可说。
 *
 * **能力边界（别幻想）**：第三方 App 没有任何 API 能自己打开无障碍开关
 * （root / Shizuku / ADB 除外）。所以看门狗检测到断链后能做的只有：
 *   1. 记一条日志，让「运行日志」页有据可查；
 *   2. 让设置页把状态如实显示出来，并给用户一个直达设置页的入口。
 * 真正管用的是**不让它断**（前台服务 + 电池优化 + 自启动 + 多任务加锁），不是死后复活。
 */
object AccessWatchdog {

    enum class State {
        /** 授权在、服务也在本进程跑着 */
        RUNNING,
        /** 授权在，但服务此刻没跑 —— 大概率是进程被清理了 */
        BROKEN,
        /** 没开辅助记账，或系统里根本没授权，不算故障 */
        OFF
    }

    /**
     * 允许「授权刚打开、服务还没来得及连上」的宽限期。
     * 用户在系统设置里拨开关之后，绑定会有几百毫秒到几秒的延迟。
     */
    const val GRACE_MS = 2 * 60 * 1000L

    /** 断链日志的节流：心跳 10 分钟一次，没必要每次都写一行 */
    private const val LOG_THROTTLE_MS = 60 * 60 * 1000L

    @Volatile
    var lastLoggedAt: Long = 0L
        private set

    /**
     * 纯函数判定，方便单测。
     *
     * @param granted         系统无障碍设置里是否已开启
     * @param running         本进程内服务是否还活着
     * @param lastConnectedAt 最近一次 onServiceConnected 的时间戳
     */
    fun health(
        now: Long,
        granted: Boolean,
        running: Boolean,
        lastConnectedAt: Long,
        enabled: Boolean = Prefs.accessEnabled
    ): State {
        if (!enabled || !granted) return State.OFF
        if (running) return State.RUNNING
        return if (now - lastConnectedAt <= GRACE_MS) State.RUNNING else State.BROKEN
    }

    /** 给界面用的一句话，null 表示没什么好说的 */
    fun describe(state: State): String? = when (state) {
        State.RUNNING -> null
        State.BROKEN -> "系统里已开启，但服务没在运行，重新开关一次可恢复"
        State.OFF -> null
    }

    /**
     * 看门狗入口：由常驻服务心跳调用。
     * @return 本次是否判定为断链
     */
    fun watchdog(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val state = health(
            now = now,
            granted = PermissionsUtil.hasAccessibility(context),
            running = PaymentAccessibilityService.running,
            lastConnectedAt = Prefs.lastAccessAt
        )
        if (state != State.BROKEN) return false
        if (now - lastLoggedAt < LOG_THROTTLE_MS) return false
        lastLoggedAt = now
        CaptureLog.add("状态", "辅助记账：系统已授权但服务未运行（进程可能被清理）")
        return true
    }

    /** 单测专用：清掉节流标记 */
    fun resetForTest() {
        lastLoggedAt = 0L
    }
}
