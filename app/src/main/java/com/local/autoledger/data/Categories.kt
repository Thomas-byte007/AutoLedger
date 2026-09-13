package com.local.autoledger.data

import com.local.autoledger.R

/**
 * 分类字典。key 一旦落库就不再变更，展示名 / 颜色可以随意调整。
 */
object Categories {

    val dining = Category("dining", "餐饮", "🍜", R.color.cat_dining, Direction.EXPENSE)
    val transport = Category("transport", "交通", "🚌", R.color.cat_transport, Direction.EXPENSE)
    val shopping = Category("shopping", "购物", "🛍", R.color.cat_shopping, Direction.EXPENSE)
    val grocery = Category("grocery", "日用百货", "🧺", R.color.cat_grocery, Direction.EXPENSE)
    val housing = Category("housing", "居住", "🏠", R.color.cat_housing, Direction.EXPENSE)
    val entertainment = Category("entertainment", "娱乐", "🎮", R.color.cat_entertainment, Direction.EXPENSE)
    val medical = Category("medical", "医疗", "🏥", R.color.cat_medical, Direction.EXPENSE)
    val education = Category("education", "教育", "📚", R.color.cat_education, Direction.EXPENSE)
    val travel = Category("travel", "旅行", "✈️", R.color.cat_travel, Direction.EXPENSE)
    val transfer = Category("transfer", "转账红包", "💸", R.color.cat_transfer, Direction.EXPENSE)
    val otherExpense = Category("other", "其他", "✨", R.color.cat_other, Direction.EXPENSE)

    val salary = Category("salary", "工资报酬", "💰", R.color.cat_salary, Direction.INCOME)
    val refund = Category("refund", "退款", "↩️", R.color.cat_refund, Direction.INCOME)
    val otherIncome = Category("other_income", "其他收入", "🪙", R.color.cat_other_income, Direction.INCOME)

    val ALL: List<Category> = listOf(
        dining, transport, shopping, grocery, housing, entertainment, medical,
        education, travel, transfer, otherExpense,
        salary, refund, otherIncome
    )

    val EXPENSES: List<Category> = ALL.filter { it.direction == Direction.EXPENSE }
    val INCOMES: List<Category> = ALL.filter { it.direction == Direction.INCOME }

    fun byKey(key: String?): Category =
        ALL.firstOrNull { it.key == key }
            ?: if (key == otherIncome.key) otherIncome else otherExpense

    fun byKey(key: String?, direction: Direction): Category =
        ALL.firstOrNull { it.key == key && it.direction == direction }
            ?: fallbackOf(direction)

    fun fallbackOf(direction: Direction): Category =
        if (direction == Direction.INCOME) otherIncome else otherExpense

    /** 内置关键词规则：key 分类，value 关键词表 */
    private val seed: List<Pair<Category, List<String>>> = listOf(
        dining to listOf(
            "餐", "饭", "食堂", "外卖", "美团", "饿了么", "饿了", "肯德基", "麦当劳", "KFC", "星巴克",
            "瑞幸", "luckin", "咖啡", "奶茶", "喜茶", "蜜雪", "库迪", "茶百道", "古茗", "沪上阿姨",
            "烧烤", "火锅", "小吃", "面馆", "米线", "沙县", "兰州", "汉堡", "披萨", "寿司", "必胜客",
            "海底捞", "西贝", "吉野家", "真功夫", "老乡鸡", "面", "粉", "粥", "早餐", "午餐", "晚餐",
            "食品", "零食", "烘焙", "面包", "蛋糕", "甜点", "酒", "啤酒", "饭店", "餐馆", "酒店餐饮"
        ),
        transport to listOf(
            "地铁", "公交", "轨道交通", "出行", "滴滴", "打车", "出租车", "网约车", "高德打车",
            "高铁", "火车", "12306", "航空", "机票", "加油", "中国石化", "中国石油", "壳牌",
            "停车", "泊车", "高速", "ETC", "单车", "哈啰", "青桔", "美团单车", "摩拜", "曹操出行",
            "首汽", "顺风车", "货拉拉", "充电桩", "车费", "燃油", "养车", "汽车服务", "美团出行"
        ),
        shopping to listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "商场", "购物", "服饰", "优衣库", "耐克",
            "阿迪", "小米", "华为", "苹果", "Apple", "天猫超市", "苏宁", "国美", "得物", "丝芙兰",
            "屈臣氏", "名创优品", "无印良品", "MUJI", "手机", "数码", "家电", "化妆品", "护肤",
            "鞋", "衣", "箱包", "配饰", "珠宝", "眼镜", "淘宝网", "京东商城", "网易严选", "抖音电商", "快手电商"
        ),
        grocery to listOf(
            "超市", "便利店", "永辉", "沃尔玛", "家乐福", "盒马", "大润发", "物美", "华润万家",
            "菜市场", "生鲜", "日用", "山姆", "罗森", "全家", "7-11", "711", "美宜佳", "便利蜂",
            "水果", "蔬菜", "粮油", "副食", "洗护", "清洁", "纸巾", "五金"
        ),
        housing to listOf(
            "房租", "租金", "物业", "水费", "电费", "燃气", "天然气", "暖气", "宽带", "话费",
            "中国移动", "中国联通", "中国电信", "垃圾处理", "家政", "维修", "保洁", "租房",
            "中介费", "房贷", "装修", "家具", "宜家", "家电维修", "水电费", "取暖"
        ),
        entertainment to listOf(
            "电影", "影院", "万达", "游戏", "腾讯视频", "爱奇艺", "优酷", "芒果TV", "网易云音乐",
            "QQ音乐", "音乐", "网吧", "KTV", "演唱会", "话剧", "剧本杀", "密室", "会员", "订阅",
            "Netflix", "Steam", "哔哩哔哩", "B站", "直播", "打赏", "彩票", "健身", "瑜伽", "运动",
            "游泳", "球馆", "桌游", "乐园", "游乐"
        ),
        medical to listOf(
            "医院", "医药", "药房", "大药房", "药店", "诊所", "门诊", "体检", "挂号", "口腔",
            "牙科", "眼科", "中医", "医疗", "卫生院", "健康", "疫苗", "检验", "康复", "妇产"
        ),
        education to listOf(
            "学费", "培训", "教育", "书店", "图书", "当当", "课程", "网课", "考试", "报名费",
            "文具", "驾校", "幼儿园", "学校", "辅导", "学而思", "新东方", "知网", "文献", "考证"
        ),
        travel to listOf(
            "酒店", "民宿", "携程", "去哪儿", "飞猪", "景区", "旅游", "度假", "旅行", "旅馆",
            "客栈", "亚朵", "汉庭", "如家", "希尔顿", "洲际", "airbnb", "门票", "导游", "跟团",
            "签证", "度假村", "青旅"
        ),
        transfer to listOf(
            "转账", "红包", "亲属卡", "还款", "信用卡还款", "代付", "AA", "收款方", "给朋友",
            "群收款", "还花呗", "花呗还款", "借款", "还钱"
        ),
        salary to listOf(
            "工资", "薪资", "薪酬", "奖金", "绩效", "补贴", "报销", "劳务", "年终奖", "薪",
            "工资代发", "劳务费", "提成", "津贴"
        ),
        refund to listOf(
            "退款", "退货", "退还", "返现", "退款成功", "原路退回", "撤销", "赔付"
        ),
        otherIncome to listOf(
            "收款", "转入", "利息", "理财收益", "分红", "收到", "到账", "存入", "红包收", "转入收款"
        )
    )

    /** 生成内置规则列表（priority 用关键词长度，长词优先匹配） */
    fun builtinRules(): List<Rule> = seed.flatMap { (cat, words) ->
        words.map { w ->
            Rule(
                keyword = w,
                categoryKey = cat.key,
                direction = cat.direction,
                priority = w.length * 10,
                builtin = true
            )
        }
    }
}
