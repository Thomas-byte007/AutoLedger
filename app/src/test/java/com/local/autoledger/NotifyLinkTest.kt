package com.local.autoledger

import com.local.autoledger.capture.NotifyLink
import com.local.autoledger.capture.NotifyLink.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知链路的活性判定。
 *
 * 这套判定是「为什么界面上明明写着运行中，却一笔都记不上」的答案，
 * 规则一旦写错，用户要么被假警报烦死，要么继续被静默失效坑着，所以单独锁住。
 */
class NotifyLinkTest {

    private companion object {
        const val NOW = 1_800_000_000_000L
    }

    @Test
    fun 记账开关没开不算故障() {
        assertEquals(
            State.OFF,
            NotifyLink.health(NOW, connected = true, lastNotifyAt = NOW, captureEnabled = false)
        )
        // 没开记账时哪怕静默一年，也不能提示「链接已断开」
        assertEquals(
            State.OFF,
            NotifyLink.health(
                NOW, connected = false, lastNotifyAt = 0L, captureEnabled = false
            )
        )
    }

    @Test
    fun 本进程没连上就是断的() {
        assertEquals(
            State.DEAD,
            NotifyLink.health(NOW, connected = false, lastNotifyAt = NOW, captureEnabled = true)
        )
    }

    @Test
    fun 刚收到通知算正常运行() {
        val last = NOW - 30_000L
        assertEquals(
            State.RUNNING,
            NotifyLink.health(NOW, connected = true, lastNotifyAt = last, captureEnabled = true)
        )
    }

    @Test
    fun 静默超过阈值先判可疑再判死() {
        val silent = NOW - NotifyLink.SILENT_MS - 1_000L
        assertEquals(
            State.SILENT,
            NotifyLink.health(NOW, connected = true, lastNotifyAt = silent, captureEnabled = true)
        )

        val dead = NOW - NotifyLink.DEAD_MS - 1_000L
        assertEquals(
            State.DEAD,
            NotifyLink.health(NOW, connected = true, lastNotifyAt = dead, captureEnabled = true)
        )
    }

    @Test
    fun 阈值边界不能来回跳() {
        val atSilent = NOW - NotifyLink.SILENT_MS
        assertEquals(
            State.SILENT,
            NotifyLink.health(NOW, connected = true, lastNotifyAt = atSilent, captureEnabled = true)
        )
        val justBefore = NOW - NotifyLink.SILENT_MS + 1_000L
        assertEquals(
            State.RUNNING,
            NotifyLink.health(NOW, connected = true, lastNotifyAt = justBefore, captureEnabled = true)
        )
    }

    @Test
    fun 从没收到过通知只算可疑不算死() {
        assertEquals(
            State.SILENT,
            NotifyLink.health(NOW, connected = true, lastNotifyAt = 0L, captureEnabled = true)
        )
    }

    @Test
    fun 说明文案只在异常时才给提示() {
        assertEquals(null, NotifyLink.describe(State.OFF))
        assertEquals(null, NotifyLink.describe(State.RUNNING, NOW))
    }

    @Test
    fun 从没收到过通知时文案不出现荒唐的天数() {
        NotifyLink.lastNotifyAt = 0L
        val text = NotifyLink.describe(State.SILENT, NOW)
        assertEquals(true, text!!.contains("还没收到过"))
    }
}
