package com.local.paysim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log

/**
 * 收到 adb 广播就往系统里投一条「假支付通知」。
 *
 * 用法（示例见 tools/emulator-e2e.ps1）：
 *   adb shell am broadcast --include-stopped-packages \
 *     -n com.tencent.mm/com.local.paysim.SimReceiver \
 *     --es title64 <base64> --es text64 <base64> --ei id 1001
 *
 * 文案走 Base64 是因为 adb shell 在 Windows 控制台里传中文很容易乱码，
 * 乱码会直接改变解析结果，那样测的就不是真实逻辑了。
 */
class SimReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = decode(intent.getStringExtra(SimNotify.EXTRA_TITLE_B64))
            .ifBlank { SimNotify.defaultTitle(context) }
        val text = decode(intent.getStringExtra(SimNotify.EXTRA_TEXT_B64))
        if (text.isBlank()) {
            Log.w(TAG, "缺少 text64，忽略这次广播")
            return
        }
        val subText = decode(intent.getStringExtra("subtext64"))
        val startId = intent.getIntExtra("id", 1001)
        val count = intent.getIntExtra("count", 1).coerceIn(1, 20)
        val tag = intent.getStringExtra("tag")

        for (i in 0 until count) {
            SimNotify.post(
                context = context,
                title = title,
                text = text,
                subText = subText,
                id = startId + i,
                tag = tag
            )
        }
        Log.i(TAG, "已投递 $count 条通知：$title | $text")
    }

    private fun decode(b64: String?): String {
        if (b64.isNullOrBlank()) return ""
        return runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrDefault("")
    }

    private companion object {
        const val TAG = "PaySim"
    }
}
