package com.local.paysim

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 只是个「活着的证据」：装完先启动一次，App 就不再处于 stopped 状态，
 * 广播一定能收到，通知渠道也顺带建好了。
 */
class SimActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SimNotify.ensureChannel(this)

        val who = if (SimNotify.isAlipay(this)) "支付宝" else "微信"
        val body = buildString {
            append("支付通知模拟器\n\n")
            append("本机包名：\n${packageName}\n\n")
            append("作用：伪装成「$who」，向系统投递假的支付结果通知，\n")
            append("用来在模拟器上端到端验证 AutoLedger 的自动记账。\n\n")
            append("不需要点任何按钮，由 tools/emulator-e2e.ps1 用 adb 广播驱动。")
        }

        val tv = TextView(this).apply {
            text = body
            textSize = 15f
            setTextColor(Color.parseColor("#1F2430"))
            setLineSpacing(6f, 1f)
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FB"))
            gravity = Gravity.CENTER
            addView(tv, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
    }
}
