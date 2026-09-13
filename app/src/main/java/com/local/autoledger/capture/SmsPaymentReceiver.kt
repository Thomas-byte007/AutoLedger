package com.local.autoledger.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.local.autoledger.data.Prefs
import java.util.concurrent.Executors

/**
 * 补充来源：银行卡消费 / 入账短信。
 * 支付宝、微信本身未必弹出通知（比如用户关了通知），但银行短信几乎一定有。
 */
class SmsPaymentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.captureEnabled || !Prefs.smsEnabled) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val pending = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            try {
                // 同一条长短信可能被拆成多段，按发送人合并
                messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, parts) ->
                    if (!PaymentParser.looksLikeBankSender(sender)) return@forEach
                    val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
                    val time = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                    val parsed = PaymentParser.parseSms(sender, body, time) ?: return@forEach
                    CaptureSink.store(appContext, parsed, time, dedupWindowSec = 180)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "短信解析失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "LedgerSms"
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ledger-sms").apply { isDaemon = true }
        }
    }
}
