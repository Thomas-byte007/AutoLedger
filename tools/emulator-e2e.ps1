<#
.SYNOPSIS
    AutoLedger 电脑端「模拟真机」端到端测试。

.DESCRIPTION
    在本地 Android 模拟器上跑一遍完整链路，全程不需要真机：

      1) 构建被测 APK + 两个「马甲」模拟器 APK
         （马甲的作用：NotificationListenerService 是按安装包名认来源的，
           要让它看到 pkg == com.tencent.mm 的通知，就只能真的装一个叫这名字的 App）
      2) 需要的话自动启动模拟器，安装三个 APK
      3) 打开 AutoLedger 的「通知使用权」
      4) 用 adb 广播投递假的微信 / 支付宝支付通知
      5) 把账本 SQLite 拉回电脑做断言，并截图存档
      6) 复现历史两个真机 bug：
         - 连续多笔支付是否都记上了（原来只记第一笔）
         - 删掉的记录会不会被通知重放又写回来（原来会）

.PARAMETER SkipBuild
    跳过 Gradle 构建，直接用上一次的 APK（重复跑测试时省时间）。

.PARAMETER NoBoot
    假定模拟器已经在跑，不再启动它。

.PARAMETER KeepEmulator
    测试结束后不关闭模拟器。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\emulator-e2e.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\emulator-e2e.ps1 -SkipBuild -NoBoot
#>
[CmdletBinding()]
param(
    [string]$Avd = "ledger_test",
    [string]$SdkRoot = "",
    [string]$OutDir = "",
    [string]$PythonExe = "",
    [switch]$SkipBuild,
    [switch]$NoBoot,
    [switch]$KeepEmulator
)

$ErrorActionPreference = "Stop"

# ----------------------------------------------------------------- 基础环境

$Root = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path (Split-Path -Parent $Root) "AutoLedger-build"
if (-not $OutDir) { $OutDir = Join-Path $Root "dist\emulator-e2e" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Remove-Item -LiteralPath (Join-Path $OutDir "progress.log") -Force -ErrorAction SilentlyContinue

$AppPkg     = "com.local.autoledger.debug"
$AppActivity = "$AppPkg/com.local.autoledger.ui.MainActivity"
$EditActivity = "$AppPkg/com.local.autoledger.ui.EditTransactionActivity"
$Listener   = "$AppPkg/com.local.autoledger.capture.PaymentNotificationListener"
$WechatPkg  = "com.tencent.mm"
$AlipayPkg  = "com.eg.android.AlipayGphone"
$SimRx      = "com.local.paysim.SimReceiver"

function Resolve-Sdk([string]$explicit) {
    $cands = @()
    if ($explicit) { $cands += $explicit }
    if ($env:ANDROID_SDK_ROOT) { $cands += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $cands += $env:ANDROID_HOME }
    $cands += "D:\develop\android-sdk"
    foreach ($c in $cands) {
        if ($c -and (Test-Path (Join-Path $c "platform-tools\adb.exe"))) { return $c }
    }
    throw "找不到 Android SDK，请用 -SdkRoot 指定"
}

function Resolve-Python([string]$explicit) {
    $cands = @()
    if ($explicit) { $cands += $explicit }
    $cands += (Get-ChildItem "$env:USERPROFILE\.workbuddy\binaries\python\versions\*\python.exe" -ErrorAction SilentlyContinue |
               Sort-Object FullName -Descending | ForEach-Object { $_.FullName })
    $cands += "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd) { $cands += $cmd.Source }
    foreach ($c in $cands) {
        if (-not $c -or -not (Test-Path $c)) { continue }
        # WindowsApps 下那个 python.exe 只是应用商店的占位程序，不是真解释器；
        # 所以候选必须真跑一次 import sqlite3 才算数。
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $v = & $c -c "import sqlite3,sys;print(sys.version.split()[0])" 2>&1
            $ok = ($LASTEXITCODE -eq 0 -and "$v" -match "^\d+\.\d+")
        } catch { $ok = $false } finally { $ErrorActionPreference = $saved }
        if ($ok) { return $c }
    }
    throw "找不到能用的 Python（需要标准库 sqlite3），请用 -PythonExe 指定"
}

$Sdk = Resolve-Sdk $SdkRoot
$AdbExe = Join-Path $Sdk "platform-tools\adb.exe"
$EmuExe = Join-Path $Sdk "emulator\emulator.exe"
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = "D:\develop\android-avd" }
$Py = Resolve-Python $PythonExe

$script:Failures = 0
# 只有这次是脚本自己拉起来的模拟器才负责关掉；本来就在跑的留着，方便连续调试
$script:StartedEmulator = $false

function Section($t) { Write-Host ""; Write-Host "== $t" -ForegroundColor Cyan }
function Info($t)    { Write-Host "   $t" }
function Pass($t)    { Write-Host "   [OK]   $t" -ForegroundColor Green }
function Fail($t)    { Write-Host "   [FAIL] $t" -ForegroundColor Red; $script:Failures++ }
function Check($what, [bool]$ok, $detail = "") {
    if ($ok) { Pass $what } else { Fail ("$what" + $(if ($detail) { "  -> $detail" })) }
}

function Invoke-Adb {
    param([string[]]$Cmd, [switch]$AllowFail)
    # adb 会把进度写到 stderr（例如 pull 的 "1 file pulled"）。
    # $ErrorActionPreference='Stop' 下这种 stderr 会被当成致命错误、脚本当场中断，
    # 所以调用原生程序期间必须把偏好降成 Continue。
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:AdbExe @Cmd 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    if ($code -ne 0 -and -not $AllowFail) {
        throw "adb $($Cmd -join ' ') 失败: $($out -join ' / ')"
    }
    return ($out | Out-String).Trim()
}

# 二进制安全地把设备上的文件搬回本地。
# 这里刻意用 Start-Process 而不是 `cmd /c "... > file"`：
# 后者会先过 PowerShell 的文本编码（二进制被搞坏），而且借道 cmd 容易被沙箱拦下来。
function Pull-Binary([string]$RemoteArgs, [string]$LocalPath) {
    if (Test-Path $LocalPath) { Remove-Item -LiteralPath $LocalPath -Force -ErrorAction SilentlyContinue }
    $argList = @("exec-out") + ($RemoteArgs -split '\s+')
    Start-Process -FilePath $script:AdbExe -ArgumentList $argList `
        -RedirectStandardOutput $LocalPath -NoNewWindow -Wait | Out-Null
    return ((Test-Path $LocalPath) -and ((Get-Item $LocalPath).Length -gt 0))
}

function Screenshot([string]$Name) {
    $remote = "/sdcard/_e2e_shot.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) -AllowFail | Out-Null
    $local = Join-Path $OutDir "$Name.png"
    Invoke-Adb @("pull", $remote, $local) -AllowFail | Out-Null
    if (Test-Path $local) { return $local }
    return $null
}

# 进度留痕：万一卡住，看 progress.log 最后一行就知道卡在哪一步
function Step([string]$t) {
    $line = ("[{0}] {1}" -f (Get-Date).ToString("HH:mm:ss"), $t)
    $line | Out-File -Append -Encoding utf8 (Join-Path $OutDir "progress.log")
    Write-Host "   ... $t" -ForegroundColor DarkGray
}

# ----------------------------------------------------------------- 模拟器

function Test-Device {
    $d = Invoke-Adb @("devices") -AllowFail
    return ($d -match "emulator-\d+\s+device")
}

function Wait-Boot([int]$TimeoutSec = 480) {
    # 必须先确认设备真的在 device 状态：刚 kill 掉的模拟器会有一小段时间
    # 仍然响应 adb，这时候 getprop 读到的还是旧进程的 1，会误判成"已开机"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Device) {
            $v = (Invoke-Adb @("shell", "getprop", "sys.boot_completed") -AllowFail).Trim()
            if ($v -eq "1") {
                # 再探一次系统服务是否真的活着：正在关机的模拟器同样会读到 1
                $probe = Invoke-Adb @("shell", "pm", "list", "packages", "android") -AllowFail
                if ($probe -match "package:android") { return $true }
            }
        }
        Start-Sleep -Seconds 4
    }
    return $false
}

function Start-Emulator {
    Info "启动模拟器 $Avd（AVD 在 $env:ANDROID_AVD_HOME，不再占 C 盘）"
    $emuArgs = @(
        "-avd", $Avd,
        "-no-snapshot",
        "-no-boot-anim",
        "-no-audio",
        "-gpu", "swiftshader_indirect",
        "-memory", "2048",
        "-netdelay", "none",
        "-netspeed", "full"
    )
    Start-Process -FilePath $EmuExe -ArgumentList $emuArgs -WindowStyle Minimized | Out-Null
    Invoke-Adb @("wait-for-device") -AllowFail | Out-Null
}

# ----------------------------------------------------------------- 投递假通知

function Send-Pay {
    param(
        [string]$Pkg,
        [string]$Title,
        [string]$Text,
        [int]$Id = 1001,
        [string]$Tag = "",
        [int]$Count = 1
    )
    # 文案走 Base64：adb shell 在 Windows 控制台里传中文极易乱码，
    # 乱码会直接改变解析结果，那测的就不是真实逻辑了
    $t64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Title))
    $x64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Text))
    $cmd = @(
        "shell", "am", "broadcast", "--include-stopped-packages",
        "-n", "$Pkg/$SimRx",
        "--es", "title64", $t64,
        "--es", "text64", $x64,
        "--ei", "id", "$Id",
        "--ei", "count", "$Count"
    )
    if ($Tag) { $cmd += @("--es", "tag", $Tag) }
    Invoke-Adb $cmd -AllowFail | Out-Null
    $suffix = ""
    if ($Count -gt 1) { $suffix = " x$Count" }
    Info "投递[$Pkg] $Title / $Text$suffix"
}

# ----------------------------------------------------------------- 读账本

function Get-Ledger([string]$Tag) {
    $db = Join-Path $OutDir "$Tag.db"
    $logPath = Join-Path $OutDir "$Tag.capture.log"
    foreach ($suffix in @("", "-wal", "-shm")) {
        $local = "$db$suffix"
        $ok = Pull-Binary "run-as $AppPkg cat databases/ledger.db$suffix" $local
        if (-not $ok -and (Test-Path $local)) { Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue }
    }
    # 采集日志是新版才有的可读凭据，同样走二进制通道拉，免得中文被控制台编码搞乱
    Pull-Binary "run-as $AppPkg cat files/capture.log" $logPath | Out-Null

    $jsonPath = Join-Path $OutDir "$Tag.ledger.json"
    if (Test-Path $jsonPath) { Remove-Item -LiteralPath $jsonPath -Force -ErrorAction SilentlyContinue }
    # 让 Python 把结果写成文件再按 UTF-8 读：直接吃它的 stdout 会被控制台编码（GBK）搞坏，
    # UTF-8 的中文被拆坏后连 JSON 的引号都会丢，ConvertFrom-Json 就报错
    & $Py (Join-Path $PSScriptRoot "ledger_assert.py") $db --log $logPath --out $jsonPath | Out-Null
    if (-not (Test-Path $jsonPath)) { throw "账本解析没有产出 $jsonPath" }
    $raw = Get-Content -Raw -Encoding UTF8 $jsonPath
    try {
        return ($raw | ConvertFrom-Json)
    } catch {
        $head = $raw
        if ($head.Length -gt 300) { $head = $head.Substring(0, 300) }
        throw "解析账本失败: $head"
    }
}

function Count-Rows($ledger) { if ($ledger.rows) { return $ledger.rows.Count } else { return 0 } }

function Find-Row($ledger, [long]$Cents) {
    if (-not $ledger.rows) { return $null }
    return ($ledger.rows | Where-Object { $_.amount_cents -eq $Cents } | Select-Object -First 1)
}

# ----------------------------------------------------------------- UI 自动化

function Get-Ui {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/ui.xml") -AllowFail | Out-Null
    $local = Join-Path $OutDir "_ui.xml"
    Invoke-Adb @("pull", "/sdcard/ui.xml", $local) -AllowFail | Out-Null
    if (-not (Test-Path $local)) { return $null }
    return [xml](Get-Content -Raw -Encoding UTF8 $local)
}

function Find-Node($doc, [string]$ResourceIdSuffix, [string]$Text) {
    if (-not $doc) { return $null }
    foreach ($n in $doc.SelectNodes("//node")) {
        $rid = $n.GetAttribute("resource-id")
        if ($ResourceIdSuffix -and $rid -and $rid.EndsWith($ResourceIdSuffix)) { return $n }
    }
    foreach ($n in $doc.SelectNodes("//node")) {
        if ($Text -and ($n.GetAttribute("text") -eq $Text -or $n.GetAttribute("content-desc") -eq $Text)) { return $n }
    }
    return $null
}

function Tap-Node($node) {
    if (-not $node) { return $false }
    $b = $node.GetAttribute("bounds")
    if ($b -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $false }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-Adb @("shell", "input", "tap", "$x", "$y") -AllowFail | Out-Null
    return $true
}

function Grant-AppRuntimePerms {
    # pm clear 会把运行时授权一起打回未授权
    foreach ($perm in @(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS")) {
        Invoke-Adb @("shell", "pm", "grant", $AppPkg, $perm) -AllowFail | Out-Null
    }
}

function Get-TopActivity {
    $d = Invoke-Adb @("shell", "dumpsys", "activity", "activities") -AllowFail
    $hit = ($d -split "`n" | Where-Object { $_ -match "topResumedActivity" } | Select-Object -First 1)
    if ($hit -and $hit -match '([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)') { return "$($Matches[1])/$($Matches[2])" }
    return ""
}

# 系统权限框会盖在 App 上面，uiautomator 只能 dump 到对话框本身，
# 后面的界面操作就全找不到控件了。每次启动界面前先把它清掉。
function Dismiss-SystemDialog {
    $top = Get-TopActivity
    if ($top -notmatch "permissioncontroller|packageinstaller") { return $false }
    $ui = Get-Ui
    $btn = Find-Node $ui $null "允许"
    if (-not $btn) { $btn = Find-Node $ui $null "Allow" }
    if ($btn -and (Tap-Node $btn)) {
        Start-Sleep -Seconds 2
        return $true
    }
    Invoke-Adb @("shell", "input", "keyevent", "4") -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    return $true
}

# ================================================================== 流程开始

Write-Host ""
Write-Host "AutoLedger 电脑端端到端测试" -ForegroundColor White
Info "仓库    $Root"
Info "构建目录 $BuildRoot"
Info "产物目录 $OutDir"
Info "SDK     $Sdk"
Info "Python  $Py"

# --- 1. 构建 ---------------------------------------------------------------
if (-not $SkipBuild) {
    Section "1/7 构建 APK"
    $env:JAVA_HOME = "D:\develop\Java\jdk-17"
    Push-Location $Root
    try {
        $buildLog = Join-Path $OutDir "build.log"
        & (Join-Path $Root "gradlew.bat") ":app:assembleDebug" ":paysim:assembleWechatDebug" ":paysim:assembleAlipayDebug" `
            --console=plain *> $buildLog
        Check "Gradle 构建通过" ($LASTEXITCODE -eq 0) "详见 $buildLog"
    } finally { Pop-Location }
} else {
    Section "1/7 构建 APK（已跳过）"
}

$appApk = Join-Path $BuildRoot "app\outputs\apk\debug\app-debug.apk"
$wechatApk = Join-Path $BuildRoot "paysim\outputs\apk\wechat\debug\paysim-wechat-debug.apk"
$alipayApk = Join-Path $BuildRoot "paysim\outputs\apk\alipay\debug\paysim-alipay-debug.apk"
foreach ($p in @($appApk, $wechatApk, $alipayApk)) {
    Check ("找到 " + (Split-Path $p -Leaf)) (Test-Path $p) $p
}
if ($script:Failures -gt 0) { Write-Host "`n构建产物不全，先停下。" -ForegroundColor Red; exit 1 }

# --- 2. 模拟器 -------------------------------------------------------------
Section "2/7 准备模拟器"
if (-not (Test-Device)) {
    if ($NoBoot) { Write-Host "指定了 -NoBoot 但没有可用设备。" -ForegroundColor Red; exit 1 }
    Start-Emulator
    $script:StartedEmulator = $true
}
if (-not (Wait-Boot)) { Write-Host "模拟器启动超时。" -ForegroundColor Red; exit 1 }
Pass "模拟器已就绪（$(Invoke-Adb @('shell','getprop','ro.build.version.sdk'))，API 35）"

# --- 3. 安装 ---------------------------------------------------------------
Section "3/7 安装应用与模拟器"
foreach ($item in @(@($appApk, "AutoLedger"), @($wechatApk, "微信马甲"), @($alipayApk, "支付宝马甲"))) {
    $r = Invoke-Adb @("install", "-r", "-g", $item[0]) -AllowFail
    Check ("安装 " + $item[1]) ($r -match "Success") ($r -replace "`r?`n", " ")
}
foreach ($p in @($WechatPkg, $AlipayPkg)) {
    Invoke-Adb @("shell", "pm", "grant", $p, "android.permission.POST_NOTIFICATIONS") -AllowFail | Out-Null
}

# --- 4. 授权 ---------------------------------------------------------------
Section "4/7 打开通知使用权"
Step "重置应用数据"
Invoke-Adb @("shell", "pm", "clear", $AppPkg) -AllowFail | Out-Null
Step "补回运行时权限（pm clear 会把它们打回未授权，否则启动时会弹系统框盖住界面）"
Grant-AppRuntimePerms
Step "打开通知使用权"
Invoke-Adb @("shell", "cmd", "notification", "allow_listener", $Listener) | Out-Null
# 这条设置是异步落下去的，紧接着读会读到旧值（pm clear 刚把监听权清掉），所以轮询等一会儿
$enabled = ""
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
    $enabled = Invoke-Adb @("shell", "settings", "get", "secure", "enabled_notification_listeners") -AllowFail
    if ($enabled -match [regex]::Escape($AppPkg)) { break }
    Start-Sleep -Seconds 2
}
Check "AutoLedger 已获得通知使用权" ($enabled -match [regex]::Escape($AppPkg)) $enabled

# 先跑一次界面，让 Application 初始化数据库，也让进程脱离 stopped 状态
Step "启动主界面"
Invoke-Adb @("shell", "am", "start", "-W", "-n", $AppActivity) -AllowFail | Out-Null
Start-Sleep -Seconds 3
Dismiss-SystemDialog | Out-Null
Check "应用可正常启动" ((Invoke-Adb @("shell", "pidof", $AppPkg) -AllowFail).Trim().Length -gt 0)

$shot = Screenshot "01-初始界面"
if ($shot) { Pass "截图 $shot" }

# --- 5. 连续多笔支付 -------------------------------------------------------
Section "5/7 投递多笔支付通知（复现「只记第一笔」）"

# 场景 A：连续多笔、金额各不相同。历史上因为判重只看「金额+方向+时间窗」，
# 后面的会被当成重复吃掉；现在按「同来源要求原文一致」判重，应该都记上。
Send-Pay $WechatPkg "微信支付" "微信支付：向 星巴克 付款 28.00 元" -Id 2001
Send-Pay $WechatPkg "微信支付" "微信支付：向 全家便利店 付款 9.50 元" -Id 2002
Send-Pay $WechatPkg "微信支付" "微信支付：向 滴滴出行 付款 36.80 元" -Id 2003
Send-Pay $AlipayPkg "支付宝"   "支付成功 25.00元" -Id 3001
Send-Pay $AlipayPkg "支付宝"   "收款成功 88.00元" -Id 3002
Start-Sleep -Seconds 4

$l1 = Get-Ledger "after-batch"
Check "5 笔支付全部入账" ((Count-Rows $l1) -eq 5) ("实际 " + (Count-Rows $l1) + " 条")
foreach ($expect in @(
        @(2800, "星巴克"), @(950, "全家便利店"), @(3680, "滴滴出行"), @(2500, "支付宝"), @(8800, "支付宝"))) {
    $row = Find-Row $l1 $expect[0]
    Check ("金额 " + ($expect[0] / 100) + " 元已入账") ($row -ne $null)
    if ($row) { Info ("    分类=" + $row.category_key + " 商户=" + $row.merchant + " 来源=" + $row.channel + " 商户匹配=" + ($row.merchant -eq $expect[1])) }
}
$income = $null
if ($l1.rows) { $income = ($l1.rows | Where-Object { $_.amount_cents -eq 8800 } | Select-Object -First 1) }
if ($income) { Check "88 元被识别为收入（direction=1）" ($income.direction -eq 1) ("实际 direction=" + $income.direction) }

# --- 6. 同一条通知被反复投递 ------------------------------------------------
Section "6/7 同一条通知重复投递不应重复记账"
$before = Count-Rows $l1
Send-Pay $WechatPkg "微信支付" "微信支付：向 瑞幸咖啡 付款 19.90 元" -Id 2100 -Tag "dup"
Start-Sleep -Seconds 2
Send-Pay $WechatPkg "微信支付" "微信支付：向 瑞幸咖啡 付款 19.90 元" -Id 2100 -Tag "dup" -Count 2
Start-Sleep -Seconds 4

$l2 = Get-Ledger "after-dup"
Check "19.9 元只记了一次" ((Count-Rows $l2) -eq ($before + 1)) ("期望 " + ($before + 1) + " 条，实际 " + (Count-Rows $l2) + " 条")

# --- 7. 删掉的记录不许复活 --------------------------------------------------
Section "7/7 删除后通知重放不应把记录写回来（复现「删了还在」）"

# 用一笔独一无二的支付来做这个场景，免得跟前面同金额的记录混在一起
$delText = "微信支付：向 美团外卖 付款 45.60 元"
Send-Pay $WechatPkg "微信支付" $delText -Id 2200 -Tag "del"
Start-Sleep -Seconds 4
$l3 = Get-Ledger "before-delete"
Check "待删除的那笔已入账" ((Count-Rows $l3) -eq ((Count-Rows $l2) + 1)) ("实际 " + (Count-Rows $l3) + " 条")

# 走真实用户路径：明细里点这条 -> 进编辑页 -> 点删除 -> 确认。
# 不用 am start 直接拉起编辑页：它是 exported=false，adb shell 拉不起来，
# 而且走点击也更接近用户真实操作。
Step "回明细页，点开那笔 45.60 元"
Invoke-Adb @("shell", "am", "start", "-W", "-n", $AppActivity) -AllowFail | Out-Null
Start-Sleep -Seconds 2
Dismiss-SystemDialog | Out-Null
$ui = Get-Ui
$cell = Find-Node $ui $null "美团外卖"
if (-not $cell) { $cell = Find-Node $ui $null "-45.60" }
Check "在明细列表里找到这笔记录" ($cell -ne $null)
Tap-Node $cell | Out-Null
Start-Sleep -Seconds 3
Dismiss-SystemDialog | Out-Null
$top = Get-TopActivity
Check "点击后进入了编辑页" ($top -match "EditTransaction") ("当前前台: " + $top)

Step "点删除，并在确认框上确认"
$ui2 = Get-Ui
$btn = Find-Node $ui2 "btnDelete" $null
Check "编辑页上有删除按钮（且记录已加载出来）" ($btn -ne $null)
Tap-Node $btn | Out-Null
Start-Sleep -Seconds 2
$ui3 = Get-Ui
$yes = Find-Node $ui3 $null "删除"
Check "弹出删除确认框并点到「删除」" (Tap-Node $yes)
Start-Sleep -Seconds 3

$l4 = Get-Ledger "after-delete"
Check "记录已从库里删掉" ((Count-Rows $l4) -eq ((Count-Rows $l3) - 1)) ("期望 " + ((Count-Rows $l3) - 1) + " 条，实际 " + (Count-Rows $l4) + " 条")
Check "墓碑已写入 deleted_txn" ($l4.deleted -ge 1) ("deleted_txn = " + $l4.deleted)

# 关键一步：把同一条通知再投一次，看它会不会又长回来
Step "重放同一条通知，看记录会不会复活"
Send-Pay $WechatPkg "微信支付" $delText -Id 2200 -Tag "del"
Start-Sleep -Seconds 4
$l5 = Get-Ledger "after-replay"
Check "通知重放后记录没有复活" ((Count-Rows $l5) -eq (Count-Rows $l4)) ("删后 " + (Count-Rows $l4) + " 条，重放后 " + (Count-Rows $l5) + " 条")
Check "库里确实找不到那笔 45.60 元" ((Find-Row $l5 4560) -eq $null)

# --- 收尾 ------------------------------------------------------------------
Section "截图与日志存档"
Invoke-Adb @("shell", "am", "start", "-W", "-n", $AppActivity) -AllowFail | Out-Null
Start-Sleep -Seconds 2
$ui = Get-Ui
Tap-Node (Find-Node $ui $null "明细") | Out-Null
Start-Sleep -Seconds 2
$s = Screenshot "02-明细列表"
if ($s) { Pass "截图 $s" }

# 采集日志入口在「我的」里。LogActivity 同样是 exported=false，不能 am start，得点进去。
Step "进「我的」→「采集日志」"
$ui = Get-Ui
Tap-Node (Find-Node $ui $null "我的") | Out-Null
Start-Sleep -Seconds 2
$ui = Get-Ui
$row = Find-Node $ui $null "采集日志"
if ($row) {
    Tap-Node $row | Out-Null
    Start-Sleep -Seconds 2
    $s = Screenshot "03-采集日志"
    if ($s) { Pass "截图 $s" }
} else {
    Fail "没能在「我的」里找到「采集日志」入口"
}

$finalPath = Join-Path $OutDir "capture.log"
if (Pull-Binary "run-as $AppPkg cat files/capture.log" $finalPath) {
    Pass "采集日志已存到 $finalPath"
}

if (-not $KeepEmulator -and $script:StartedEmulator) {
    Info "关闭模拟器（本次是脚本启动的）"
    Invoke-Adb @("emu", "kill") -AllowFail | Out-Null
}

Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "全部通过。" -ForegroundColor Green
    Write-Host "产物在 $OutDir"
    exit 0
} else {
    Write-Host "$script:Failures 项未通过，请看上面的 [FAIL] 行。" -ForegroundColor Red
    Write-Host "产物在 $OutDir"
    exit 1
}
