package com.local.autoledger.capture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 自动记账的采集日志。
 *
 * 「为什么这笔没记上」在真机上只能靠它定位：每收到一条支付通知、每次解析失败、
 * 每次因为判重被丢掉，都会在这里留一行。只写本机文件，最多保留 [MAX_LINES] 行。
 */
object CaptureLog {

    private const val MAX_LINES = 300
    private const val FILE_NAME = "capture.log"

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    @Volatile
    private var file: File? = null

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            file = f
            lines.clear()
            runCatching {
                if (f.exists()) {
                    f.readLines(Charsets.UTF_8).takeLast(MAX_LINES).forEach { lines.addLast(it) }
                }
            }
        }
    }

    /** 线程安全，可从通知监听的子线程直接调用 */
    fun add(tag: String, msg: String) {
        val line = "${fmt.format(Date())}  [$tag]  $msg"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            val f = file ?: return
            runCatching { f.writeText(lines.joinToString("\n"), Charsets.UTF_8) }
        }
    }

    fun dump(): String = synchronized(lock) {
        if (lines.isEmpty()) {
            "暂无采集记录。\n\n用微信 / 支付宝付一笔，或导入一份账单，再回来看看这里。"
        } else {
            lines.joinToString("\n")
        }
    }

    fun count(): Int = synchronized(lock) { lines.size }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            runCatching { file?.delete() }
        }
    }

    /** 把通知原文压成一行，避免日志被长文本撑爆 */
    fun brief(text: String, max: Int = 140): String {
        val t = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max) + "…"
    }
}
