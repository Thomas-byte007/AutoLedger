package com.local.autoledger

import com.local.autoledger.capture.AccessWatchdog
import com.local.autoledger.capture.AccessWatchdog.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 无障碍链路的活性判定。
 *
 * 关键是要把两种「看起来都是没在记账」的状态分开：
 *   · 用户没授权（OFF） —— 不算故障，别去烦他；
 *   · 授权还在、服务没了（BROKEN） —— 进程被清理的典型症状，界面必须说出来。
 * 另外服务刚被授权时绑定有延迟，宽限期内不能误报。
 */
class AccessWatchdogTest {

    private companion object {
        const val NOW = 1_800_000_000_000L
    }

    private fun health(
        now: Long = NOW,
        granted: Boolean = true,
        running: Boolean = true,
        lastConnectedAt: Long = NOW,
        enabled: Boolean = true
    ) = AccessWatchdog.health(now, granted, running, lastConnectedAt, enabled)

    @Test
    fun 没开辅助记账或没授权都不算故障() {
        assertEquals(State.OFF, health(enabled = false))
        assertEquals(State.OFF, health(granted = false))
        // 没授权时哪怕服务没跑，也不能提示「服务未运行」——用户根本没开这功能
        assertEquals(State.OFF, health(granted = false, running = false))
    }

    @Test
    fun 服务在跑就是正常() {
        assertEquals(State.RUNNING, health(running = true))
        // 服务活着但连接时间很旧（进程早就被回收过一次又重连），仍以 running 为准
        assertEquals(State.RUNNING, health(running = true, lastConnectedAt = 0L))
    }

    @Test
    fun 刚授权还在宽限期内不误报() {
        val justNow = NOW - AccessWatchdog.GRACE_MS + 1_000L
        assertEquals(State.RUNNING, health(now = NOW, running = false, lastConnectedAt = justNow))
    }

    @Test
    fun 授权在服务却没跑就是断了() {
        // 典型场景：上滑清理 → 进程被杀 → 系统可能还留着授权，但服务已经没了
        val longAgo = NOW - AccessWatchdog.GRACE_MS - 60_000L
        assertEquals(State.BROKEN, health(now = NOW, running = false, lastConnectedAt = longAgo))
        // 从没连上过（lastConnectedAt = 0）同样算断
        assertEquals(State.BROKEN, health(now = NOW, running = false, lastConnectedAt = 0L))
    }

    @Test
    fun 只有断了才有话要说() {
        assertNull(AccessWatchdog.describe(State.RUNNING))
        assertNull(AccessWatchdog.describe(State.OFF))
        assertNotNull(AccessWatchdog.describe(State.BROKEN))
    }
}
