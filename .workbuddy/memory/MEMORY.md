# MEMORY.md — 记账助手 项目长期约定

## 项目坐标

- 包名 `com.local.autoledger`，应用名「自动记账」
- 源码根：`app/src/main/java/com/local/autoledger/`
- 交付物：`dist/AutoLedger-v1.0.1-release.apk`（当前 1.0.1 / versionCode 2）
- 远程仓库：`git@github.com:Thomas-byte007/AutoLedger.git`（分支 main）
  注意本机 SSH 走 `~/.ssh/config` 里 `github.com → ssh.github.com:443` 的转发，别用 `-F none`

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
- **判重必须分档**（`LedgerRepository.rejectReason`，别退回单一条件）：
  ① 墓碑 `deleted_txn` 命中 → 丢；② 同来源：20s 内且 `raw_text` 一字不差 → 重复；
  ③ 跨来源（通知 / 短信 / 账单）：金额 + 方向一致、窗口 240s → 重复。
  单一按「金额 + 方向 + 时间窗」判重会吃掉用户连付的几笔同金额，已踩过坑。
- **删除要留墓碑**：`ledger.db` 现为 **v2**，`delete(id)` 会往 `deleted_txn` 抄一份，
  30 分钟内拒绝同原文重放，否则删掉的记录会被系统重投的通知写回来。加表记得改 `VERSION` + `onUpgrade`。
- **别拿 `StatusBarNotification.postTime` 直接当交易时间**：同一条通知被反复改写时它每次都往前走。
  参见 `PaymentNotificationListener.resolveOccurredAt`（按正文是否变化 + postTime 是否前进判断）。
- **排查靠采集日志**：`CaptureLog`（存 `filesDir/capture.log`，300 行上限），
  入口「我的 → 采集日志」。新增采集分支时记得补一行日志，否则真机问题查不了。

## 测试（改完代码先跑，全程电脑端闭环，不用真机）

单元测试（主力回归，秒级）：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

52 个用例：`MoneyTest` / `PaymentParserTest` / `CategoryEngineTest` / `LedgerRepositoryTest`
（最后一个用 Robolectric 起真实 SQLite）。结果 XML 在
`D:\Project\AutoLedger-build\app\test-results\testDebugUnitTest\`。

模拟器端到端：

```powershell
powershell -ExecutionPolicy Bypass -File tools\emulator-e2e.ps1
```

`tools/emulator-e2e.ps1` + `tools/ledger_assert.py`：起模拟器 → 装 APK → 投假支付通知 →
拉库断言 → 驱动界面删除 → 截图存 `dist/emulator-e2e/`。

### 测试基础设施的坑（改之前先看）

- **中文项目路径会让 Gradle test worker 全部 `ClassNotFoundException`**。
  修法在根 `build.gradle.kts`：build 目录重定位到 `D:\Project\AutoLedger-build`。
  **别删那段逻辑**，也别以为测试挂是自己代码的问题。
- **模拟器 AVD 必须在 D 盘**：用户环境变量 `ANDROID_AVD_HOME=D:\develop\android-avd`。
  默认的 `~/.android/avd` 往 C 盘写，一次能吃掉 9.7 GB（关机时写的 6 GB `userdata-qemu.img`
  + 2.5 GB 快照）。`config.ini` 已关快照、`disk.dataPartition.size` 限到 3072M。
- **`:paysim` 是测试专用马甲模块**：两个 flavor 分别伪装 `com.tencent.mm` /
  `com.eg.android.AlipayGphone`。原理是 `NotificationListenerService` 按安装包名认来源，
  不真的装这个包名就测不了通知抓取。文案走 **Base64**（`--es text64`）传，
  因为 adb shell 传中文会乱码，而乱码会改变解析结果、测的就不是真实逻辑了。
- **`.ps1` 必须带 UTF-8 BOM**：Windows PowerShell 5.1 否则按 GBK 读，中文乱码、引号被撑破，
  报一堆「意外的标记 }」。加 BOM 用 `[System.IO.File]::WriteAllBytes` 拼 `0xEF,0xBB,0xBF`。
- **拉设备上的二进制别用 `cmd /c "... > file"`**（会过文本编码导致损坏，还容易被沙箱卡住）：
  用 `Start-Process -RedirectStandardOutput`，或 `adb shell screencap` + `adb pull`。
- **`pm clear` 会把运行时权限打回未授权**，启动 App 时系统弹权限框盖住界面，
  uiautomator 只能 dump 到对话框。跑界面自动化前先 `pm grant` 再启动。
- 本机删除走回收站，`Remove-Item` 后磁盘空间**不会**立刻释放，得再清一次回收站。

## 构建命令（本机）

```powershell
$env:JAVA_HOME="D:\develop\Java\jdk-17"
$env:ANDROID_HOME="D:\develop\android-sdk"
cd D:\Project\记账助手
.\gradlew.bat assembleRelease
```

注意 `gradle.properties` 里的 `android.overridePathCheck=true` 不能删（路径含中文）。
