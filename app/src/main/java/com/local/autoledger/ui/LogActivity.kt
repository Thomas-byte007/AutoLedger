package com.local.autoledger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.autoledger.capture.CaptureLog
import com.local.autoledger.databinding.ActivityLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 采集日志。真机上排查「为什么这笔没记上」全靠它：
 * 每条付款通知收到了什么原文、是被识别了、被跳过了，还是压根没解析出金额。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("采集日志", binding.tvLog.text))
            toast("已复制到剪贴板")
        }

        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清空采集日志？")
                .setMessage("只清日志，已经记下的账目不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { CaptureLog.clear() }
                        load()
                    }
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { CaptureLog.dump() }
            binding.tvLog.text = text
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
