package com.local.paysim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * 投递「支付结果通知」的唯一出口。
 *
 * 通知渠道一旦建好就不能改重要级别，所以这里固定 IMPORTANCE_HIGH，
 * 保证通知一定会被系统真正 post 出去（IMPORTANCE_NONE 的话监听端什么都收不到）。
 */
object SimNotify {

    const val CHANNEL_ID = "paysim"

    /** 输入文案统一用 Base64 传，绕开 adb / 控制台的中文编码坑 */
    const val EXTRA_TEXT_B64 = "text64"
    const val EXTRA_TITLE_B64 = "title64"

    fun isAlipay(context: Context): Boolean =
        context.packageName == "com.eg.android.AlipayGphone"

    fun defaultTitle(context: Context): String =
        if (isAlipay(context)) "支付宝" else "微信支付"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "支付结果通知", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "模拟微信 / 支付宝的付款结果通知" }
            )
        }
    }

    /**
     * @param tag 通知 tag，和 id 一起决定这条通知在系统里的身份
     *            （AutoLedger 判重时会拿 pkg|id|tag 作为键，所以要测「同一笔被反复改写」就固定 id/tag）
     */
    fun post(
        context: Context,
        title: String,
        text: String,
        subText: String = "",
        id: Int = 1001,
        tag: String? = null
    ) {
        ensureChannel(context)
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (subText.isNotBlank()) setSubText(subText) }
            .build()
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(tag, id, n)
    }
}
