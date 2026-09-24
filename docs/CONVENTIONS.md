# 随手记（AutoLedger）详细开发约定

> 本文件是 `.workbuddy/memory/MEMORY.md` 的详细版归档，排查测试/构建/模拟器问题前必读。

## 测试入口

单元测试（主力回归，秒级）：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

86 个用例：`MoneyTest` / `PaymentParserTest` / `CategoryEngineTest` / `CategoryHierarchyTest` /
`CategoryStoreTest` / `YearSummaryTest` / `LedgerRepositoryTest`（后四类用 Robolectric 起真实 SQLite）。
结果 XML 在 `app/build/test-results/testDebugUnitTest/`（路径含中文时重定位到 `D:\Project\AutoLedger-build\app\test-results\`）。

模拟器端到端：

```powershell
powershell -ExecutionPolicy Bypass -File tools\emulator-e2e.ps1
```

`tools/emulator-e2e.ps1` + `tools/ledger_assert.py`：起模拟器 → 装 APK → 投假支付通知 →
拉库断言 → 驱动界面删除 → 截图存 `dist/emulator-e2e/`。

界面可视化验收（逐页截图）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\ui-verify.ps1 -SkipBuild
```

覆盖：记一笔 / 分类选择 / 添加分类 / 编辑页 / 报表滚动 / 一级类下钻页 / 二级类下钻 /
年度账单（含整年明细）/ 分类管理（改名→删除→恢复内置）/ 后台常驻开关，产物 `dist/ui-verify/`。
（v1.5.0 起报表不再就地展开一级类，改为整行点击跳 `CategoryBreakdownActivity`）

只截「我的」页：

```powershell
powershell -ExecutionPolicy Bypass -File tools\shot-mine.ps1 -SkipInstall
```

产物 `dist/ui-verify/5x-*.png`。

## 测试基础设施的坑

- **动「我的」页就会打断 UI 脚本**：`ui-verify.ps1` 的「后台常驻」段依赖
  `稳定运行` 折叠区先展开（v1.5.0 移除短信后展开态只有 3 个开关：
  支付通知/后台常驻/辅助记账，取 `$switches[1]`；数量阈值从 4 改成 3）；
  `emulator-e2e.ps1` 找「采集日志」前要先点「更多」；`verify_settings_rows.py` 有 `ensure_fold()` 兜底。
  新增断言前先确认目标行是默认可见还是收在折叠里。
- **模拟器自动熄屏后 `input tap`/`input swipe` 静默失效，而 `uiautomator dump` 仍返回上一屏内容**。
  跑交互前 `settings put system screen_off_timeout 600000` + `svc power stayon usb`，
  每次交互前 `input keyevent KEYCODE_WAKEUP`。
- **自己写 adb 封装必须临时 `$ErrorActionPreference='Continue'`**：`adb pull`/`adb install`
  的正常进度走 stderr，PS 5.1 当成 ErrorRecord 抛出，`Stop` 模式当场中断。
- **非 ASCII 项目路径会让 Gradle test worker 全部 `ClassNotFoundException`**。修法在根
  `build.gradle.kts`：build 目录重定位到 `D:\Project\AutoLedger-build`。别删那段逻辑。
- **`adb` 偶发 `device offline` 而 `Invoke-Adb -AllowFail` 会吞错误**，导致 App 没重启、
  后续点击全部错位出假失败。`ui-verify.ps1` 的 `Invoke-Adb` 已内置重试，别绕开它。
- **`am start` 不会重建已有 Activity**，会带上一轮的 tab。脚本开头 `Reset-App`：
  force-stop 确认进程没了 → start → 显式点「明细」tab。
- **`uiautomator dump` 失败时设备上留的是上一屏 xml**。必须 `rm -f /sdcard/ui.xml` 再 dump，
  并校验输出含 `dumped`（`Get-Ui` 已这么做）。
- **`pm clear` 后要等通知监听真正绑上再发假支付**，判据是 `run-as <pkg> cat files/capture.log`
  出现「使用权已连接」，数量不够再补发一轮。
- **`uiautomator dump` 只有当前屏幕可见节点**。长页面靠下的模块（收入来源/趋势/大额支出、
  年度账单分布）断言前必须先滚过去。滑动落点要落在目标
  滚动容器里——`0.72*屏高` 在编辑页正好是数字键盘。
- **别在 UI 脚本里给本应用授予无障碍服务**：`ui-verify.ps1` 的 `Reset-App` 会先
  `Revoke-Accessibility` 再启动。原因不是「授权了会弹引导框」，而是无障碍服务真的连上之后，
  `uiautomator dump` 永远等不到 idle 状态（`ERROR: could not get idle state`），
  从那一步开始所有 `Get-Ui` 全部失败，表现是「一连串 UI 树抓取失败 + 截图却都正常」。
  v1.4.1 加了启动引导框之后必须处理它，正确做法是**点「取消」把框收掉**
  （`Dismiss-AccessibilityPrompt`），不是写 `enabled_accessibility_services`。
- **报表下钻改成跳页之后，UI 脚本别再等「就地展开」**：点一级类会切到
  `CategoryBreakdownActivity`（二级类构成 + 交易明细），断言用 `Get-TopActivity` 判 Activity，
  再在那一屏找「二级分类 / 交易明细」。
- **沙箱会连带回收 `Start-Process` 起的模拟器**（命令一结束进程就没了）。改用后台任务托管
  `emulator.exe`（前台 `&` + run_in_background），或对那条命令关沙箱。
- **AVD 必须在 D 盘**（`ANDROID_AVD_HOME=D:\develop\android-avd`），默认 `~/.android/avd`
  一次能吃 9.7 GB。`config.ini` 已关快照、`disk.dataPartition.size=3072M`。
- **`:paysim` 是测试专用马甲模块**：两个 flavor 伪装 `com.tencent.mm` /
  `com.eg.android.AlipayGphone`。文案走 Base64（`--es text64`）传，adb shell 传中文会乱码。
- **`.ps1` 必须带 UTF-8 BOM**，否则 PS 5.1 按 GBK 读导致乱码/语法错。加 BOM 用
  `[System.IO.File]::WriteAllBytes` 拼 `0xEF,0xBB,0xBF`。
- **拉设备二进制别用 `cmd /c "... > file"`**（文本编码损坏）。用 `Start-Process
  -RedirectStandardOutput`，或 `screencap` + `adb pull`。
- **`pm clear` 会把运行时权限打回未授权**，弹权限框盖住界面。跑 UI 自动化前先 `pm grant` 再启动。
- 本机删除走回收站，`Remove-Item` 后磁盘空间不会立刻释放。

## 构建命令

```powershell
$env:JAVA_HOME="D:\develop\Java\jdk-17"
$env:ANDROID_HOME="D:\develop\android-sdk"
cd D:\Project\AutoLedger
.\gradlew.bat assembleRelease
```

`gradle.properties` 的 `android.overridePathCheck=true` 不能删。出包复制到 `dist\随手记-v<版本>-release.apk`。

## 图标资产

两套图标都是**生成的矢量**，别手改 `res/drawable/ic_cat_*.xml`（会被脚本覆盖）：

```powershell
# 分类图标（53 个：11 一级类 + 42 二级类）→ 顺带刷新 CategoryIconCatalog.kt
& "C:\Users\C1921\AppData\Local\Programs\Python\Python312\python.exe" tools\gen_cat_icons.py

# 启动器图标预览（四种裁形 + 24~96px 小尺寸 + 单色版）→ dist/icon-preview/
& "C:\Users\C1921\AppData\Local\Programs\Python\Python312\python.exe" tools\preview_launcher_icon.py
```

- 启动器图标 = `ic_launcher_background`（渐变底板）+ `ic_launcher_foreground`（卡片/¥/金币）+
  `ic_launcher_monochrome`（Android 13+ 主题图标，纯描边）；`mipmap-anydpi-v26/ic_launcher.xml` 挂这三层。
- 前景层所有图形必须收在**中心 66dp 安全圆**内（换算：以 (54,54) 为心、半径 33 的圆）。
  圆角矩形按「圆角圆心到中心距离 + 圆角半径」算，别拿矩形外角硬套。
- 预览脚本的几何是**手抄 XML**的，改完 XML 必须同步改脚本，否则预览会骗人。
- 模拟器上看真机效果：装包 → HOME → 上滑进抽屉 → 截图放大（图标在抽屉最后一屏，
  `随手记` 会排在 `支付通知模拟` 后面）。

## 模拟器入口

- 一键启动：`D:\develop\android-avd\launch-emulator.cmd`（自设 ANDROID_AVD_HOME 再起 `ledger_test`）
- 桌面快捷方式：`C:\Users\C1921\Desktop\安卓模拟器（记账助手）.lnk`
- 重建快捷方式：`D:\develop\android-avd\make-shortcut.py`（托管 venv 的 pylnk3；
  沙箱硬拦 COM，别试 `WScript.Shell`）
- 验证 AVD 可解析：`emulator.exe -list-avds`（需 ANDROID_AVD_HOME 指向 D 盘）

## 模拟器脚本坑（历史教训）

- 后台 PowerShell 默认 2 分钟超时（长任务显式传 timeout）
- 模拟器要与测试脚本同一次调用启动并关沙箱
- 冷启动要 `KEYCODE_WAKEUP` + `wm dismiss-keyguard`
- `am start -n pkg/.MainActivity` 会错解析，须写全限定类名
- uiautomator dump 后 cat 要重试
- `adb shell am start ... -d package:xxx` 里的 `package:xxx` 必须加引号，
  否则 PowerShell 会把冒号后的部分吃掉（症状：`dat=package:`）

## 自动记账的存活（v1.5.2 加固后的口径）

- **「清理后台」在多数国产 ROM 上 = force-stop**，而 force-stop 的语义包含
  **把该 app 从 `enabled_accessibility_services` 里摘掉**（AOSP 行为，不是 adb 怪癖）。
  所以「记账失效」和「无障碍开关自己关了」常常是同一件事的两面。
- 判定三件套（改代码前后必跑）：
  `adb shell settings get secure enabled_accessibility_services`（清理前后各读一次）
  `adb shell dumpsys accessibility | grep -iA5 autoledger`（看 state=binding|crashed）
  `adb shell dumpsys activity services com.local.autoledger`（前台服务在不在）
  另有 `am get-standby-bucket` / `cmd appops get` / `dumpsys alarm | grep` / `cmd activity stop-app`。
- **Android 12+ 后台启动前台服务的豁免清单**里没有「自定义广播 + inexact 闹钟」，
  有「精确闹钟」「BOOT_COMPLETED」「用户已关闭电池优化」。所以看门狗必须走
  `setExactAndAllowWhileIdle` 自排链（一次性闹钟，每次执行后自己排下一次）。
- **Android 15 + targetSdk 35 禁止在 BOOT_COMPLETED 里启动 dataSync 型前台服务**
  （禁名单 dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone）。
  常驻服务已改 `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`；
  运行时类型 34+ 才传，以下传 0。
- **无障碍回调里绝不能抛异常**：服务崩一次，系统会直接停用该服务（表现为开关自己关）。
  `onAccessibilityEvent` 外面那层 `runCatching` 不许删。
- **没有 API 能自己打开无障碍开关**（root/Shizuku/ADB 除外）。看门狗检测到断链只能记日志 +
  界面如实显示 + 送用户去设置页，别做无谓的自愈尝试。
- 常驻通知 `setOngoing(true)` 在 Android 14+ 用户可手动划掉（服务还在），这是系统行为不是 bug。

## 改文件的坑

- **同一文件不要并行发多条 Edit**：后写的那条基于旧内容，会静默覆盖前一条（两边都报成功）。
  要改同一文件的多个位置，就一条一条发；改完 `Grep` 复核一遍。
- 交付前 `Grep` 复核关键词是否真的清干净（删功能时尤其重要：Manifest / 布局 / 文案 / 脚本要一起清）。
