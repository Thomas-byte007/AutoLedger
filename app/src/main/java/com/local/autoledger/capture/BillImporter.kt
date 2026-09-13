package com.local.autoledger.capture

import android.content.Context
import android.net.Uri
import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import com.local.autoledger.data.LedgerRepository
import com.local.autoledger.data.Money
import com.local.autoledger.data.Txn
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 导入微信 / 支付宝导出的账单 CSV，把历史消费一次性补齐。
 *
 * 支付宝导出文件是 GBK，微信导出文件是带 BOM 的 UTF-8，两种都要能认。
 * 表头前面还有若干说明行，所以先定位表头再按列名取值，不依赖列顺序。
 */
object BillImporter {

    data class Result(
        val source: String,
        val added: Int,
        val skipped: Int,
        val total: Int,
        val error: String? = null
    )

    private val TIME_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss"
    )

    fun import(context: Context, uri: Uri): Result {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        } ?: return Result("未知", 0, 0, 0, "无法读取文件")

        val text = decode(bytes)
        if (text.isBlank()) return Result("未知", 0, 0, 0, "文件内容为空")

        val rows = parseCsv(text).filter { it.any { c -> c.isNotBlank() } }
        if (rows.isEmpty()) return Result("未知", 0, 0, 0, "没有解析到文本行")

        val source = detectSource(text)
        val headerIdx = rows.indexOfFirst { r ->
            r.any { it.trim() == "交易时间" } || r.any { it.trim() == "交易创建时间" }
        }
        if (headerIdx < 0) {
            return Result(source, 0, 0, 0, "没找到表头，请确认是微信/支付宝导出的账单 CSV")
        }
        val header = rows[headerIdx].map { it.trim() }
        val body = rows.drop(headerIdx + 1)

        val idxTime = col(header, "交易时间", "交易创建时间", "付款时间")
        val idxPeer = col(header, "交易对方", "对方")
        val idxItem = col(header, "商品", "商品名称")
        val idxDir = col(header, "收/支")
        val idxAmount = col(header, "金额(元)", "金额（元）", "金额")
        val idxPay = col(header, "支付方式", "付款方式")
        val idxStatus = col(header, "当前状态", "交易状态")
        val idxType = col(header, "交易类型", "类型")

        if (idxAmount < 0 || idxTime < 0) {
            return Result(source, 0, 0, 0, "账单列名与预期不符，缺少金额或时间列")
        }

        val list = ArrayList<Txn>()
        var consider = 0
        for (row in body) {
            fun at(i: Int): String = if (i in row.indices) row[i].trim() else ""

            val rawAmount = at(idxAmount)
            val cents = Money.parseToCents(rawAmount) ?: continue
            if (cents <= 0) continue

            // 收/支：微信写「支出/收入/不计收支/」，支付宝写「支出/收入/不计收支」
            val dirText = if (idxDir >= 0) at(idxDir) else ""
            val status = if (idxStatus >= 0) at(idxStatus) else ""
            val type = if (idxType >= 0) at(idxType) else ""
            val item = if (idxItem >= 0) at(idxItem) else ""
            val peer = if (idxPeer >= 0) at(idxPeer) else ""
            val pay = if (idxPay >= 0) at(idxPay) else ""

            if (status.contains("关闭") || status.contains("失败") || status.contains("已取消")) continue
            if (status.contains("已全额退款")) continue

            val direction = when {
                dirText.contains("支出") -> Direction.EXPENSE
                dirText.contains("收入") -> Direction.INCOME
                status.contains("退款") -> Direction.INCOME
                else -> continue      // 不计收支，跳过
            }

            val time = parseTime(at(idxTime)) ?: continue
            consider++

            val rawText = listOf(peer, item, type, status).filter { it.isNotBlank() }.joinToString(" ")
            val match = LedgerRepository.classify(direction, peer, rawText)
            // 明确是退款的，直接归到退款分类
            val categoryKey =
                if (status.contains("退款") || type.contains("退款")) "refund"
                else match.categoryKey

            list += Txn(
                amountCents = cents,
                direction = direction,
                categoryKey = categoryKey,
                merchant = peer.ifBlank { item },
                payMethod = pay,
                channel = Channel.IMPORT,
                occurredAt = time,
                autoCategory = match.auto || categoryKey == "refund",
                note = item.take(60),
                rawText = rawText
            )
        }

        if (list.isEmpty()) {
            return Result(source, 0, 0, consider, "没有可导入的有效流水（共识别 $consider 条）")
        }

        val (added, skipped) = LedgerRepository.insertBatch(list)
        CaptureLog.add("导入", "$source：新增 $added 笔，跳过重复 $skipped 笔")
        return Result(source, added, skipped, list.size)
    }

    // ------------------------------------------------------------- 内部

    private fun col(header: List<String>, vararg names: String): Int {
        names.forEach { n ->
            val i = header.indexOfFirst { it == n }
            if (i >= 0) return i
        }
        names.forEach { n ->
            val i = header.indexOfFirst { it.contains(n) }
            if (i >= 0) return i
        }
        return -1
    }

    private fun parseTime(s: String): Long? {
        if (s.isBlank()) return null
        val clean = s.replace("T", " ").trim()
        for (p in TIME_FORMATS) {
            try {
                val fmt = DateTimeFormatter.ofPattern(p)
                val dt = LocalDateTime.parse(clean, fmt)
                return com.local.autoledger.data.TimeFmt.toMillis(dt)
            } catch (_: Throwable) {
                // 试下一个格式
            }
        }
        return null
    }

    private fun detectSource(text: String): String = when {
        text.contains("微信支付账单明细") || text.contains("微信昵称") -> "微信账单"
        text.contains("支付宝交易记录明细") || text.contains("电子客户回单") -> "支付宝账单"
        text.contains("交易创建时间") -> "支付宝账单"
        else -> "账单文件"
    }

    /** UTF-8（含 BOM）优先，解不出来就按 GBK 处理——支付宝导出就是 GBK */
    internal fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Throwable) {
            runCatching { String(bytes, Charset.forName("GBK")) }
                .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        }
    }

    /** 支持引号包裹、逗号转义的标准 CSV 解析 */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        sb.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { row.add(sb.toString()); sb.setLength(0) }
                    '\r' -> Unit
                    '\n' -> {
                        row.add(sb.toString()); sb.setLength(0)
                        rows.add(row); row = ArrayList()
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) {
            row.add(sb.toString())
            rows.add(row)
        }
        return rows
    }
}
