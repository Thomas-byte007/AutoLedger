# 自动记账助手（AutoLedger）

一个完全离线的安卓记账 App：**不用手动记账**，微信 / 支付宝付款后自动把这一笔记进本地账本，自动判断消费类型，月底自动出账单报表。

> 数据全部保存在手机本机（SQLite），应用**没有申请网络权限**，不存在任何上传行为。

---

## 一、安装

1. 把 `app-release.apk` 传到手机（微信文件传输 / 数据线 / 网盘均可）。
2. 点击安装。首次会提示「来自未知来源」，在系统设置里允许本次安装即可。
3. 最低支持 Android 8.0（API 26）。

---

## 二、让它开始自动记账（关键一步）

自动记账靠读取**支付通知**实现，需要你手动授予一次「通知使用权」，安卓系统不允许静默获取：

1. 打开 App → 底部「我的」→ 点顶部卡片上的 **去开启通知使用权**（或「通知使用权设置」）。
2. 在系统页面里找到「自动记账」，把开关打开。
3. 回到 App，「我的」页顶部会显示 **自动记账运行中**。

同时请确认：

- 手机系统里 **微信 / 支付宝的通知是打开的**（有些用户关了通知，就收不到）；
- 微信里「微信支付」服务通知不要设为免打扰。

授权后，付款时会自动记账，并弹一条轻提示，例如：

```
已自动记账 · 餐饮
-25.00  星巴克  · 待分类
```

### 三种记账来源

| 来源 | 说明 | 需要什么 |
|---|---|---|
| 微信支付通知 | 微信付款后自动记账 | 通知使用权 |
| 支付宝支付通知 | 支付宝付款后自动记账 | 通知使用权 |
| 银行卡短信（可选） | 卡里被扣钱 / 到账时自动记账，作为补充 | 短信权限，默认关闭 |
| 手动记账 | 现金、代付、漏记的，右下角「+」手动补 | 无 |

> 同一笔钱如果同时产生了「支付通知」和「银行短信」，App 会自动去重，只记一条。

---

## 三、自动判断消费类型

每笔记录都会按关键词规则自动分类：餐饮、交通、购物、日用百货、居住、娱乐、医疗、教育、旅行、转账红包、工资报酬、退款、其他。

- 内置 **200+ 条**关键词规则，开箱即用（例如「星巴克 → 餐饮」「滴滴 → 交通」）。
- **识别不出来时不会乱猜**，会标记为「待分类」，你点进去选一个分类即可。
- 在编辑页打开「记住这个商户」开关，以后这个商户会自动归到你选的分类。
- 想批量维护：「我的 → 分类规则」可以新增 / 删除关键词规则。

---

## 四、月度账单

底部「报表」页会按自然月统计：

- 本月支出总额、收入、笔数、日均支出、**与上月的环比**
- **支出构成环形图** + 每个分类的金额、占比百分比、占比条
- 收入构成
- 近 6 个月收支趋势柱状图
- 花钱最多的对象 TOP 5
- 本月还有多少笔「待分类」，一键跳去处理

---

## 五、补历史账：导入微信 / 支付宝账单

自动记账只能从装上那天开始。想补齐历史消费：

1. **支付宝**：我的 → 账单 → 右上角「···」→ 开具交易流水证明 / 账单导出，选 CSV，到邮箱下载。
2. **微信**：我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账，选 CSV。
3. 把 CSV 传到手机，App 里「我的 → 导入微信 / 支付宝账单」，选中文件即可。

支付宝导出的 GBK 编码和微信导出的带 BOM 的 UTF-8 都能正确识别，表头前的说明行会自动跳过，重复记录自动去重。

---

## 六、导出与清理

- **导出账本数据**：生成 UTF-8 BOM 的 CSV，Excel 直接打开不乱码，可自行备份。
- **清空全部数据**：删掉本机所有账目，不可恢复。

---

## 七、隐私

- 未申请 `INTERNET` 权限，物理上无法联网。
- 支付通知只提取「金额 / 商户 / 支付方式」等字段，原文留在本机数据库备查，不会外传。
- 短信解析只在本地进行，且默认关闭。
- 卸载应用 = 数据彻底删除。

---

## 八、工程结构

```
app/src/main/java/com/local/autoledger/
├── LedgerApp.kt                     应用入口
├── data/                            数据层
│   ├── Models.kt                    Txn / Category / MonthSummary 等模型
│   ├── Categories.kt                分类字典 + 内置关键词
│   ├── LedgerDb.kt                  SQLite 建表与内置规则灌入
│   ├── LedgerRepository.kt          读写、去重、月度统计、CSV 导出
│   ├── CategoryEngine.kt            关键词自动分类引擎
│   ├── Prefs.kt                     轻量设置
│   └── LedgerBus.kt                 数据变更通知
├── capture/                         自动记账
│   ├── PaymentParser.kt             通知 / 短信 → 一笔交易（金额、收支、商户、支付方式）
│   ├── PaymentNotificationListener.kt  微信 / 支付宝通知监听
│   ├── SmsPaymentReceiver.kt        银行卡短信
│   ├── BillImporter.kt              微信 / 支付宝账单 CSV 导入
│   └── CaptureSink.kt               落库 + 去重 + 轻提示
└── ui/                              界面
    ├── MainActivity.kt              底部三 Tab
    ├── TransactionsFragment.kt      明细（按天分组）
    ├── ReportFragment.kt            月度报表
    ├── SettingsFragment.kt          我的 / 授权 / 导入导出
    ├── EditTransactionActivity.kt   手动记账 / 编辑 / 手动分类
    ├── RulesActivity.kt             分类规则管理
    └── widget/                      自绘环形图与柱状图（无第三方图表库）
```

### 技术选型

| 项 | 选择 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| 构建 | AGP 8.7.3 + Gradle 8.10，JDK 17 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| UI | View + XML + Material 3（自定义环形图、柱状图，零图表依赖） |
| 存储 | 原生 SQLite（无 Room / 无注解处理器） |
| 依赖 | 仅 AndroidX + Material，无网络库、无统计 SDK |

---

## 九、自己编译

```bash
cd D:\Project\记账助手
gradlew.bat assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（本项目已额外复制一份到 `dist/AutoLedger-v1.0.0-release.apk`）

### 签名配置（密钥不入库）

`keystore/` 整个目录已被 `.gitignore` 排除，**不会提交到仓库**，因此 clone 下来后需要自己准备证书。
签名参数由 `app/build.gradle.kts` 从 `local.properties`（同样被忽略）读取，键名如下：

```properties
LEDGER_KEYSTORE=keystore/ledger-release.jks
LEDGER_STORE_PASSWORD=你的_store_口令
LEDGER_KEY_ALIAS=你的_别名
LEDGER_KEY_PASSWORD=你的_key_口令
```

缺失时会回退到同名环境变量；若证书文件不存在，release 包会退化为**未签名**产物（源码仍可正常编译）。
当前交付用的自签证书在开发机上位于 `keystore/ledger-release.jks`（别名 `ledger`），仅用于本地安装验证，
正式发布请替换成自己的证书并妥善保管。

已产出的包信息（用 `apksigner` / `aapt2` 校验过）：

| 项 | 值 |
|---|---|
| 包名 | `com.local.autoledger` |
| 版本 | 1.0.0 (versionCode 1) |
| minSdk / targetSdk | 26 / 35 |
| 签名 | APK Signature Scheme v2，CN=AutoLedger |
| 权限 | RECEIVE_SMS、READ_SMS、POST_NOTIFICATIONS、READ_EXTERNAL_STORAGE(≤32) |
| INTERNET 权限 | **无** |

### 环境要求（这台机器上已验证可用的组合）

- JDK 17（`D:\develop\Java\jdk-17`，需设置 `JAVA_HOME`）
- Android SDK：`D:\develop\android-sdk`（`platforms;android-35` + `build-tools;35.0.0`），路径写在 `local.properties`
- Gradle 8.10（仓库自带 wrapper，或使用 `~\.gradle\wrapper\dists` 里的缓存发行版）

### 两个容易踩的坑

1. **项目路径含中文**：AGP 默认会直接拒绝构建，已在 `gradle.properties` 里用
   `android.overridePathCheck=true` 跳过（官方给出的开关）。
2. **`gradle wrapper` 任务**：它会去 `services.gradle.org` 校验发行版 URL，网络不通时会失败。
   本地已缓存该发行版时，用 `--no-validate-url` 即可：
   ```bash
   gradle wrapper --gradle-version 8.10 --distribution-type bin --no-validate-url
   ```
   生成之后 `gradlew` 会直接使用缓存，不再联网。
