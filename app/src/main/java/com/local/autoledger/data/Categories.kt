package com.local.autoledger.data

import com.local.autoledger.R

/**
 * 分类字典：两级结构。
 *
 * - **一级类** [CategoryGroup]：饮食 / 出行 / 日用 / 购物 / 学校 / 居住 / 医疗 / 通讯 / 爱好 / 其他 / 收入。
 *   报表页的「支出分布」就是按一级类排名的。
 * - **二级类** [Category]：记账时真正选中的那一项，例如「饮食」下的「零食」「饮料」。
 *   账目表里存的 `category_key` 永远是二级类的 key。
 *
 * key 一旦落库就不再变更，展示名 / 颜色 / 图标可以随意调整。
 *
 * 老版本（v1.0.2 及以前）库里只有 14 个扁平的分类，它们的 key 全部保留下来，
 * 只是被挂到了合适的一级类下面，所以老数据不需要迁移也能正确归组。
 *
 * 图标：一级类 / 二级类的图标名与各自的 key 同名，真正画出来的矢量图标由
 * [CategoryIconCatalog] 解析；用户改图标时存的是图标名，不是资源 id。
 */
object Categories {

    // ------------------------------------------------------------------ 一级类

    val gDining = CategoryGroup("g_dining", "饮食", R.color.cat_dining, Direction.EXPENSE)
    val gTransport = CategoryGroup("g_transport", "出行", R.color.cat_transport, Direction.EXPENSE)
    val gDaily = CategoryGroup("g_daily", "日用", R.color.cat_grocery, Direction.EXPENSE)
    val gShopping = CategoryGroup("g_shopping", "购物", R.color.cat_shopping, Direction.EXPENSE)
    val gSchool = CategoryGroup("g_school", "学校", R.color.cat_education, Direction.EXPENSE)
    val gHome = CategoryGroup("g_home", "居住", R.color.cat_housing, Direction.EXPENSE)
    val gHealth = CategoryGroup("g_health", "医疗", R.color.cat_medical, Direction.EXPENSE)
    val gComm = CategoryGroup("g_comm", "通讯", R.color.cat_comm, Direction.EXPENSE)
    val gHobby = CategoryGroup("g_hobby", "爱好", R.color.cat_entertainment, Direction.EXPENSE)
    val gOther = CategoryGroup("g_other", "其他", R.color.cat_other, Direction.EXPENSE)
    val gIncome = CategoryGroup("g_income", "收入", R.color.cat_salary, Direction.INCOME)

    private val BASE_GROUPS: List<CategoryGroup> = listOf(
        gDining, gTransport, gDaily, gShopping, gSchool, gHome, gHealth, gComm, gHobby, gOther,
        gIncome
    )

    /** 叠加上用户的改名 / 换色 / 删除之后，界面真正能看到的一级类 */
    val GROUPS: List<CategoryGroup> get() = BASE_GROUPS
        .filterNot { it.key in layer.hiddenGroups }
        .map { applyPatch(it) }

    val EXPENSE_GROUPS: List<CategoryGroup> get() = GROUPS.filter { it.direction == Direction.EXPENSE }
    val INCOME_GROUPS: List<CategoryGroup> get() = GROUPS.filter { it.direction == Direction.INCOME }

    fun groupsOf(direction: Direction): List<CategoryGroup> =
        if (direction == Direction.INCOME) INCOME_GROUPS else EXPENSE_GROUPS

    fun groupByKey(key: String?): CategoryGroup? = GROUPS.firstOrNull { it.key == key }

    fun fallbackGroupOf(direction: Direction): CategoryGroup =
        if (direction == Direction.INCOME) gIncome else gOther

    /** 某条账目归属的一级类 */
    fun groupOf(key: String?, direction: Direction): CategoryGroup =
        groupByKey(byKey(key, direction).group) ?: fallbackGroupOf(direction)

    // ------------------------------------------------------------------ 二级类（内置）

    val dining = Category("dining", "餐饮", R.color.cat_dining, Direction.EXPENSE, gDining.key)
    val snack = Category("snack", "零食", R.color.cat_snack, Direction.EXPENSE, gDining.key)
    val drink = Category("drink", "饮料", R.color.cat_drink, Direction.EXPENSE, gDining.key)
    val fruit = Category("fruit", "水果", R.color.cat_fruit, Direction.EXPENSE, gDining.key)
    val dinner = Category("dinner", "聚餐", R.color.cat_dinner, Direction.EXPENSE, gDining.key)

    val transport = Category("transport", "交通", R.color.cat_transport, Direction.EXPENSE, gTransport.key)
    val taxi = Category("taxi", "打车", R.color.cat_taxi, Direction.EXPENSE, gTransport.key)
    val fuel = Category("fuel", "加油", R.color.cat_fuel, Direction.EXPENSE, gTransport.key)
    val parking = Category("parking", "停车", R.color.cat_parking, Direction.EXPENSE, gTransport.key)
    val travel = Category("travel", "旅行", R.color.cat_travel, Direction.EXPENSE, gTransport.key)

    val grocery = Category("grocery", "日用百货", R.color.cat_grocery, Direction.EXPENSE, gDaily.key)
    val clothing = Category("clothing", "服饰鞋包", R.color.cat_clothing, Direction.EXPENSE, gDaily.key)
    val appliance = Category("appliance", "家用电器", R.color.cat_appliance, Direction.EXPENSE, gDaily.key)
    val express = Category("express", "快递物流", R.color.cat_express, Direction.EXPENSE, gDaily.key)

    val shopping = Category("shopping", "购物", R.color.cat_shopping, Direction.EXPENSE, gShopping.key)
    val digital = Category("digital", "数码电子", R.color.cat_digital, Direction.EXPENSE, gShopping.key)
    val beauty = Category("beauty", "美妆护肤", R.color.cat_beauty, Direction.EXPENSE, gShopping.key)

    val education = Category("education", "教育培训", R.color.cat_education, Direction.EXPENSE, gSchool.key)
    val stationery = Category("stationery", "文具书籍", R.color.cat_stationery, Direction.EXPENSE, gSchool.key)
    val exam = Category("exam", "考试报名", R.color.cat_exam, Direction.EXPENSE, gSchool.key)

    val housing = Category("housing", "居住物业", R.color.cat_housing, Direction.EXPENSE, gHome.key)
    val rent = Category("rent", "房租", R.color.cat_rent, Direction.EXPENSE, gHome.key)
    val utility = Category("utility", "水电燃气", R.color.cat_utility, Direction.EXPENSE, gHome.key)
    val furniture = Category("furniture", "家居家具", R.color.cat_furniture, Direction.EXPENSE, gHome.key)

    val medical = Category("medical", "医疗", R.color.cat_medical, Direction.EXPENSE, gHealth.key)
    val medicine = Category("medicine", "药品", R.color.cat_medicine, Direction.EXPENSE, gHealth.key)
    val checkup = Category("checkup", "体检", R.color.cat_checkup, Direction.EXPENSE, gHealth.key)

    val phone = Category("phone", "话费", R.color.cat_comm, Direction.EXPENSE, gComm.key)
    val broadband = Category("broadband", "宽带", R.color.cat_broadband, Direction.EXPENSE, gComm.key)

    val entertainment = Category("entertainment", "休闲娱乐", R.color.cat_entertainment, Direction.EXPENSE, gHobby.key)
    val fitness = Category("fitness", "运动健身", R.color.cat_fitness, Direction.EXPENSE, gHobby.key)
    val media = Category("media", "影音会员", R.color.cat_media, Direction.EXPENSE, gHobby.key)
    val game = Category("game", "游戏充值", R.color.cat_game, Direction.EXPENSE, gHobby.key)

    val transfer = Category("transfer", "转账红包", R.color.cat_transfer, Direction.EXPENSE, gOther.key)
    val gift = Category("gift", "人情礼金", R.color.cat_gift, Direction.EXPENSE, gOther.key)
    val pet = Category("pet", "宠物", R.color.cat_pet, Direction.EXPENSE, gOther.key)
    val otherExpense = Category("other", "其他", R.color.cat_other, Direction.EXPENSE, gOther.key)

    val salary = Category("salary", "工资报酬", R.color.cat_salary, Direction.INCOME, gIncome.key)
    val bonus = Category("bonus", "奖金提成", R.color.cat_bonus, Direction.INCOME, gIncome.key)
    val refund = Category("refund", "退款", R.color.cat_refund, Direction.INCOME, gIncome.key)
    val redpacket = Category("redpacket", "红包礼金", R.color.cat_redpacket, Direction.INCOME, gIncome.key)
    val otherIncome = Category("other_income", "其他收入", R.color.cat_other_income, Direction.INCOME, gIncome.key)

    private val BUILTIN: List<Category> = listOf(
        dining, snack, drink, fruit, dinner,
        transport, taxi, fuel, parking, travel,
        grocery, clothing, appliance, express,
        shopping, digital, beauty,
        education, stationery, exam,
        housing, rent, utility, furniture,
        medical, medicine, checkup,
        phone, broadband,
        entertainment, fitness, media, game,
        transfer, gift, pet, otherExpense,
        salary, bonus, refund, redpacket, otherIncome
    )

    // ------------------------------------------------------------------ 用户层

    @Volatile
    private var layer: CategoryLayer = CategoryLayer()

    /** 内置 + 用户层最终生效的分类字典 */
    val ALL: List<Category> get() = visibleBuiltins() + layer.customs.filterNot { it.key in layer.hiddenLeaves }

    /** 只有内置分类，用于「分类规则」这类不该出现自建分类的场景 */
    val BUILTIN_ALL: List<Category> get() = visibleBuiltins()

    private fun visibleBuiltins(): List<Category> =
        BUILTIN.filterNot { it.key in layer.hiddenLeaves }.map { applyPatch(it) }

    private fun applyPatch(base: Category): Category {
        val p = layer.patches[base.key] ?: return base
        val group = (p.group?.takeIf { it.isNotBlank() } ?: base.group)
            .let { if (it in layer.hiddenGroups) fallbackGroupOf(base.direction).key else it }
        return base.copy(
            label = p.label?.takeIf { it.isNotBlank() } ?: base.label,
            // 用户存下来的可能是新图标名，也可能是老版本存的 emoji，统一收敛一次
            iconName = p.iconName?.takeIf { it.isNotBlank() }
                ?.let { CategoryIconCatalog.normalize(it) } ?: base.iconName,
            colorRes = (p.colorRes ?: 0).takeIf { it != 0 } ?: base.colorRes,
            group = group
        )
    }

    private fun applyPatch(base: CategoryGroup): CategoryGroup {
        val p = layer.groupPatches[base.key] ?: return base
        return base.copy(
            label = p.label?.takeIf { it.isNotBlank() } ?: base.label,
            iconName = p.iconName?.takeIf { it.isNotBlank() }
                ?.let { CategoryIconCatalog.normalize(it, group = true) } ?: base.iconName,
            colorRes = (p.colorRes ?: 0).takeIf { it != 0 } ?: base.colorRes
        )
    }

    /** 当前生效的用户层（管理页用它判断哪些是改过的） */
    val currentLayer: CategoryLayer get() = layer

    /**
     * 由仓库层在启动 / 任何一次分类增删后灌进来。
     * 纯逻辑单测不会调用它，所以默认空层，行为与出厂字典完全一致。
     */
    fun applyLayer(newLayer: CategoryLayer) {
        layer = newLayer
    }

    /** 回到出厂字典（清空自建分类与所有改动） */
    fun clearLayer() {
        layer = CategoryLayer()
    }

    /**
     * 只刷新自建分类，等价于 [applyLayer] 保留改动、替换 customs。
     * 早期接口保留下来，仓库层内部统一改用 applyLayer 之后可以删。
     */
    fun registerCustom(list: List<Category>) {
        layer = layer.copy(customs = list.toList())
    }

    val customCategories: List<Category> get() = layer.customs

    val EXPENSES: List<Category> get() = ALL.filter { it.direction == Direction.EXPENSE }
    val INCOMES: List<Category> get() = ALL.filter { it.direction == Direction.INCOME }

    fun of(direction: Direction): List<Category> =
        if (direction == Direction.INCOME) INCOMES else EXPENSES

    fun childrenOf(groupKey: String, direction: Direction): List<Category> =
        of(direction).filter { it.group == groupKey }

    fun byKey(key: String?): Category =
        ALL.firstOrNull { it.key == key }
            ?: if (key == otherIncome.key) otherIncome else otherExpense

    fun byKey(key: String?, direction: Direction): Category =
        ALL.firstOrNull { it.key == key && it.direction == direction }
            ?: fallbackOf(direction)

    fun byKeyOrNull(key: String?, direction: Direction): Category? =
        ALL.firstOrNull { it.key == key && it.direction == direction }

    fun fallbackOf(direction: Direction): Category =
        if (direction == Direction.INCOME) otherIncome else otherExpense

    /** key 在出厂字典里有没有（自建分类一律 false） */
    fun isBuiltinKey(key: String?): Boolean = BUILTIN.any { it.key == key }

    fun isGroupKey(key: String?): Boolean = BASE_GROUPS.any { it.key == key }

    // ------------------------------------------------------------------ 自建分类的颜色与图标

    /** 自建分类可选的颜色；落库存的是名字，不存资源 id，避免升级后 id 漂移 */
    val PALETTE: List<Pair<String, Int>> = listOf(
        "cat_dining" to R.color.cat_dining,
        "cat_snack" to R.color.cat_snack,
        "cat_transport" to R.color.cat_transport,
        "cat_taxi" to R.color.cat_taxi,
        "cat_grocery" to R.color.cat_grocery,
        "cat_shopping" to R.color.cat_shopping,
        "cat_clothing" to R.color.cat_clothing,
        "cat_education" to R.color.cat_education,
        "cat_housing" to R.color.cat_housing,
        "cat_medical" to R.color.cat_medical,
        "cat_fitness" to R.color.cat_fitness,
        "cat_media" to R.color.cat_media,
        "cat_salary" to R.color.cat_salary,
        "cat_redpacket" to R.color.cat_redpacket,
        "cat_other" to R.color.cat_other
    )

    fun colorResOf(name: String?): Int =
        PALETTE.firstOrNull { it.first == name }?.second ?: R.color.cat_other

    fun colorNameOf(colorRes: Int): String =
        PALETTE.firstOrNull { it.second == colorRes }?.first ?: "cat_other"

    /**
     * 自建分类 / 编辑弹窗可选的图标名。
     *
     * 全部是统一的线性矢量图标（见 [CategoryIconCatalog]），
     * 没有再让用户挑 emoji —— 那套表情在不同 ROM 上长得不一样，也压不住「专业感」。
     */
    val ICON_CHOICES: List<String> get() = CategoryIconCatalog.LEAF_NAMES

    /** 自建分类的 key 一律以这个前缀打头，方便与内置分类区分 */
    const val CUSTOM_PREFIX = "c_"

    // ------------------------------------------------------------------ 内置关键词规则

    /**
     * 内置关键词种子。key 是二级类，value 是关键词表。
     * 同一个方向上不允许出现重复关键词（否则先注册的那个会静默胜出）。
     */
    private val seed: List<Pair<Category, List<String>>> = listOf(
        // ---- 饮食 ----
        dining to listOf(
            "餐", "饭", "食堂", "外卖", "美团", "饿了么", "饿了", "肯德基", "麦当劳", "KFC",
            "星巴克", "瑞幸", "luckin", "咖啡", "奶茶", "喜茶", "蜜雪", "库迪", "茶百道", "古茗",
            "沪上阿姨", "烧烤", "火锅", "小吃", "面馆", "米线", "沙县", "兰州", "汉堡", "披萨",
            "寿司", "必胜客", "海底捞", "西贝", "吉野家", "真功夫", "老乡鸡", "面", "粉", "粥",
            "早餐", "午餐", "晚餐", "饭店", "餐馆", "餐饮", "快餐"
        ),
        snack to listOf(
            "零食", "食品", "烘焙", "面包", "蛋糕", "甜点", "饼干", "巧克力", "坚果", "薯片",
            "糖果", "点心", "零食铺", "良品铺子", "三只松鼠", "百草味"
        ),
        drink to listOf(
            "饮料", "可乐", "雪碧", "矿泉水", "果汁", "气泡水", "维他命水", "红牛", "东鹏",
            "冰红茶", "柠檬茶", "三得利", "元气森林", "酒水饮料"
        ),
        fruit to listOf(
            "水果", "苹果", "香蕉", "西瓜", "葡萄", "草莓", "车厘子", "橙子", "榴莲",
            "百果园", "鲜丰水果", "水果店"
        ),
        dinner to listOf(
            "聚餐", "请客", "宴请", "酒", "啤酒", "白酒", "红酒", "小龙虾", "自助餐", "夜宵",
            "烧烤店", "大排档"
        ),

        // ---- 出行 ----
        transport to listOf(
            "地铁", "公交", "轨道交通", "交通", "单车", "哈啰", "青桔", "美团单车", "摩拜",
            "共享单车", "轮渡", "车费", "公交卡", "出行", "通勤"
        ),
        taxi to listOf(
            "滴滴", "打车", "出租车", "网约车", "高德打车", "曹操出行", "首汽", "T3出行",
            "花小猪", "顺风车", "货拉拉", "代驾", "出租车费"
        ),
        fuel to listOf(
            "加油", "中国石化", "中国石油", "壳牌", "加油站", "燃油", "汽油", "充电桩",
            "充电费", "养车", "汽车服务"
        ),
        parking to listOf(
            "停车", "泊车", "停车场", "高速", "ETC", "过路费", "洗车", "车位"
        ),
        travel to listOf(
            "酒店", "民宿", "携程", "去哪儿", "飞猪", "景区", "旅游", "度假", "旅行", "旅馆",
            "客栈", "亚朵", "汉庭", "如家", "希尔顿", "洲际", "airbnb", "门票", "导游",
            "跟团", "签证", "度假村", "青旅", "机票", "航空", "火车", "高铁", "12306"
        ),

        // ---- 日用 ----
        grocery to listOf(
            "超市", "便利店", "永辉", "沃尔玛", "家乐福", "盒马", "大润发", "物美", "华润万家",
            "菜市场", "生鲜", "日用", "山姆", "罗森", "全家", "7-11", "711", "美宜佳",
            "便利蜂", "蔬菜", "粮油", "副食", "纸巾", "日用杂货", "五金", "杂货店"
        ),
        clothing to listOf(
            "服饰", "优衣库", "耐克", "阿迪", "nike", "adidas", "鞋", "衣", "箱包", "裤",
            "外套", "羽绒服", "海澜之家", "安踏", "李宁", "森马", "配饰", "内衣"
        ),
        appliance to listOf(
            "家电", "苏宁", "国美", "美的", "格力", "海尔", "小米之家", "扫地机器人",
            "电饭煲", "家电维修", "电器", "冰箱", "洗衣机", "空调"
        ),
        express to listOf(
            "快递", "顺丰", "菜鸟", "中通", "圆通", "申通", "韵达", "极兔", "京东物流",
            "邮政", "驿站", "寄件", "运费"
        ),

        // ---- 购物 ----
        shopping to listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "商场", "购物", "天猫超市", "得物",
            "名创优品", "无印良品", "MUJI", "网易严选", "抖音电商", "快手电商", "直播间",
            "代购", "百货商场"
        ),
        digital to listOf(
            "数码", "手机", "华为", "苹果手机", "Apple", "平板", "电脑", "耳机", "键盘", "显示器",
            "相机", "小米", "OPPO", "vivo", "荣耀", "充电宝", "智能手表", "笔记本电脑"
        ),
        beauty to listOf(
            "化妆品", "护肤", "美妆", "丝芙兰", "屈臣氏", "面膜", "口红", "香水", "洗面奶",
            "精华", "彩妆", "美发", "美容"
        ),

        // ---- 学校 ----
        education to listOf(
            "学费", "培训", "教育", "课程", "网课", "辅导", "学而思", "新东方", "驾校",
            "幼儿园", "学校", "补习", "培优", "考研", "讲座"
        ),
        stationery to listOf(
            "文具", "书店", "图书", "当当", "书本", "笔记本", "打印", "复印", "学习资料",
            "教材", "笔袋"
        ),
        exam to listOf(
            "考试", "报名费", "考证", "四级", "六级", "雅思", "托福", "公务员", "职称",
            "知网", "文献", "论文", "报名"
        ),

        // ---- 居住 ----
        housing to listOf(
            "物业", "垃圾处理", "家政", "保洁", "维修", "装修", "中介费", "租房", "小区",
            "门禁", "取暖", "物业费"
        ),
        rent to listOf(
            "房租", "租金", "押金", "月租", "公寓", "房东", "房租水电"
        ),
        utility to listOf(
            "水费", "电费", "燃气", "天然气", "暖气", "水电费", "供暖", "水电气", "电表"
        ),
        furniture to listOf(
            "家具", "宜家", "IKEA", "沙发", "床垫", "衣柜", "窗帘", "家居", "餐具", "厨具",
            "收纳", "床品"
        ),

        // ---- 医疗 ----
        medical to listOf(
            "医院", "诊所", "门诊", "挂号", "口腔", "牙科", "眼科", "中医", "医疗",
            "卫生院", "康复", "妇产", "住院", "手术", "看医生"
        ),
        medicine to listOf(
            "药房", "大药房", "药店", "医药", "药品", "买药", "同仁堂", "处方药", "消炎药"
        ),
        checkup to listOf(
            "体检", "疫苗", "检验", "化验", "拍片", "核磁", "健康检查", "核酸"
        ),

        // ---- 通讯 ----
        phone to listOf(
            "话费", "充值话费", "中国移动", "中国联通", "中国电信", "流量", "手机充值",
            "套餐费", "号码"
        ),
        broadband to listOf(
            "宽带", "网费", "光纤", "宽带续费", "有线电视", "上网费"
        ),

        // ---- 爱好 ----
        entertainment to listOf(
            "电影", "影院", "万达", "KTV", "演唱会", "话剧", "剧本杀", "密室", "桌游",
            "乐园", "游乐", "打赏", "彩票", "门票娱乐"
        ),
        fitness to listOf(
            "健身", "瑜伽", "运动", "游泳", "球馆", "篮球", "羽毛球", "健身房", "跑步",
            "马拉松", "球拍", "体育"
        ),
        media to listOf(
            "会员", "订阅", "腾讯视频", "爱奇艺", "优酷", "芒果TV", "网易云音乐", "QQ音乐",
            "音乐", "Netflix", "哔哩哔哩", "B站", "直播", "视频会员", "听书", "网盘会员"
        ),
        game to listOf(
            "游戏", "Steam", "游戏充值", "皮肤", "点券", "switch", "任天堂", "手游", "点卡"
        ),

        // ---- 其他 ----
        transfer to listOf(
            "转账", "红包", "亲属卡", "还款", "信用卡还款", "代付", "AA", "收款方", "给朋友",
            "群收款", "还花呗", "花呗还款", "借款", "还钱"
        ),
        gift to listOf(
            "礼金", "随礼", "份子钱", "结婚礼", "送礼", "寿礼", "人情", "慰问"
        ),
        pet to listOf(
            "宠物", "猫粮", "狗粮", "宠物医院", "猫砂", "宠物用品", "撸猫", "宠物美容"
        ),

        // ---- 收入 ----
        salary to listOf(
            "工资", "薪资", "薪酬", "工资代发", "薪", "月薪", "绩效", "基本工资", "劳务",
            "劳务费", "代发工资"
        ),
        bonus to listOf(
            "奖金", "年终奖", "提成", "季度奖", "全勤奖", "加班费", "津贴", "补贴", "项目奖"
        ),
        refund to listOf(
            "退款", "退货", "退还", "返现", "退款成功", "原路退回", "撤销", "赔付", "报销"
        ),
        redpacket to listOf(
            "红包收", "收到红包", "压岁钱", "礼金收", "收款红包", "发红包收"
        ),
        otherIncome to listOf(
            "收款", "转入", "利息", "理财收益", "分红", "收到", "到账", "存入", "转入收款"
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
