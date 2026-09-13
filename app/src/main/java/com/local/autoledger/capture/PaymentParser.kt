package com.local.autoledger.capture

import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import com.local.autoledger.data.Money

/**
 * 从「通知文本 / 短信文本」里还原出一笔交易。
 *
 * 微信和支付宝的支付通知都是一句话，例如：
 *   微信支付: 已支付¥25.00        支付宝: 支付成功 25.00元
 *   微信支付: 微信红包 已存入零钱   支付宝: 收款成功 88.00元
 * 银行短信形态更多，但同样可以靠「金额 + 收支关键词」抽出来。
 *
 * 解析原则：宁可漏，不可错。金额和收支方向必须同时命中，否则一律丢弃。
 */
object PaymentParser {

    data class Parsed(
        val amountCents: Long,
        val direction: Direction,
        val merchant: String,
        val payMethod: String,
        val channel: Channel,
        val rawText: String
    )

    const val PKG_WECHAT = "com.tencent.mm"
    const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

    // ------------------------------------------------------------- 关键词

    private val REFUND_HINTS = listOf("退款", "退货", "退还", "已退回", "原路退回")
    private val INCOME_HINTS = listOf(
        "收款到账", "已收款", "收款成功", "收款金额", "收入", "入账", "转入", "汇入",
        "已存入零钱", "存入零钱", "收到红包", "红包已存入", "发来红包", "到账", "进账"
    )
    private val EXPENSE_HINTS = listOf(
        "支付成功", "付款成功", "已支付", "已付款", "支付了", "支出", "扣款", "消费",
        "付款", "支付", "已转账", "转出"
    )

    /** 明显不是记账的场景，直接排除 */
    private val NOISE_HINTS = listOf(
        "验证码", "校验码", "动态码", "登录", "安全提醒", "异常登录", "人脸识别",
        "账单已生成", "还款提醒", "逾期", "额度提升", "优惠券", "红包封面", "活动",
        "点击查看", "退订", "未接来电", "直播", "开票", "发票", "签到", "会员权益",
        "余额提醒", "理财推荐", "邀请", "升级提醒", "系统维护", "反诈"
    )

    // ------------------------------------------------------------- 正则

    private val AMOUNT_RE = Regex(
        """(?:￥|¥|RMB|rmb)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""" +
            """|([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元""" +
            """|金额[：:\s]*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    )

    private val MERCHANT_RES = listOf(
        Regex("""向\s*([^,，。；;、\s]{1,24}?)\s*(?:付款|支付|转账|发起)"""),
        Regex("""在\s*([^,，。；;、\s]{1,24}?)\s*(?:消费|支付|付款|下单)"""),
        Regex("""收款方[：:]\s*([^,，。；;、\s]{1,24})"""),
        Regex("""付款给\s*([^,，。；;、\s]{1,24})"""),
        Regex("""商户名称[：:]\s*([^,，。；;、\s]{1,32})"""),
        Regex("""商户[：:]\s*([^,，。；;、\s]{1,32})"""),
        Regex("""交易对方[：:]\s*([^,，。；;、\s]{1,32})"""),
        Regex("""对方户名[：:]\s*([^,，。；;、\s]{1,32})"""),
        Regex("""来自\s*([^,，。；;、\s]{1,24}?)\s*的""")
    )

    private val PAY_METHOD_RE = Regex(
        "(零钱通|微信零钱|零钱|余额宝|花呗|数字人民币|云闪付|借记卡|储蓄卡|信用卡|银行卡)" +
            "|[（(]尾号?(\\d{4})[)）]|尾号(\\d{4})"
    )

    private val CARD_TAIL_RE = Regex("""尾号(\d{4})""")

    // ------------------------------------------------------------- 通知

    /**
     * @param pkg    通知来源包名
     * @param text   标题 + 正文 + 大文本拼接后的完整内容
     */
    fun parseNotification(pkg: String, text: String): Parsed? {
        val channel = when (pkg) {
            PKG_WECHAT -> Channel.WECHAT
            PKG_ALIPAY -> Channel.ALIPAY
            else -> return null
        }
        val body = text.replace('\n', ' ').trim()
        if (body.isEmpty()) return null
        if (containsAny(body, NOISE_HINTS)) return null

        val amount = extractAmount(body) ?: return null
        val direction = decideDirection(body) ?: return null

        // 微信里「xx 给你转账」这类未收款提示不该记账
        if (body.contains("待收款") || body.contains("请你确认收款") || body.contains("已过期")) return null

        return Parsed(
            amountCents = amount,
            direction = direction,
            merchant = extractMerchant(body, channel),
            payMethod = extractPayMethod(body),
            channel = channel,
            rawText = body
        )
    }

    // ------------------------------------------------------------- 短信

    private val SMS_NOISE = listOf(
        "验证码", "校验码", "退订", "回复TD", "优惠", "活动", "理财", "贷款", "额度",
        "分期", "推荐", "积分兑换", "中奖", "免费领取"
    )

    /** 银行卡消费 / 入账短信 */
    fun parseSms(sender: String, body: String, timeMs: Long): Parsed? {
        val t = body.replace('\n', ' ').trim()
        if (t.isEmpty()) return null
        if (containsAny(t, SMS_NOISE)) return null

        val amount = extractAmount(t) ?: return null
        val direction = decideDirection(t) ?: return null

        val tail = CARD_TAIL_RE.find(t)?.groupValues?.get(1)
        val payMethod = if (tail != null) "银行卡(尾号$tail)" else extractPayMethod(t)

        // 商户名：优先取短信里明确给出的字段
        val merchant = MERCHANT_RES.asSequence()
            .mapNotNull { it.find(t)?.groupValues?.get(1) }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.contains("尾号") }
            ?: ""

        return Parsed(
            amountCents = amount,
            direction = direction,
            merchant = merchant,
            payMethod = payMethod,
            channel = Channel.SMS,
            rawText = "$sender | $t".trim()
        )
    }

    /** 该发件人是否像银行 / 卡组织 */
    fun looksLikeBankSender(sender: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        val s = sender.trim()
        val bankShortCodes = listOf(
            "95588", "95533", "95555", "95599", "95566", "95559", "95561", "95511",
            "95595", "95558", "95528", "95501", "95577", "95508", "95594", "95568",
            "95313", "95308"
        )
        if (bankShortCodes.any { s.endsWith(it) || s.startsWith(it) }) return true
        val bankNames = listOf(
            "银行", "工商", "建设", "农业", "招商", "交通银行", "中信", "浦发", "民生",
            "光大", "华夏", "兴业", "平安银行", "广发", "邮储", "银联", "信用卡"
        )
        if (bankNames.any { s.contains(it) }) return true
        return s.startsWith("106") && s.length <= 20 && s.contains("银行")
    }

    // ------------------------------------------------------------- 内部

    fun extractAmount(text: String): Long? {
        var first: Long? = null
        for (m in AMOUNT_RE.findAll(text)) {
            val raw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: continue
            val cents = Money.parseToCents(raw) ?: continue
            if (cents in 1..(999_999_999L)) {
                if (first == null) first = cents
            }
        }
        return first
    }

    fun decideDirection(text: String): Direction? = when {
        containsAny(text, REFUND_HINTS) -> Direction.INCOME
        containsAny(text, INCOME_HINTS) -> Direction.INCOME
        containsAny(text, EXPENSE_HINTS) -> Direction.EXPENSE
        else -> null
    }

    private fun extractMerchant(text: String, channel: Channel): String {
        for (re in MERCHANT_RES) {
            val v = re.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            if (v.isBlank()) continue
            if (v.length > 24) continue
            if (v == "你" || v == "我" || v == "对方") continue
            return v
        }
        // 微信红包 / 转账类，用来源兜底
        if (text.contains("红包")) return "红包"
        if (text.contains("转账")) return "转账"
        return ""
    }

    private fun extractPayMethod(text: String): String {
        val m = PAY_METHOD_RE.find(text) ?: return ""
        val groups = m.groupValues
        for (i in 1 until groups.size) {
            if (groups[i].isNotBlank()) return groups[i]
        }
        return ""
    }

    private fun containsAny(text: String, keys: List<String>): Boolean =
        keys.any { text.contains(it) }
}
