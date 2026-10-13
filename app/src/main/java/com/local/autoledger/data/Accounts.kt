package com.local.autoledger.data

/**
 * 账户（付款方式）。
 *
 * 不新增数据库字段：账目表里早就有一列 `pay_method`，
 * 通知里抓到的「零钱」「招商银行(1234)」本来就存在那里，
 * 手动记账时把它当作「账户」来选即可。
 */
object Accounts {

    data class Account(val key: String, val label: String)

    val cash = Account("cash", "现金")
    val wechat = Account("wechat", "微信零钱")
    val alipay = Account("alipay", "支付宝余额")
    val debit = Account("debit", "储蓄卡")
    val credit = Account("credit", "信用卡")
    val other = Account("other", "其他账户")

    val ALL: List<Account> = listOf(cash, wechat, alipay, debit, credit, other)

    val DEFAULT: Account = cash

    /** 展示用：优先按已知账户反查，认不出来就原样显示通知里抓到的文本 */
    fun labelOf(payMethod: String?): String =
        payMethod?.takeIf { it.isNotBlank() } ?: DEFAULT.label

    fun isKnown(payMethod: String?): Boolean = ALL.any { it.label == payMethod }
}
