package com.local.autoledger.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.autoledger.R
import com.local.autoledger.data.Categories
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Rule
import com.local.autoledger.databinding.ActivityRulesBinding
import com.local.autoledger.databinding.DialogAddRuleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分类规则管理：查看 / 新增 / 删除关键词规则 */
class RulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRulesBinding
    private val adapter = RuleAdapter { rule -> confirmDelete(rule) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.fab.setOnClickListener { addRule() }

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) { LedgerRepository.rules() }
            adapter.submit(rules)
        }
    }

    private fun addRule() {
        val dialogBinding = DialogAddRuleBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle("新增分类规则")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("下一步") { _, _ ->
                val keyword = dialogBinding.etKeyword.text?.toString()?.trim().orEmpty()
                if (keyword.isEmpty()) {
                    Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                chooseCategory(keyword)
            }
            .show()
    }

    private fun chooseCategory(keyword: String) {
        val all = Categories.ALL
        val labels = all.map {
            "${it.label}（${if (it.direction == Direction.EXPENSE) "支出" else "收入"}）"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("「$keyword」归入哪个分类？")
            .setItems(labels) { _, which ->
                val cat = all[which]
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        LedgerRepository.addRule(keyword, cat.key, cat.direction)
                    }
                    load()
                    Toast.makeText(
                        this@RulesActivity,
                        "已添加：$keyword → ${cat.label}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun confirmDelete(rule: Rule) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除规则？")
            .setMessage("删除后「${rule.keyword}」将不再自动归入该分类，已有的记录不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { LedgerRepository.deleteRule(rule.id) }
                    load()
                }
            }
            .show()
    }
}
