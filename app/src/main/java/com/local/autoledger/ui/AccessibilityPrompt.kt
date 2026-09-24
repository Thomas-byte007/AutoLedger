package com.local.autoledger.ui

import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.autoledger.R
import com.local.autoledger.data.Prefs
import com.local.autoledger.databinding.DialogAccessPromptBinding

/**
 * 启动时的「无障碍服务未开启」引导弹窗。
 *
 * 它只解决一件事：很多用户不知道「支付没弹通知」这一笔为什么没记上，
 * 而补上它需要在系统设置里亲手开一个开关，App 自己开不了。
 * 所以启动后查一次系统状态，没开就把人送到设置页去。
 *
 * 三条硬性边界（改代码时别放宽）：
 *  1. 只在「系统里确实没开」时弹，已开启一次都不弹；
 *  2. 同一会话（进程存活期间）只弹一次，避免反复打断 —— 标记是内存里的，
 *     App 被杀后自然重置，下次冷启动才会再问；
 *  3. 不做「不再提示」之类的永久关闭：用户随时可以在「我的 → 辅助记账」里自己关，
 *     但弹窗本身不替用户做决定。
 *
 * 「确定」只负责把人送过去，不写任何状态；等他从设置页回来，
 * 由 [onResume] 看结果再决定要不要把 App 内的辅助记账开关补上。
 */
object AccessibilityPrompt {

    /** 本次会话已经弹过了（进程级标记，进程被杀即重置） */
    @Volatile
    private var shownThisSession: Boolean = false

    /** 用户是从本弹窗点「确定」跳去设置页的；回来时才知道要不要自动开 App 内开关 */
    @Volatile
    private var awaitingResult: Boolean = false

    /** 当前正在显示的弹窗（同一时刻只可能有一个；Activity 重建时用来先关掉旧的） */
    private var dialog: AlertDialog? = null

    /**
     * 纯判定：系统没开 + 本次会话还没弹过 → 才弹。
     *
     * 单独抽出来是因为这条规则一旦写反，用户要么被弹窗烦死，要么永远不知道漏记的原因，
     * 单测直接锁住（[AccessibilityPromptTest]）。
     */
    @VisibleForTesting
    fun shouldShow(granted: Boolean, shown: Boolean): Boolean = !granted && !shown

    /**
     * 应用启动后调用一次（MainActivity.onCreate 里延迟一小会儿调）。
     * @return 是否真的弹出了弹窗
     */
    fun maybeShow(activity: AppCompatActivity): Boolean {
        // 系统授权的判定只看「设置里开没开」，不能看服务进程活着没 ——
        // 服务被系统回收不代表用户撤回了授权，详见 PermissionsUtil.hasAccessibility
        if (!shouldShow(PermissionsUtil.hasAccessibility(activity), shownThisSession)) return false
        shownThisSession = true
        showDialog(activity)
        return true
    }

    /**
     * 回到前台时调用：只处理「从本弹窗跳过去、并且真的开了」这一种情况。
     *
     * 系统开关和 App 内开关是两回事，两个都满足服务才干活。用户既然是从这个弹窗
     * 主动去开的，说明他就是想要这个功能，这里顺手把 App 内开关补上；
     * 但若他没开，就什么都不做 —— 不替用户开，只替用户省一步。
     */
    fun onResume(activity: AppCompatActivity) {
        if (!awaitingResult) return
        awaitingResult = false
        if (!PermissionsUtil.hasAccessibility(activity)) return
        if (Prefs.accessEnabled) return
        Prefs.accessEnabled = true
        Toast.makeText(activity, R.string.access_prompt_done, Toast.LENGTH_SHORT).show()
    }

    private fun showDialog(activity: AppCompatActivity) {
        val binding = DialogAccessPromptBinding.inflate(activity.layoutInflater)
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            // 「取消」：只关掉本次弹窗，不改任何状态（传 null 即默认 dismiss）
            .setNegativeButton(R.string.access_prompt_cancel, null)
            // 「确定」：跳到系统无障碍设置页；开不开由用户在系统里决定
            .setPositiveButton(R.string.access_prompt_ok) { _, _ ->
                awaitingResult = true
                PermissionsUtil.openAccessibilitySettings(activity)
            }
            .setCancelable(true)
            .create()
            .also { it.show() }
    }

    /** 单测专用：清掉会话内状态 */
    @VisibleForTesting
    fun resetForTest() {
        shownThisSession = false
        awaitingResult = false
        dialog = null
    }
}
