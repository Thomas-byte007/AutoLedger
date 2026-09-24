<#
.SYNOPSIS
    随手记新版界面的可视化验收：把新增页面挨个跑一遍并截图。

.DESCRIPTION
    和 emulator-e2e.ps1 的区别：那个脚本验的是「记账逻辑对不对」，
    这个验的是「界面能不能用」——记一笔、分类选择、添加分类、报表下钻。

    每一步都截图 + dump 一份 UI 树，跑完把产物丢在 dist/ui-verify/。
    模拟器的生命周期全在这一个脚本里，跑完才关。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\ui-verify.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$Avd = "ledger_test",
    [string]$SdkRoot = "",
    [string]$OutDir = "",
    [switch]$SkipBuild,
    [switch]$KeepEmulator
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
if ($Root -match '^[\x20-\x7E]+$') {
    $AppBuild = Join-Path $Root "app\build"
    $SimBuild = Join-Path $Root "paysim\build"
} else {
    $Base = Join-Path (Split-Path -Parent $Root) "AutoLedger-build"
    $AppBuild = Join-Path $Base "app"
    $SimBuild = Join-Path $Base "paysim"
}
if (-not $OutDir) { $OutDir = Join-Path $Root "dist\ui-verify" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$AppPkg      = "com.local.autoledger.debug"
$AppActivity = "$AppPkg/com.local.autoledger.ui.MainActivity"
$Listener    = "$AppPkg/com.local.autoledger.capture.PaymentNotificationListener"
$WechatPkg   = "com.tencent.mm"
$AlipayPkg   = "com.eg.android.AlipayGphone"
$SimRx       = "com.local.paysim.SimReceiver"

function Resolve-Sdk([string]$explicit) {
    $cands = @()
    if ($explicit) { $cands += $explicit }
    if ($env:ANDROID_SDK_ROOT) { $cands += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $cands += $env:ANDROID_HOME }
    $cands += "D:\develop\android-sdk"
    foreach ($c in $cands) {
        if ($c -and (Test-Path (Join-Path $c "platform-tools\adb.exe"))) { return $c }
    }
    throw "找不到 Android SDK"
}

$Sdk = Resolve-Sdk $SdkRoot
$AdbExe = Join-Path $Sdk "platform-tools\adb.exe"
$EmuExe = Join-Path $Sdk "emulator\emulator.exe"
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = "D:\develop\android-avd" }

$script:StartedEmulator = $false
$script:Failures = 0

function Info($t) { Write-Host "   $t" }
function Pass($t) { Write-Host "   [OK]   $t" -ForegroundColor Green }
function Fail($t) { Write-Host "   [FAIL] $t" -ForegroundColor Red; $script:Failures++ }
function Step($t) { Write-Host "   ... $t" -ForegroundColor DarkGray }

# 这台机器上的 adb 连接会偶发掉线（device offline / device not found），
# 一旦掉线，后面每一条 adb 命令都会静默失败 —— 点击落在错的地方、dump 拿到旧画面，
# 最终表现成一堆「找不到控件」的假失败。所以这里统一重试 + 掉线时重建 server。
$script:AdbOfflineHits = @("device offline", "device not found", "no devices/emulators found", "error: closed", "connection reset")

function Invoke-Adb {
    param([string[]]$Cmd, [switch]$AllowFail)
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = ""
    $code = 0
    try {
        for ($try = 1; $try -le 4; $try++) {
            $out = & $script:AdbExe @Cmd 2>&1
            $code = $LASTEXITCODE
            $text = ($out | Out-String)
            $offline = $false
            foreach ($hit in $script:AdbOfflineHits) {
                if ($text -match [regex]::Escape($hit)) { $offline = $true; break }
            }
            if (-not $offline) { break }
            # 掉线了：重启 server 再等设备回来，然后重放这条命令
            & $script:AdbExe kill-server  2>&1 | Out-Null
            Start-Sleep -Milliseconds 800
            & $script:AdbExe start-server 2>&1 | Out-Null
            & $script:AdbExe wait-for-device 2>&1 | Out-Null
            Start-Sleep -Milliseconds 800
        }
    } finally {
        $ErrorActionPreference = $saved
    }
    if ($code -ne 0 -and -not $AllowFail) { throw "adb $($Cmd -join ' ') 失败: $out" }
    return ($out | Out-String).Trim()
}

function Test-Device {
    $d = Invoke-Adb @("devices") -AllowFail
    return ($d -match "emulator-\d+\s+device")
}

function Wait-Boot([int]$TimeoutSec = 420) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Device) {
            $v = (Invoke-Adb @("shell", "getprop", "sys.boot_completed") -AllowFail).Trim()
            if ($v -eq "1") {
                $probe = Invoke-Adb @("shell", "pm", "list", "packages", "android") -AllowFail
                if ($probe -match "package:android") { return $true }
            }
        }
        Start-Sleep -Seconds 4
    }
    return $false
}

function Start-Emulator {
    # 网络走直连 + 软件渲染，模拟器才能在这台机器上稳定起来
    $emuArgs = @(
        "-avd", $Avd, "-no-snapshot", "-no-boot-anim", "-no-audio",
        "-gpu", "swiftshader_indirect", "-memory", "2048",
        "-netdelay", "none", "-netspeed", "full"
    )
    Start-Process -FilePath $EmuExe -ArgumentList $emuArgs -WindowStyle Minimized | Out-Null
    Invoke-Adb @("wait-for-device") -AllowFail | Out-Null
}

function Screenshot([string]$Name) {
    $remote = "/sdcard/_ui_shot.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) -AllowFail | Out-Null
    $local = Join-Path $OutDir "$Name.png"
    Invoke-Adb @("pull", $remote, $local) -AllowFail | Out-Null
    if (Test-Path $local) { Pass "截图 $Name.png" } else { Fail "截图 $Name 失败" }
}

function Show-UiTag($doc) {
    # 调试用：dump 里有哪些文案（uiautomator 只含当前可见节点）
    if (-not $doc) { return "<null>" }
    $vals = @()
    foreach ($n in $doc.SelectNodes("//node")) {
        foreach ($a in @("text", "content-desc")) {
            $v = $n.GetAttribute($a)
            if ($v -and $vals -notcontains $v) { $vals += $v }
        }
    }
    return ($vals | Select-Object -First 20) -join " | "
}

# dump + pull 经常失败或抓到上一份缓存，必须重试并校验产物。
# 关键：dump 前先删掉设备上的旧文件 —— 否则 uiautomator 失败时会把上一屏的
# xml 原样拉回来，断言全部落在旧画面上，报出一堆假失败（踩过，很费时间）。
function Get-Ui([string]$Tag, [switch]$Quiet) {
    $remote = "/sdcard/ui.xml"
    $local = Join-Path $OutDir "ui-$Tag.xml"
    for ($i = 0; $i -lt 5; $i++) {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Invoke-Adb @("shell", "rm", "-f", $remote) -AllowFail | Out-Null
        $dumped = Invoke-Adb @("shell", "uiautomator", "dump", $remote) -AllowFail
        Invoke-Adb @("pull", $remote, $local) -AllowFail | Out-Null
        if ($dumped -match "dumped" -and (Test-Path $local) -and ((Get-Item $local).Length -gt 200)) {
            try { return [xml](Get-Content -Raw -Encoding UTF8 $local) } catch { }
        }
        Start-Sleep -Milliseconds 900
    }
    if (-not $Quiet) { Fail "UI 树抓取失败：$Tag" }
    return $null
}

# 让 App 回到一个确定的起点：冷启动 + 停在「明细」tab。
# 不这么做的话，am start 只是把上次的 Activity 拉到前台，会带着上一轮留下的 tab
# （上一轮结尾停在「我的」），后面所有的点击就全错位了。
function Reset-App {
    for ($i = 0; $i -lt 3; $i++) {
        Invoke-Adb @("shell", "am", "force-stop", $AppPkg) -AllowFail | Out-Null
        Start-Sleep -Seconds 2
        $killPid = (Invoke-Adb @("shell", "pidof", $AppPkg) -AllowFail).Trim()
        if (-not $killPid) { break }
    }
    # v1.4.1 起冷启动会弹「开启辅助记账」引导框，会盖住后面所有点击。
    # 这里**故意不授予无障碍**，只把引导框点掉：无障碍服务一旦真的连上，
    # uiautomator 会一直等不到 idle 状态，之后所有 dump 全部失败。
    Revoke-Accessibility
    Invoke-Adb @("shell", "am", "start", "-W", "-n", $AppActivity) -AllowFail | Out-Null
    Start-Sleep -Seconds 4
    Dismiss-AccessibilityPrompt
    # 冷启动后默认就在「明细」；已经冷启动过（进程被复用）时这里把 tab 拨回来
    $ui = Get-Ui "00-reset"
    $tab = Find-Node $ui $null "明细"
    if ($tab) { Tap-Node $tab | Out-Null; Start-Sleep -Seconds 2 }
    return (Get-Ui "00-reset-detail")
}

# 把无障碍授权清掉。不是为了让引导框「不弹」，恰恰相反：
# 让 Prefs 与系统两边都是「未开启」，启动路径才是确定的、可重复的。
# 真正把无障碍服务连上会让 uiautomator 永远等不到 idle，dump 全灭。
function Revoke-Accessibility {
    Invoke-Adb @("shell", "settings", "put", "secure", "enabled_accessibility_services", "null") -AllowFail | Out-Null
    Invoke-Adb @("shell", "settings", "put", "secure", "accessibility_enabled", "0") -AllowFail | Out-Null
}

# 冷启动后若「开启辅助记账」引导框冒出来，点「取消」把它收掉再继续。
function Dismiss-AccessibilityPrompt {
    $ui = Get-Ui "00-dismiss-prompt" -Quiet
    if (-not $ui) { return }
    $btn = Find-Node $ui $null "取消"
    if ($btn) {
        Tap-Node $btn | Out-Null
        Start-Sleep -Seconds 2
    }
}

function Wait-Top([string]$Pattern, [int]$Tries = 6) {
    for ($i = 0; $i -lt $Tries; $i++) {
        Start-Sleep -Seconds 1
        if ((Get-TopActivity) -match $Pattern) { return $true }
    }
    return $false
}

function Find-Node($doc, [string]$ResourceIdSuffix, [string]$Text) {
    if (-not $doc) { return $null }
    if ($ResourceIdSuffix) {
        foreach ($n in $doc.SelectNodes("//node")) {
            $rid = $n.GetAttribute("resource-id")
            if ($rid -and $rid.EndsWith($ResourceIdSuffix)) { return $n }
        }
    }
    if ($Text) {
        foreach ($n in $doc.SelectNodes("//node")) {
            if ($n.GetAttribute("text") -eq $Text -or $n.GetAttribute("content-desc") -eq $Text) { return $n }
        }
    }
    return $null
}

function Find-Nodes($doc, [string]$ResourceIdSuffix, [string]$Text) {
    $hits = @()
    if (-not $doc) { return $hits }
    foreach ($n in $doc.SelectNodes("//node")) {
        if ($ResourceIdSuffix) {
            $rid = $n.GetAttribute("resource-id")
            if ($rid -and $rid.EndsWith($ResourceIdSuffix)) { $hits += $n }
        }
        if ($Text -and -not $ResourceIdSuffix) {
            if ($n.GetAttribute("text") -eq $Text -or $n.GetAttribute("content-desc") -eq $Text) { $hits += $n }
        }
    }
    return $hits
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

function Tap-Text($doc, [string]$Text) {
    $n = Find-Node $doc $null $Text
    if (Tap-Node $n) { Pass "点了「$Text」"; return $true }
    Fail "找不到可点的「$Text」"
    Info "  当前界面：$(Show-UiTag $doc)"
    return $false
}

function Tap-Id($doc, [string]$Id) {
    $n = Find-Node $doc $Id $null
    if (Tap-Node $n) { Pass "点了 #$Id"; return $true }
    Fail "找不到控件 #$Id"
    Info "  当前界面：$(Show-UiTag $doc)"
    return $false
}

# 长页面里的文案要滚过去收集：uiautomator dump 只包含当前可见的节点
function Collect-Seen($doc, $bag) {
    if (-not $doc) { return }
    foreach ($n in $doc.SelectNodes("//node")) {
        $t = $n.GetAttribute("text"); if ($t) { $bag[$t] = $true }
        $d = $n.GetAttribute("content-desc"); if ($d) { $bag[$d] = $true }
    }
}

# 长页面里找一个可点条目：先看当前屏，找不到就往下滚再找
function Tap-TextScrolling([string]$Text, [int]$MaxScrolls = 4) {
    for ($i = 0; $i -le $MaxScrolls; $i++) {
        $ui = Get-Ui "find-$($Text -replace '[^0-9A-Za-z]', '')-$i"
        $n = Find-Node $ui $null $Text
        if ($n) { Tap-Node $n | Out-Null; Pass "点了「$Text」"; return $true }
        Scroll-Down
    }
    Fail "滚动查找也没找到「$Text」"
    return $false
}

# 采集日志的「行数」可以当作「已经收到几笔通知」的计数器（第一行是「使用权已连接」）
function Get-CaptureLogLines {
    $r = Invoke-Adb @("shell", "run-as", $AppPkg, "cat", "files/capture.log") -AllowFail
    if (-not $r -or $r -match "No such file|Permission denied") { return 0 }
    return @(($r -split "`n") | Where-Object { $_.Trim() -ne "" }).Count
}

# 通知使用权（NotificationListenerService）要显式打开，且可能偶发失败
function Set-ListenerEnabled {
    for ($i = 0; $i -lt 3; $i++) {
        Invoke-Adb @("shell", "cmd", "notification", "allow_listener", $Listener) -AllowFail | Out-Null
        Start-Sleep -Milliseconds 700
        $d = Invoke-Adb @("shell", "settings", "get", "secure", "enabled_notification_listeners") -AllowFail
        if ($d -match "autoledger") { return $true }
    }
    Fail "通知使用权没打开"
    return $false
}

function Grant-AppRuntimePerms {
    foreach ($perm in @("android.permission.POST_NOTIFICATIONS")) {
        Invoke-Adb @("shell", "pm", "grant", $AppPkg, $perm) -AllowFail | Out-Null
    }
}

# 输入法弹出来会盖住对话框底部按钮，点之前先收掉。
# 只有确认它真的在显示才按返回键，否则会把对话框一起关掉。
function Get-ScreenSize {
    $s = Invoke-Adb @("shell", "wm", "size") -AllowFail
    if ($s -match "(\d+)x(\d+)") { return @([int]$Matches[1], [int]$Matches[2]) }
    return @(1080, 1920)
}

# uiautomator 的 dump 里只有「当前可见」的节点，长页面首屏之外的东西查不到，
# 所以断言长页面上的模块之前必须先滚过去。
function Scroll-Down([int]$Times = 1) {
    $sz = Get-ScreenSize
    $cx = [int]($sz[0] / 2)
    $from = [int]($sz[1] * 0.72)
    $to = [int]($sz[1] * 0.28)
    for ($i = 0; $i -lt $Times; $i++) {
        Invoke-Adb @("shell", "input", "swipe", "$cx", "$from", "$cx", "$to", "300") -AllowFail | Out-Null
        Start-Sleep -Milliseconds 800
    }
}

function Scroll-Up([int]$Times = 1) {
    $sz = Get-ScreenSize
    $cx = [int]($sz[0] / 2)
    $from = [int]($sz[1] * 0.28)
    $to = [int]($sz[1] * 0.78)
    for ($i = 0; $i -lt $Times; $i++) {
        Invoke-Adb @("shell", "input", "swipe", "$cx", "$from", "$cx", "$to", "300") -AllowFail | Out-Null
        Start-Sleep -Milliseconds 800
    }
}

function Hide-Keyboard {
    $d = Invoke-Adb @("shell", "dumpsys", "input_method") -AllowFail
    if ($d -match "mInputShown=true") {
        Invoke-Adb @("shell", "input", "keyevent", "4") -AllowFail | Out-Null
        Start-Sleep -Milliseconds 900
    }
}

function Dismiss-SystemDialog {
    $d = Invoke-Adb @("shell", "dumpsys", "activity", "activities") -AllowFail
    $hit = ($d -split "`n" | Where-Object { $_ -match "topResumedActivity" } | Select-Object -First 1)
    if ($hit -notmatch "permissioncontroller|packageinstaller") { return }
    $ui = Get-Ui "sysdialog"
    $btn = Find-Node $ui $null "允许"
    if (-not $btn) { $btn = Find-Node $ui $null "Allow" }
    if ($btn) { Tap-Node $btn | Out-Null } else { Invoke-Adb @("shell", "input", "keyevent", "4") -AllowFail | Out-Null }
    Start-Sleep -Seconds 2
}

function Type-Into([string]$Text) {
    # 先清空：分类名最长 6 个字，多按几次 DEL 一定清干净
    for ($i = 0; $i -lt 12; $i++) {
        Invoke-Adb @("shell", "input", "keyevent", "67") -AllowFail | Out-Null
    }
    Start-Sleep -Milliseconds 300
    Invoke-Adb @("shell", "input", "text", $Text) -AllowFail | Out-Null
    Start-Sleep -Milliseconds 500
}

function Go-Home {
    for ($i = 0; $i -lt 4; $i++) {
        if ((Get-TopActivity) -match "MainActivity") { return }
        Invoke-Adb @("shell", "input", "keyevent", "4") -AllowFail | Out-Null
        Start-Sleep -Milliseconds 900
    }
}

function Test-KeepAliveService {
    $d = Invoke-Adb @("shell", "dumpsys", "activity", "services", "KeepAliveService") -AllowFail
    return ($d -match "KeepAliveService" -and $d -match "isForeground=true")
}

function Get-TopActivity {
    $d = Invoke-Adb @("shell", "dumpsys", "activity", "activities") -AllowFail
    $hit = ($d -split "`n" | Where-Object { $_ -match "topResumedActivity" } | Select-Object -First 1)
    if ($hit -and $hit -match '([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)') { return "$($Matches[1])/$($Matches[2])" }
    return ""
}

function Send-Pay {
    param([string]$Pkg, [string]$Title, [string]$Text, [int]$Id = 1001, [string]$Tag = "")
    $t64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Title))
    $x64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Text))
    $cmd = @("shell", "am", "broadcast", "--include-stopped-packages", "-n", "$Pkg/$SimRx",
        "--es", "title64", $t64, "--es", "text64", $x64, "--ei", "id", "$Id")
    if ($Tag) { $cmd += @("--es", "tag", $Tag) }
    Invoke-Adb $cmd -AllowFail | Out-Null
}

# ================================================================== 流程

Write-Host ""
Write-Host "随手记 新版界面可视化验收" -ForegroundColor White

if (-not $SkipBuild) {
    Write-Host "`n== 构建"
    $env:JAVA_HOME = "D:\develop\Java\jdk-17"
    Push-Location $Root
    try {
        $log = Join-Path $OutDir "build.log"
        & (Join-Path $Root "gradlew.bat") ":app:assembleDebug" ":paysim:assembleWechatDebug" ":paysim:assembleAlipayDebug" --console=plain *> $log
        if ($LASTEXITCODE -ne 0) { Fail "构建失败，见 $log"; exit 1 }
        Pass "Gradle 构建通过"
    } finally { Pop-Location }
}

Write-Host "`n== 准备模拟器"
if (-not (Test-Device)) {
    Start-Emulator
    $script:StartedEmulator = $true
}
if (-not (Wait-Boot)) { Write-Host "模拟器启动超时" -ForegroundColor Red; exit 1 }
Pass "模拟器已就绪"
Info "屏幕 $(Invoke-Adb @('shell','wm','size') -AllowFail) / $(Invoke-Adb @('shell','wm','density') -AllowFail)"

Write-Host "`n== 安装"
$appApk = Join-Path $AppBuild "outputs\apk\debug\app-debug.apk"
$wechatApk = Join-Path $SimBuild "outputs\apk\wechat\debug\paysim-wechat-debug.apk"
$alipayApk = Join-Path $SimBuild "outputs\apk\alipay\debug\paysim-alipay-debug.apk"
foreach ($item in @(@($appApk, "AutoLedger"), @($wechatApk, "微信马甲"), @($alipayApk, "支付宝马甲"))) {
    $r = Invoke-Adb @("install", "-r", "-g", $item[0]) -AllowFail
    if ($r -match "Success") { Pass "安装 $($item[1])" } else { Fail "安装 $($item[1]) -> $r" }
}

Write-Host "`n== 造数据"
Invoke-Adb @("shell", "pm", "clear", $AppPkg) -AllowFail | Out-Null
Grant-AppRuntimePerms
Set-ListenerEnabled
Invoke-Adb @("shell", "am", "start", "-W", "-n", $AppActivity) -AllowFail | Out-Null
Start-Sleep -Seconds 4
Dismiss-SystemDialog
# pm clear 之后 Prefs 也没了，冷启动会弹「开启辅助记账」，先收掉再去等通知监听
Dismiss-AccessibilityPrompt

# 关键：清数据后通知监听要重新绑定，绑上之前发出去的假支付会全部漏掉（踩过）。
# capture.log 第一行就是「通知使用权已连接」，等它出现再发通知。
$ready = $false
for ($i = 0; $i -lt 25; $i++) {
    if ((Get-CaptureLogLines) -ge 1) { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if ($ready) { Pass "通知监听已连上" } else { Fail "等不到通知监听连接" }

$pays = @(
    @($WechatPkg, "微信支付", "微信支付：向 星巴克 付款 28.00 元"),
    @($WechatPkg, "微信支付", "微信支付：向 三只松鼠 付款 36.00 元"),
    @($WechatPkg, "微信支付", "微信支付：向 滴滴出行 付款 36.80 元"),
    @($AlipayPkg, "支付宝", "支付成功 25.00元")
)
$baseLines = Get-CaptureLogLines
$round = 0
while ($round -lt 3) {
    $round++
    for ($i = 0; $i -lt $pays.Count; $i++) {
        Send-Pay $pays[$i][0] $pays[$i][1] $pays[$i][2] -Id (5001 + $i + ($round - 1) * 100)
    }
    # 通知是异步投递的，等齐再判断；只增长了一两笔不能算收工
    for ($w = 0; $w -lt 10; $w++) {
        if (((Get-CaptureLogLines) - $baseLines) -ge $pays.Count) { break }
        Start-Sleep -Seconds 1
    }
    if (((Get-CaptureLogLines) - $baseLines) -ge $pays.Count) { break }
    Info "第 $round 轮只收到 $((Get-CaptureLogLines) - $baseLines)/$($pays.Count) 笔，补发一次"
}
$got = (Get-CaptureLogLines) - $baseLines
if ($got -ge $pays.Count) { Pass "已投递并捕获 $got 笔假支付" } else { Fail "只捕获到 $got / $($pays.Count) 笔假支付" }

# 从确定的起点开始（冷启动 + 明细 tab），否则后面所有点击都可能错位
$ui = Reset-App
Screenshot "01-明细列表"

# ------------------------------------------------------------------ 记一笔
Write-Host "`n== 记一笔页"
if (Tap-Text $ui "手动记一笔") {
    Start-Sleep -Seconds 2
    $top = Get-TopActivity
    if ($top -match "EditTransaction") { Pass "打开了记一笔页" } else { Fail "没进记一笔页：$top" }
}
$ui = Get-Ui "02-quick-entry"
Screenshot "02-记一笔"

# 键盘输入 12.50
foreach ($k in @("key1", "key2", "keyDot", "key5", "key0")) {
    $n = Find-Node $ui $k $null
    if ($n) { Tap-Node $n | Out-Null; Start-Sleep -Milliseconds 250 } else { Fail "键盘上找不到 $k" }
}
Start-Sleep -Milliseconds 400
$ui = Get-Ui "03-amount-typed"
$amount = Find-Node $ui "tvAmount" $null
if ($amount) { Pass "金额显示 = $($amount.GetAttribute('text'))" } else { Fail "找不到 tvAmount" }
Screenshot "03-输入金额"

# ------------------------------------------------------------------ 分类选择
# v1.4.0 起：分类选择改为当前页底部滑出的面板（BottomSheet）。
# 面板是独立窗口，uiautomator dump 看不到它的内容 —— 断言用「不跳页 + 截图」，
# 点选分类按截图比例定位，结果回主窗口校验 tvCategory。
Write-Host "`n== 分类选择面板"
if (Tap-Id $ui "rowCategory") {
    Start-Sleep -Seconds 2
    Screenshot "04-分类选择面板"
    if ((Get-TopActivity) -match "EditTransaction") {
        Pass "分类面板已滑出（未跳转新页面）"
    } else {
        Fail "面板打开后页面变了：$(Get-TopActivity)"
    }
    # 按比例点「最近使用」第 3 格（标准测试数据下是「零食」）
    $sz = Get-ScreenSize
    Invoke-Adb @("shell", "input", "tap", [int]($sz[0] * 0.597), [int]($sz[1] * 0.472)) -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    $ui = Get-Ui "09-entry-with-cat"
    Screenshot "08-记一笔已选分类"
    $cat = Find-Node $ui "tvCategory" $null
    if ($cat) { Pass "分类行 = $($cat.GetAttribute('text'))" } else { Fail "分类行没渲染" }
}

# 添加分类
# 「添加分类」入口现在在面板底部（独立窗口），脚本点不到；
# CategoryEditor 的改名 / 删除覆盖仍由下面的「分类管理」段承担。

# 保存
if (Tap-Text $ui "确定") {
    if (Wait-Top "MainActivity" 8) { Pass "保存后回到主界面" } else { Fail "保存后还在 $(Get-TopActivity)" }
} else {
    Fail "记一笔页找不到「确定」"
}
$ui = Get-Ui "10-after-save"
Screenshot "09-保存后的明细"

# ------------------------------------------------------------------ 编辑页（顺带查删除按钮）
Write-Host "`n== 编辑已有记录"
$row = Find-Node $ui $null "-12.50"
if (-not $row) { $row = Find-Node $ui $null "手动记账" }
if ($row) {
    Tap-Node $row | Out-Null
    Start-Sleep -Seconds 3
    $ui = Get-Ui "11-edit"
    Screenshot "10-编辑页"
    $del = Find-Node $ui "btnDelete" $null
    if ($del) {
        $b = $del.GetAttribute("bounds")
        Pass "编辑页有删除按钮，bounds=$b"
    } else {
        Fail "编辑页没有删除按钮（下面 ui-11-edit.xml 里可以看到完整的控件树）"
    }
    Invoke-Adb @("shell", "input", "keyevent", "4") -AllowFail | Out-Null
    Start-Sleep -Seconds 2
} else {
    Fail "明细里找不到刚存的那笔"
}

# ------------------------------------------------------------------ 记一笔收入
# 报表的「收入来源」卡片只在有收入时才渲染，先补一笔收入，否则这条断言测不到东西。
Write-Host "`n== 记一笔收入"
$ui = Get-Ui "12-income-home"
if (Tap-Text $ui "手动记一笔") {
    Start-Sleep -Seconds 2
    $ui = Get-Ui "12b-income-entry"
    Screenshot "14-记一笔-收入"
    if (Tap-Text $ui "收入") { Start-Sleep -Milliseconds 900 }
    $ui = Get-Ui "12c-income-tab"
    foreach ($k in @("key8", "key0", "key0", "key0")) {
        $n = Find-Node $ui $k $null
        if ($n) { Tap-Node $n | Out-Null; Start-Sleep -Milliseconds 250 } else { Fail "键盘上找不到 $k" }
    }
    $ui = Get-Ui "12d-income-amount"
    $amt = Find-Node $ui "tvAmount" $null
    if ($amt) { Pass "收入金额 = $($amt.GetAttribute('text'))" } else { Fail "收入页找不到 tvAmount" }
    Screenshot "15-记一笔-收入已输入"
    if (Tap-Text $ui "确定") {
        if (Wait-Top "MainActivity" 8) { Pass "收入已保存" } else { Fail "收入保存后还在 $(Get-TopActivity)" }
    } else {
        Fail "记一笔页找不到「确定」"
    }
    Screenshot "16-收入已入明细"
} else {
    Fail "回不到主界面，没法记收入"
}

# ------------------------------------------------------------------ 报表
Write-Host "`n== 报表页"
$ui = Get-Ui "13-back-home"
if (-not (Find-Node $ui $null "报表")) { $ui = Get-Ui "13-back-home2" }
Tap-Text $ui "报表" | Out-Null
Start-Sleep -Seconds 3
$ui = Get-Ui "13-report"
Screenshot "11-报表"

# 报表是长页面，收入来源/趋势/大额支出都在首屏之下，逐屏滚过去收集文案
$seen = @{}
Collect-Seen $ui $seen
for ($i = 1; $i -le 3; $i++) {
    Scroll-Down
    Screenshot "11-$i-报表滚动$i"
    Collect-Seen (Get-Ui "13-report-scroll$i") $seen
}
foreach ($t in @("支出分布", "收入来源", "近 6 个月趋势", "花钱最多的对象")) {
    if ($seen.ContainsKey($t)) { Pass "报表里有「$t」" } else { Fail "报表里没有「$t」" }
}
Scroll-Up -Times 4
$ui = Get-Ui "13-report-top"

# v1.5.0 起：报表只排一级类，点一行直接进下钻页（二级类构成 + 交易明细）
if (Tap-Text $ui "饮食") {
    Start-Sleep -Seconds 3
    $top = Get-TopActivity
    if ($top -match "CategoryBreakdown") { Pass "点一级类进了下钻页" } else { Fail "没进下钻页：$top" }
    $ui = Get-Ui "14-breakdown"
    Screenshot "12-一级类下钻页"
    # 「交易明细」标题带笔数（「交易明细 · N 笔」），精确匹配文本会落空，按 id 找
    if (Find-Node $ui $null "二级分类") { Pass "下钻页有「二级分类」" } else { Fail "下钻页没有「二级分类」" }
    if (Find-Node $ui "tvDetailTitle" $null) { Pass "下钻页有「交易明细」" } else { Fail "下钻页没有「交易明细」" }
}

# 下钻页里点某个二级类 → 该二级类自己的流水页
if (Tap-Text $ui "零食") {
    Start-Sleep -Seconds 3
    $top = Get-TopActivity
    if ($top -match "CategoryDetail") { Pass "下钻到了二级类明细页" } else { Fail "没进分类明细页：$top" }
    Screenshot "13-分类明细"
}
$ui = Get-Ui "15-detail"

# ------------------------------------------------------------------ 账单周期选择 + 年度账单
# v1.4.0 起：报表页点月份标题滑出「选择时间」面板，任意月份 / 全年都在这里选，
# 原来的「年度账单」入口卡片已并入面板。
# 面板内容 dump 看不到，宫格按截图比例定位；选择结果回主窗口校验。
Write-Host "`n== 账单周期选择面板"
Go-Home
$ui = Get-Ui "20-home"
Tap-Text $ui "报表" | Out-Null
Start-Sleep -Seconds 3
$ui = Get-Ui "21-report"
$sz = Get-ScreenSize

# 面板选月份：点「上个月」宫格（月 tab 下的 4 列 x 3 行，第 2 行第 4 格）
$prevM = (Get-Date).Month - 1; if ($prevM -eq 0) { $prevM = 12 }
if (Tap-Id $ui "tvMonth") {
    Start-Sleep -Seconds 2
    Screenshot "17-选择时间面板"
    # 点 8 月宫格（比例来自 1080x2400 实测，其他分辨率按比例缩放）
    Invoke-Adb @("shell", "input", "tap", [int]($sz[0] * 0.862), [int]($sz[1] * 0.845)) -AllowFail | Out-Null
    Start-Sleep -Seconds 3
    $ui = Get-Ui "21b-month-picked"
    $tv = Find-Node $ui "tvMonth" $null
    if ($tv -and $tv.GetAttribute("text") -match "8月") {
        Pass "月份宫格直达：$($tv.GetAttribute('text'))"
    } else {
        Fail "月份没切过去，现在是 $(if ($tv) { $tv.GetAttribute('text') } else { 'null' })"
    }
}

# 面板选年份 → 年度账单
if (Tap-Id $ui "tvMonth") {
    Start-Sleep -Seconds 2
    # 「年」tab：面板中上部右侧
    Invoke-Adb @("shell", "input", "tap", [int]($sz[0] * 0.730), [int]($sz[1] * 0.662)) -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    Screenshot "17b-年口径"
    # 年份格「当年」：居中单行
    Invoke-Adb @("shell", "input", "tap", [int]($sz[0] * 0.5), [int]($sz[1] * 0.921)) -AllowFail | Out-Null
    Start-Sleep -Seconds 3
    $top = Get-TopActivity
    if ($top -match "YearReportActivity") { Pass "从面板打开了年度账单" } else { Fail "没进年度账单：$top" }
} else { Fail "报表页找不到月份标题（tvMonth）" }

$ui = Get-Ui "22-year-report"
Screenshot "18-年度账单"
foreach ($t in @("全年支出", "月均支出", "月度走势", "有账月份", "记账笔数")) {
    if (Find-Node $ui $null $t) { Pass "年度账单里「$t」" } else { Fail "年度账单里没有「$t」" }
}

# 年度账单也是长页面，12 月走势 / 分类分布 / 大额对象都在首屏之下，逐屏收集文案
$seenY = @{}
Collect-Seen $ui $seenY
for ($i = 1; $i -le 3; $i++) {
    Scroll-Down
    Screenshot "19-$i-年度账单滚动$i"
    Collect-Seen (Get-Ui "22b-year-scroll$i") $seenY
}
foreach ($t in @("支出分布", "花钱最多的对象")) {
    if ($seenY.ContainsKey($t)) { Pass "年度账单里有「$t」" } else { Fail "年度账单里没有「$t」" }
}
$ui2 = Get-Ui "22c-year-bottom"

# 点一级类 → 下钻页（二级类构成 + 交易明细），再点二级类继续下钻。
# 先滚回顶部再往下走一格，保证「支出分布」这一块一定在可见区里
# （长页面上 uiautomator 只报可见节点，位置飘了就会「找不到控件」）
Scroll-Up -Times 8
Scroll-Down
$ui2 = Get-Ui "22d-year-top"
if (Tap-Text $ui2 "饮食") {
    Start-Sleep -Seconds 3
    $ui2 = Get-Ui "23-year-breakdown"
    Screenshot "19-年度账单一级类下钻页"
    $top = Get-TopActivity
    if ($top -match "CategoryBreakdown") {
        Pass "年度账单点一级类进了下钻页"
    } else { Fail "年度账单没进下钻页：$top" }
    if (Find-Node $ui2 $null "二级分类") {
        Pass "年度账单下钻页有二级分类"
    } else { Fail "年度账单下钻页没有二级分类" }
    if (Find-Node $ui2 "tvDetailTitle" $null) {
        Pass "年度账单下钻页有交易明细"
    } else { Fail "年度账单下钻页没有交易明细" }
    if (Tap-Text $ui2 "零食") {
        Start-Sleep -Seconds 3
        $top = Get-TopActivity
        if ($top -match "CategoryDetail") { Pass "年度账单下钻到分类明细" } else { Fail "下钻失败：$top" }
        $ui3 = Get-Ui "24-year-detail"
        $sub = Find-Node $ui3 "tvMonth" $null
        if ($sub -and $sub.GetAttribute("text") -match "年") {
            Pass "明细页按整年过滤：$($sub.GetAttribute('text'))"
        } else {
            Fail "明细页不是整年口径"
        }
        Screenshot "20-年度账单分类明细"
    }
}
Go-Home

# ------------------------------------------------------------------ 分类管理
Write-Host "`n== 分类管理"
$ui = Get-Ui "30-home"
Tap-Text $ui "我的" | Out-Null
Start-Sleep -Seconds 2
Scroll-Up -Times 3
$ui = Get-Ui "31-mine"
# 「分类管理」在首屏的「常用工具」四宫格里，不用再往下滚
if (Tap-Text $ui "分类管理") {
    Start-Sleep -Seconds 2
    $top = Get-TopActivity
    if ($top -match "CategoryManageActivity") { Pass "打开了分类管理" } else { Fail "没进分类管理：$top" }
} else {
    Fail "我的页找不到「分类管理」"
}
$ui = Get-Ui "32-manage"
Screenshot "21-分类管理"
if (Find-Node $ui $null "一级类") { Pass "一级类行已渲染" } else { Fail "看不到一级类行" }

# 改第一个二级类的名字（第 1 个「编辑」按钮是它上面那个一级类的，所以要取第 2 个）
$edits = Find-Nodes $ui "btnEdit" $null
if ($edits.Count -ge 2) {
    Tap-Node $edits[1] | Out-Null
    Start-Sleep -Seconds 2
    $ui = Get-Ui "33-edit-dialog"
    Screenshot "22-编辑分类弹窗"
    $name = Find-Node $ui "etName" $null
    if ($name) {
        Tap-Node $name | Out-Null
        Start-Sleep -Milliseconds 500
        Type-Into "Brunch"
        Hide-Keyboard
        $ui = Get-Ui "34-edit-filled"
        if (Tap-Text $ui "保存") { Start-Sleep -Seconds 2 }
        $ui = Get-Ui "35-after-rename"
        Screenshot "23-改名后"
        if (Find-Node $ui $null "Brunch") { Pass "内置分类改名生效" } else { Fail "改名没生效" }

        # 删掉它
        $dels = Find-Nodes $ui "btnDelete" $null
        if ($dels.Count -ge 2) {
            Tap-Node $dels[1] | Out-Null
            Start-Sleep -Seconds 2
            $ui = Get-Ui "36-confirm-delete"
            Screenshot "24-删除确认"
            if (Tap-Text $ui "删除分类") {
                Start-Sleep -Seconds 2
                $ui = Get-Ui "37-after-delete"
                Screenshot "25-删除后"
                if (-not (Find-Node $ui $null "Brunch")) { Pass "分类已删除" } else { Fail "分类没删掉" }
            } else { Fail "删除确认框上找不到按钮" }
        } else { Fail "找不到删除按钮" }

        # 恢复内置
        if (Tap-Text $ui "恢复内置") {
            Start-Sleep -Seconds 2
            $ui = Get-Ui "38-restore-dialog"
            Screenshot "26-恢复内置确认"
            if (Tap-Text $ui "恢复内置") {
                Start-Sleep -Seconds 2
                $ui = Get-Ui "39-after-restore"
                Screenshot "27-恢复内置后"
                if (Find-Node $ui $null "餐饮") { Pass "内置分类已恢复为「餐饮」" } else { Fail "恢复后看不到出厂分类名" }
            } else { Fail "恢复确认框上找不到按钮" }
        } else { Fail "找不到「恢复内置」" }
    } else { Fail "编辑弹窗里没有输入框" }
} else { Fail "分类管理页找不到编辑按钮" }
Go-Home

# ------------------------------------------------------------------ 后台常驻
Write-Host "`n== 后台常驻运行"
$ui = Get-Ui "40-home"
Tap-Text $ui "我的" | Out-Null
Start-Sleep -Seconds 2
# 「我的」是同一个 Fragment 实例，上一步滚下去的位置会留着，先回到顶部才看得到开关
Scroll-Up -Times 3
$ui = Get-Ui "41-mine"
if (Test-KeepAliveService) { Fail "开关还没打开，服务就已经在跑了" } else { Pass "默认不常驻（用户没开就不跑）" }

# 保活 / 辅助记账 / 省电放行已经收进「稳定运行」折叠区，先展开才点得到开关
if (-not (Tap-Text $ui "稳定运行")) {
    Fail "我的页找不到「稳定运行」折叠区"
} else {
    Start-Sleep -Seconds 1
    $ui = Get-Ui "41a-mine-fold"
    # 展开后屏上的开关顺序：读取支付通知 / 后台常驻运行 / 辅助记账
    $switches = Find-Nodes $ui "switchView" $null
    if ($switches.Count -ge 3) {
        Tap-Node $switches[1] | Out-Null
        Start-Sleep -Seconds 3
        Screenshot "28-后台常驻已开启"
        if (Test-KeepAliveService) { Pass "前台服务已启动（isForeground=true）" } else { Fail "服务没起来" }
        # 直接抓 adb 输出的中文可能被 PowerShell 按本地编码读坏，用包名 + 渠道 id 这种 ASCII 标记来判断
        $notif = Invoke-Adb @("shell", "dumpsys", "notification", "--noredact") -AllowFail
        $mine = ($notif -split "`n" | Where-Object { $_ -match "NotificationRecord" -and $_ -match $AppPkg })
        if ($mine) {
            if ($mine -match "channel=keep_alive") { Pass "通知栏有常驻入口（channel=keep_alive）" }
            else { Fail "有通知但不在常驻渠道：$mine" }
        } else { Fail "通知栏没有常驻通知" }
        Invoke-Adb @("shell", "cmd", "statusbar", "expand-notifications") -AllowFail | Out-Null
        Start-Sleep -Seconds 2
        Screenshot "29-通知栏常驻"
        Invoke-Adb @("shell", "cmd", "statusbar", "collapse") -AllowFail | Out-Null

        # 从最近任务划掉 App，服务应当还活着
        Invoke-Adb @("shell", "am", "kill", $AppPkg) -AllowFail | Out-Null
        Start-Sleep -Seconds 4
        if (Test-KeepAliveService) { Pass "划掉 App 后服务依然在跑" } else { Fail "划掉 App 后服务没了" }

        # 关掉开关
        $ui = Get-Ui "42-mine-off"
        $switches = Find-Nodes $ui "switchView" $null
        if ($switches.Count -ge 3) {
            Tap-Node $switches[1] | Out-Null
            Start-Sleep -Seconds 3
            if (-not (Test-KeepAliveService)) { Pass "关掉开关后服务已停止" } else { Fail "关掉开关后服务还在" }
        } else { Fail "二次取开关失败，实测 $($switches.Count) 个" }
    } else {
        Fail "「稳定运行」展开后看不到 3 个开关，实测 $($switches.Count) 个"
    }
}

# ------------------------------------------------------------------ 收尾
if (-not $KeepEmulator -and $script:StartedEmulator) {
    Info "关闭模拟器"
    Invoke-Adb @("emu", "kill") -AllowFail | Out-Null
}

Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "界面验收全部通过。" -ForegroundColor Green
    Write-Host "产物在 $OutDir"
    exit 0
} else {
    Write-Host "$script:Failures 项未通过，产物在 $OutDir" -ForegroundColor Red
    exit 1
}
