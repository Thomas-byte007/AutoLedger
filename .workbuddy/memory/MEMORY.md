# MEMORY.md — 记账助手 项目长期约定

## 项目坐标

- 包名 `com.local.autoledger`，应用名「自动记账」
- 源码根：`app/src/main/java/com/local/autoledger/`
- 交付物：`dist/AutoLedger-v1.0.0-release.apk`

## 硬性约束（用户明确要求，不要违反）

1. **纯本地**：不申请 `INTERNET` 权限，全部数据存本机 SQLite。任何引入网络库/云端的方案都要先问。
2. **必须支持手动记账**：手动记账是一等功能，不是兜底。漏记、现金、代付都要能记，
   并且自动抓到的记录也要能编辑 / 删除 / 改分类。
3. **界面要简约精美**：用户明确提过。设计语言见下，改动界面时保持一致。
4. **构建产物必须是 APK**：不能只交源码。

## 视觉规范（改 UI 必须遵守）

- 主色 `#3B6CF6`，页面底色 `#F4F6FB`，卡片纯白 + 20dp 圆角 + 1dp 描边（不用阴影）
- 顶部概览卡用 `bg_hero_card` 蓝色渐变 + 白字，是整个 App 的视觉锚点
- 支出用 `@color/expense_red`，收入用 `@color/income_green`（中国习惯：支出的红、收入的绿）
- 分类图标 = emoji 放在 `shape_circle` 圆形底上，底色用 `ThemeUtil.soft()` 把分类色调淡
- 文案用中文，金额统一走 `Money.yuan()` / `Money.plain()` 千分位格式化

## 代码约定

- 数据层方法都是**同步阻塞**的，调用方必须放 `Dispatchers.IO`
- 数据变更后调用 `LedgerBus.notifyChanged()`，界面注册在 onStart / 注销在 onStop
- 分类 key 一旦落库不可改名；展示名与颜色可随意调整（见 `Categories`）
- 新增分类：加 `Category` + 至少 5 个关键词种子；新增内置规则要在 `Categories.seed` 里补
- 解析支付信息的原则是「宁可漏，不可错」，不确定就不要记账，交给用户手动分类

## 构建命令（本机）

```powershell
$env:JAVA_HOME="D:\develop\Java\jdk-17"
$env:ANDROID_HOME="D:\develop\android-sdk"
cd D:\Project\记账助手
.\gradlew.bat assembleRelease
```

注意 `gradle.properties` 里的 `android.overridePathCheck=true` 不能删（路径含中文）。
