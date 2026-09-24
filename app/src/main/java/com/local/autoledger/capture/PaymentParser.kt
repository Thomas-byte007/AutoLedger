package com.local.autoledger.capture

import com.local.autoledger.data.Channel
import com.local.autoledger.data.Direction
import com.local.autoledger.data.Money

/**
 * 从一条支付通知 / 支付结果页文本里还原出一笔交易。
 *
 * 微信和支付宝的支付通知都是一句话，例如：
 *   微信支付: 已支付¥25.00        支付宝: 支付成功 25.00元
 *   微信支付: 微信红包 已存入零钱   支付宝: 收款成功 88.00元
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

    /**
     * 支付成功页上才会出现的字样。
     *
     * 只认「已经付完了」的确定性表达，不认「正在支付 / 等待付款」这类中间态 ——
     * 中间态页面同样有金额，但那笔钱还没出去。
     */
    private val SCREEN_HINTS = listOf(
        "支付成功", "付款成功", "交易成功", "已付款", "支付完成", "付款完成",
        "已完成支付", "收款成功", "退款成功", "已存入零钱", "已存入余额"
    )

    /** 页面上的中间态 / 诱导性文案，命中就不记 */
    private val SCREEN_NOISE = listOf(
        "等待对方确认", "等待付款", "确认付款", "请输入支付密码", "正在支付",
        "支付中", "付款方式选择", "立即付款", "去支付", "免密支付开通",
        "向你付款", "向你转账", "请你确认收款", "已过期", "待收款"
    )

    /** 支付结果页只有几个字；超过这个长度基本就是账单列表页了 */
    private const val SCREEN_MAX_CHARS = 260

    /** 页面兜底金额：必须带两位小数，避免把日期、件数之类的数字认成钱 */
    private val SCREEN_AMOUNT_RE = Regex("""-?\s*([0-9][0-9,]*\.[0-9]{2})""")

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

    // ------------------------------------------------------------- 支付成功页（无障碍）

    /**
     * 无障碍链路专用：读微信 / 支付宝「支付成功」页。
     *
     * 这条链路解决的是「支付宝压根不弹通知」的场景 —— 通知监听再稳也拿不到一条
     * 系统里根本不存在的通知，而支付成功页是必然会出现的。
     *
     * 页面文本比通知脏得多（一屏上所有的字都会进来），所以门槛要比通知高：
     *  1. 必须命中明确的「成功」字样，光有金额不行；
     *  2. 文本过长的一律丢 —— 账单列表页同样写着「交易成功」并带着一堆金额，
     *     真正的结果页只有寥寥几个字，用长度把列表页挡在门外；
     *  3. 方向同样必须能判定，判不出来就不记。
     *
     * 原则不变：宁可漏，不可错。
     */
    fun parsePaymentScreen(pkg: String, text: String): Parsed? {
        val channel = when (pkg) {
            PKG_WECHAT -> Channel.WECHAT
            PKG_ALIPAY -> Channel.ALIPAY
            else -> return null
        }
        val body = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (body.isEmpty()) return null
        if (body.length > SCREEN_MAX_CHARS) return null
        if (!containsAny(body, SCREEN_HINTS)) return null
        if (containsAny(body, NOISE_HINTS) || containsAny(body, SCREEN_NOISE)) return null

        // 支付成功页上的金额常常不带「¥ / 元」，就是一个 -12.50。
        // 兜底正则要求带两位小数，正是为了不把日期、数量之类的数字当成钱。
        val amount = extractAmount(body) ?: extractScreenAmount(body) ?: return null
        val direction = decideDirection(body) ?: return null

        return Parsed(
            amountCents = amount,
            direction = direction,
            merchant = extractMerchant(body, channel),
            payMethod = extractPayMethod(body),
            channel = channel,
            rawText = body
        )
    }

    private fun extractScreenAmount(text: String): Long? {
        val m = SCREEN_AMOUNT_RE.find(text) ?: return null
        return Money.parseToCents(m.groupValues[1])
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
