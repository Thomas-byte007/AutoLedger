package com.local.autoledger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.autoledger.capture.PaymentAccessibilityService

/** 权限 / 系统开关的检查与跳转 */
object PermissionsUtil {

    /** 是否已授予「通知使用权」（自动记账的前提） */
    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun openNotificationAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    /**
     * 无障碍服务是否已在系统里开启。
     *
     * 注意：这里不能用 [PaymentAccessibilityService.running] 代替 —— 服务进程被回收后
     * 那个标记会变成 false，但系统设置里开关还开着，用户看到「未开启」会以为授权丢了。
     * 判断「用户有没有授权」只看系统设置；判断「此刻在不在干活」才看 running。
     */
    fun hasAccessibility(context: Context): Boolean {
        val raw = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        if (raw.isBlank()) return false
        val self = "${context.packageName}/${PaymentAccessibilityService::class.java.name}"
        return raw.split(':').any {
            it.equals(self, ignoreCase = true) || it.equals(context.packageName, ignoreCase = true)
        }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { openAppSettings(context) }
    }

    /** 是否已被排除在电池优化之外（省电策略放行） */
    fun isBatteryUnrestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /**
     * 系统是否对本应用施加了「后台限制」（部分 ROM 叫「后台活动管理 / 深度睡眠」）。
     *
     * 它和电池优化是**两套独立开关**：很多用户在「电池」里选了「无限制」，
     * 这里仍然返回 true，于是保活看起来完全没生效 —— 排查时两个都要看。
     */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching { am.isBackgroundRestricted }.getOrDefault(false)
    }

    /**
     * 跳到系统弹窗请用户放行电池优化。
     * 国产 ROM 的省电策略是「通知监听被杀」的头号元凶，这一步比任何保活都管用。
     */
    @SuppressLint("BatteryLife")
    fun requestBatteryUnrestricted(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openAppSettings(context) }
    }

    fun hasPostNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
