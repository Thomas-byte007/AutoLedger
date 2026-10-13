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

    /** 只认这两个来源；其余包名的通知 / 页面一律不看 */
    fun channelOf(pkg: String): Channel? = when (pkg) {
        PKG_WECHAT -> Channel.WECHAT
        PKG_ALIPAY -> Channel.ALIPAY
        else -> null
    }

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

    /**
     * 「钱还没到 / 这笔已经黄了」的状态。
     *
     * 通知和支付页都会出现：微信里「xx 给你转账」这类未收款提示同样带金额、同样写着
     * 「转账」，照记就会虚增收入，所以放在两层闸门共用的那一层里挡掉。
     */
    private val UNFINISHED_HINTS = listOf(
        "待收款", "请你确认收款", "已过期", "已失效", "等待对方确认", "等待付款"
    )

    /** 页面上的中间态 / 诱导性文案，命中就不记 */
    private val SCREEN_NOISE = listOf(
        "确认付款", "请输入支付密码", "正在支付", "支付中", "付款方式选择",
        "立即付款", "去支付", "免密支付开通", "向你付款", "向你转账"
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

    /**
     * 「被动进账播报」，不计入日常收支。
     *
     * 理财产品的收益 / 利息是每天**凌晨**发放的，支付宝和微信都会推一条
     * 「昨日收益已到账 1.23 元」之类的通知。它带金额，又带着「到账 / 已存入」这类字样，
     * 于是被当成一笔收入记下来 —— 但它既不是消费也不是真实进账，更没有交易对方，
     * 在账本里就是一条凭空多出来、没有来源的收入。
     *
     * 这里只认「收益 / 利息」这类语义明确的词。**不能**把「余额宝 / 零钱通 / 花呗」
     * 当噪声：它们是合法的扣款渠道（见 [PAY_METHOD_RE]），一放进去正常付款就全丢了。
     */
    private val PASSIVE_INCOME_HINTS = listOf(
        "收益", "利息", "分红", "结息", "派息", "理财通收益", "余额增值"
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
        val channel = channelOf(pkg) ?: return null
        val body = flatten(text)
        if (body.isEmpty()) return null
        if (skipReason(pkg, body) != null) return null

        val amount = extractAmount(body) ?: return null
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

    /**
     * 这条通知为什么不记账（null = 它有可能是一笔账）。
     *
     * 只给日志用：采集日志里写清楚「为什么跳过」，比笼统一句「未识别」有用得多 ——
     * 真机上排查时，最怕的就是分不清「文案没覆盖」和「本来就不该记」。
     */
    fun skipReason(pkg: String, text: String): String? {
        if (channelOf(pkg) == null) return "不是微信 / 支付宝的通知"
        return level1SkipReason(flatten(text))
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
        val channel = channelOf(pkg) ?: return null
        val body = flatten(text)
        if (body.isEmpty() || body.length > SCREEN_MAX_CHARS) return null
        if (level1SkipReason(body) != null) return null
        if (containsAny(body, SCREEN_NOISE)) return null
        if (!containsAny(body, SCREEN_HINTS)) return null

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

    /**
     * 这是个「已经付完了」的页面，但金额还没出现。
     *
     * 支付宝「碰一下」的支付成功页，金额是**从无到有做动画渲染**出来的：窗口刚出现、
     * 窗口事件打过来的时候，金额对应的节点还不在无障碍树里，[parsePaymentScreen]
     * 自然只能返回 null。这种「缺的只是时间」的页面不该被当成解析失败，
     * 调用方看到它就稍后重扫一次（见 [com.local.autoledger.capture.PaymentAccessibilityService]）。
     */
    fun incompleteSuccessPage(pkg: String, text: String): Boolean {
        if (channelOf(pkg) == null) return false
        val body = flatten(text)
        if (body.isEmpty() || body.length > SCREEN_MAX_CHARS) return false
        if (level1SkipReason(body) != null) return false
        if (containsAny(body, SCREEN_NOISE)) return false
        if (!containsAny(body, SCREEN_HINTS)) return false
        // 命中「支付成功」却连一个像金额的数字都没有 —— 就是金额还没渲染出来的那一帧
        return extractAmount(body) == null && extractScreenAmount(body) == null
    }

    private fun extractScreenAmount(text: String): Long? {
        val m = SCREEN_AMOUNT_RE.find(text) ?: return null
        return Money.parseToCents(m.groupValues[1])
    }

    // ------------------------------------------------------------- 内部

    /** 通知正文、页面文字都要先压成一行：判重、日志、正则都按单行处理 */
    private fun flatten(text: String): String =
        text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    /**
     * 通知与页面共用的第一层闸门，返回 null 表示「有记账的可能」。
     *
     * 通知和支付页的脏法不一样，但「理财收益播报」「营销提醒」这类东西两头都不该记，
     * 所以放在一处判，避免两边的名单越长越不一致。
     */
    private fun level1SkipReason(body: String): String? {
        if (body.isEmpty()) return "内容为空"
        if (containsAny(body, PASSIVE_INCOME_HINTS)) return "理财收益 / 利息类播报，不属于日常收支"
        if (containsAny(body, NOISE_HINTS)) return "与收支无关的提醒"
        if (containsAny(body, UNFINISHED_HINTS)) return "钱还没到账或这笔已经失效"
        return null
    }

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
