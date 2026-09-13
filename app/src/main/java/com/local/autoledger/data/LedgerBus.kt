package com.local.autoledger.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** 极简事件总线：数据变化后通知界面刷新 */
object LedgerBus {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun register(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun unregister(l: () -> Unit) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        if (listeners.isEmpty()) return
        main.post { listeners.toList().forEach { runCatching { it() } } }
    }
}
