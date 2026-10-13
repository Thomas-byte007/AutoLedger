package com.local.autoledger.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.LedgerBus
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.Prefs
import com.local.autoledger.data.Txn
import com.local.autoledger.ui.MainActivity

/**
 * 把解析结果落库的唯一出口。支付通知与无障碍辅助记账都走这里，
 * 保证分类逻辑与去重策略只有一份。
 */
object CaptureSink {

    private const val CHANNEL_ID = "capture"
    private var channelReady = false

    /**
     * @return 新记录 id；判定为重复时返回 -1
     */
    fun store(
        context: Context,
        parsed: PaymentParser.Parsed,
        occurredAt: Long,
        dedupWindowSec: Long = 240
    ): Long {
        val match = LedgerRepository.classify(parsed.direction, parsed.merchant, parsed.rawText)
        val txn = Txn(
            amountCents = parsed.amountCents,
            direction = parsed.direction,
            categoryKey = match.categoryKey,
            merchant = parsed.merchant,
            payMethod = parsed.payMethod,
            channel = parsed.channel,
            occurredAt = occurredAt,
            autoCategory = match.auto,
            rawText = parsed.rawText
        )
        val result = LedgerRepository.insertDetailed(txn, dedupWindowSec)
        if (result.id > 0) {
            LedgerBus.notifyChanged()
            val label = Categories.byKey(txn.categoryKey, txn.direction).label
            val merchant = if (txn.merchant.isNotBlank()) " · ${txn.merchant}" else ""
            CaptureLog.add(
                "记账",
                "${parsed.channel.label} ${Money.signed(txn.amountCents, txn.direction)} · $label$merchant"
            )
            notifyCaptured(context, txn)
        } else {
            CaptureLog.add("跳过", "${result.reason}｜${CaptureLog.brief(parsed.rawText)}")
        }
        return result.id
    }

    private fun ensureChannel(context: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "记账提醒",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "自动记录一笔账目后的轻提示" }
                mgr.createNotificationChannel(ch)
            }
        }
        channelReady = true
    }

    private fun notifyCaptured(context: Context, txn: Txn) {
        if (!Prefs.captureEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)

        val cat = Categories.byKey(txn.categoryKey)
        val title = "已自动记账 · ${cat.label}"
        val text = buildString {
            append(Money.signed(txn.amountCents, txn.direction))
            append("  ")
            append(txn.merchant.ifBlank { txn.channel.label })
            if (txn.isPending) append("  · 待分类")
        }
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ledger)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(txn.hashCode(), n) }
    }
}
