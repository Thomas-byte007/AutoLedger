<#
.SYNOPSIS
    给「我的」页单独截图：收起态、展开「稳定运行」、滚到底部。

.DESCRIPTION
    ui-verify.ps1 只把「我的」当跳板，不单独存这一页的截图。
    改这一页排版时用它快速看效果，产物在 dist/ui-verify/8x-*.png。

    两个必须记住的前提（都踩过）：
      1. 模拟器会自己熄屏，熄屏后 input tap / swipe 静默失效，而 uiautomator dump
         照样返回上一屏内容 —— 表现就是「点了没反应、dump 帧帧一样」。
         所以这里先关掉息屏、保持常亮，并在每次交互前补一次 KEYCODE_WAKEUP。
      2. adb 把 pull / install 的进度写到 stderr，PowerShell 5.1 会当错误往外抛，
         $ErrorActionPreference='Stop' 时脚本当场中断，所以函数内要临时改成 Continue。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\shot-mine.ps1
    powershell -ExecutionPolicy Bypass -File tools\shot-mine.ps1 -SkipInstall -KeepEmulator
#>
[CmdletBinding()]
param(
    [string]$Avd = "ledger_test",
    [switch]$SkipInstall,
    [switch]$KeepEmulator
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $Root "dist\ui-verify"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$Sdk    = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\develop\android-sdk" }
$AdbExe = Join-Path $Sdk "platform-tools\adb.exe"
$EmuExe = Join-Path $Sdk "emulator\emulator.exe"
if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = "D:\develop\android-avd" }

$Pkg      = "com.local.autoledger.debug"
$Activity = "$Pkg/com.local.autoledger.ui.MainActivity"
$Apk      = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
$script:Started = $false

function Invoke-Adb {
    param([string[]]$Cmd, [switch]$AllowFail)
    # adb 把正常进度（pull / install）也写到 stderr，PS 5.1 会把 stderr 当 ErrorRecord
    # 往外抛，$ErrorActionPreference='Stop' 时脚本直接中断。所以要先把错误动作关掉再调。
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $AdbExe @Cmd 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    if ($code -ne 0 -and -not $AllowFail) { throw "adb $($Cmd -join ' ') 失败: $out" }
    return ($out | Out-String).Trim()
}

function Wait-Boot([int]$TimeoutSec = 420) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $d = Invoke-Adb @("devices") -AllowFail
        if ($d -match "emulator-\d+\s+device") {
            $v = Invoke-Adb @("shell", "getprop", "sys.boot_completed") -AllowFail
            if ($v.Trim() -eq "1") { return $true }
        }
        Start-Sleep -Seconds 4
    }
    return $false
}

# 熄屏之后 input 会被整个丢掉，所以每次动手前都补一次唤醒
function Wake {
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") -AllowFail | Out-Null
    Invoke-Adb @("shell", "wm", "dismiss-keyguard") -AllowFail | Out-Null
    Start-Sleep -Milliseconds 400
}

# uiautomator 失败时会把上一屏的 xml 原样留在设备上，必须先删再 dump 并校验输出
function Get-Ui([string]$Tag) {
    $local = Join-Path $OutDir "ui-$Tag.xml"
    for ($i = 0; $i -lt 5; $i++) {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Invoke-Adb @("shell", "rm", "-f", "/sdcard/ui.xml") -AllowFail | Out-Null
        $r = Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/ui.xml") -AllowFail
        Invoke-Adb @("pull", "/sdcard/ui.xml", $local) -AllowFail | Out-Null
        if ($r -match "dumped" -and (Test-Path $local) -and ((Get-Item $local).Length -gt 200)) {
            return [xml](Get-Content -Raw -Encoding UTF8 $local)
        }
        Start-Sleep -Milliseconds 900
    }
    throw "UI 树抓取失败：$Tag"
}

# 按 resource-id 后缀找第 Index 个节点：比按中文文案找稳，不受控制台编码影响
function Find-Node($doc, [string]$Suffix, [int]$Index = 1) {
    $seen = 0
    foreach ($n in $doc.SelectNodes("//node")) {
        $rid = $n.GetAttribute("resource-id")
        if ($rid -and $rid.EndsWith($Suffix)) {
            $seen++
            if ($seen -eq $Index) { return $n }
        }
    }
    return $null
}

function Count-Node($doc, [string]$Suffix) {
    $c = 0
    foreach ($n in $doc.SelectNodes("//node")) {
        $rid = $n.GetAttribute("resource-id")
        if ($rid -and $rid.EndsWith($Suffix)) { $c++ }
    }
    return $c
}

function Tap-Node($node) {
    if (-not $node) { return $false }
    $b = $node.GetAttribute("bounds")
    if ($b -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $false }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Wake
    Invoke-Adb @("shell", "input", "tap", "$x", "$y") -AllowFail | Out-Null
    return $true
}

function Swipe-Up {
    Wake
    Invoke-Adb @("shell", "input", "swipe", "540", "1600", "540", "700", "500") -AllowFail | Out-Null
    Start-Sleep -Seconds 1
}

function Shot([string]$Name) {
    Invoke-Adb @("shell", "screencap", "-p", "/sdcard/_shot.png") -AllowFail | Out-Null
    $local = Join-Path $OutDir "$Name.png"
    Invoke-Adb @("pull", "/sdcard/_shot.png", $local) -AllowFail | Out-Null
    if (Test-Path $local) { Write-Host "   截图 $Name.png" }
    else { Write-Host "   截图 $Name 失败" -ForegroundColor Red }
}

Write-Host "== 起模拟器"
$d = Invoke-Adb @("devices") -AllowFail
if (-not ($d -match "emulator-\d+\s+device")) {
    Start-Process -FilePath $EmuExe -ArgumentList @(
        "-avd", $Avd, "-no-snapshot", "-no-boot-anim", "-no-audio",
        "-gpu", "swiftshader_indirect", "-memory", "2048") -WindowStyle Minimized | Out-Null
    Invoke-Adb @("wait-for-device") -AllowFail | Out-Null
    $script:Started = $true
}
if (-not (Wait-Boot)) { throw "模拟器没起来" }

# 别让屏幕中途自己黑掉，否则后面的点击全落空
Invoke-Adb @("shell", "settings", "put", "system", "screen_off_timeout", "600000") -AllowFail | Out-Null
Invoke-Adb @("shell", "svc", "power", "stayon", "usb") -AllowFail | Out-Null

if (-not $SkipInstall) {
    Write-Host "== 装包"
    Invoke-Adb @("install", "-r", $Apk) | Out-Null
}
foreach ($p in @(
        "android.permission.POST_NOTIFICATIONS")) {
    Invoke-Adb @("shell", "pm", "grant", $Pkg, $p) -AllowFail | Out-Null
}

Write-Host "== 进「我的」"
Invoke-Adb @("shell", "am", "force-stop", $Pkg) -AllowFail | Out-Null
Start-Sleep -Seconds 2
Invoke-Adb @("shell", "am", "start", "-W", "-n", $Activity) -AllowFail | Out-Null
Start-Sleep -Seconds 6
Wake

$ui = Get-Ui "80-main"
if (-not (Tap-Node (Find-Node $ui "nav_mine" 1))) { throw "底部导航里没找到「我的」" }
Start-Sleep -Seconds 3
Wake
$ui = Get-Ui "81-mine"
Shot "81-我的-收起"

Write-Host "== 展开「稳定运行」（第 1 个 tvFoldTitle）"
if (-not (Tap-Node (Find-Node $ui "tvFoldTitle" 1))) { throw "找不到「稳定运行」表头" }
Start-Sleep -Seconds 2
Wake
$ui = Get-Ui "82-fold-reliable"
Write-Host ("   可见开关 " + (Count-Node $ui "switchView") + " 个（收起时是 1）")
Shot "82-我的-稳定运行"

Write-Host "== 滚到底看「更多」「清空全部数据」"
Swipe-Up
Swipe-Up
Wake
Get-Ui "83-bottom" | Out-Null
Shot "83-我的-底部"

if (-not $KeepEmulator -and $script:Started) {
    Write-Host "== 关模拟器"
    Invoke-Adb @("emu", "kill") -AllowFail | Out-Null
}
Write-Host "产物在 $OutDir"
