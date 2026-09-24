package com.local.autoledger

import android.content.Context
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.local.autoledger.capture.PaymentAccessibilityService
import com.local.autoledger.ui.AccessibilityPrompt
import com.local.autoledger.ui.PermissionsUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * 无障碍引导弹窗的触发条件。
 *
 * 这里锁的是「什么时候该弹」：弹早了烦人，弹晚了等于没提示，
 * 而「同一会话只弹一次」是用户明确要的底线，写漏了就会变成每次回前台都被拦一下。
 */
@RunWith(RobolectricTestRunner::class)
class AccessibilityPromptTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AccessibilityPrompt.resetForTest()
        // 默认当作「系统里没开」
        writeEnabledServices("")
    }

    @Test
    fun 没开启时应该弹() {
        assertTrue(AccessibilityPrompt.shouldShow(granted = false, shown = false))
        assertTrue(AccessibilityPrompt.maybeShow(activity()))
    }

    @Test
    fun 已开启时一次都不弹() {
        writeEnabledServices("${context.packageName}/${PaymentAccessibilityService::class.java.name}")
        assertTrue(PermissionsUtil.hasAccessibility(context))

        assertFalse(AccessibilityPrompt.shouldShow(granted = true, shown = false))
        assertFalse(AccessibilityPrompt.maybeShow(activity()))
    }

    @Test
    fun 同一会话内只弹一次() {
        val act = activity()
        assertTrue(AccessibilityPrompt.maybeShow(act))
        // 第二次：标记已经置上，哪怕系统里还是没开也不能再弹
        assertFalse(AccessibilityPrompt.maybeShow(act))
        assertFalse(AccessibilityPrompt.shouldShow(granted = false, shown = true))
    }

    @Test
    fun 文案里带小米开启路径() {
        val path = context.getString(R.string.access_prompt_path)
        assertTrue("路径要以「设置」起头：$path", path.startsWith("设置"))
        listOf("无障碍功能", "已安装的服务", "随手记", "打开开关并确认").forEach {
            assertTrue("路径里缺了「$it」：$path", path.contains(it))
        }
    }

    /** 模拟系统设置里的「已开启的无障碍服务」列表 */
    private fun writeEnabledServices(value: String) {
        Settings.Secure.putString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value
        )
    }

    private fun activity(): AppCompatActivity =
        Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
}
