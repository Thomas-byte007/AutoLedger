<#
.SYNOPSIS
    v1.4.0 三项改动的针对性可视化验收（比 ui-verify.ps1 轻：只跑新交互）。
    产物存 dist/ui-verify/，文件名以 v140- 前缀。
#>
[CmdletBinding()]
param(
    [string]$Avd = "ledger_test",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $Root "dist\ui-verify"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$AppPkg = "com.local.autoledger.debug"
$AppActivity = "$AppPkg/com.local.autoledger.ui.MainActivity"
$AdbExe = "D:\develop\android-sdk\platform-tools\adb.exe"
$script:Failures = 0

function Invoke-Adb {
    param([string[]]$Cmd, [switch]$AllowFail)
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $AdbExe @Cmd 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($code -ne 0 -and -not $AllowFail) { throw "adb $($Cmd -join ' ') 失败: $out" }
    return ($out | Out-String).Trim()
}

function Info($t) { Write-Host "   $t" }
function Pass($t) { Write-Host "   [OK]   $t" -ForegroundColor Green }
function Fail($t) { Write-Host "   [FAIL] $t" -ForegroundColor Red; $script:Failures++ }

function Get-Ui([string]$Tag) {
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
    Fail "UI 树抓取失败：$Tag"
    return $null
}

function Find-Node($doc, [string]$ResourceIdSuffix, [string]$Text) {
    if (-not $doc) { return $null }
    foreach ($n in $doc.SelectNodes("//node")) {
        if ($ResourceIdSuffix -and $n.GetAttribute("resource-id").EndsWith($ResourceIdSuffix)) { return $n }
        if ($Text -and -not $ResourceIdSuffix -and
            ($n.GetAttribute("text") -eq $Text -or $n.GetAttribute("content-desc") -eq $Text)) { return $n }
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

function Screenshot([string]$Name) {
    $remote = "/sdcard/_ui_shot.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) -AllowFail | Out-Null
    $local = Join-Path $OutDir "$Name.png"
    Invoke-Adb @("pull", $remote, $local) -AllowFail | Out-Null
    if (Test-Path $local) { Pass "截图 $Name.png" } else { Fail "截图 $Name 失败" }
}

function Get-TopActivity {
    $d = Invoke-Adb @("shell", "dumpsys", "activity", "activities") -AllowFail
    $hit = ($d -split "`n" | Where-Object { $_ -match "topResumedActivity" } | Select-Object -First 1)
    if ($hit -and $hit -match '([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)') { return "$($Matches[1])/$($Matches[2])" }
    return ""
}

function Get-ScreenSize {
    $s = Invoke-Adb @("shell", "wm", "size") -AllowFail
    if ($s -match "(\d+)x(\d+)") { return @([int]$Matches[1], [int]$Matches[2]) }
    return @(1080, 2400)
}

# ==================================================================

Write-Host "随手记 v1.4.0 交互验收"

if (-not (Invoke-Adb @("devices") -AllowFail) -match "emulator-\d+\s+device") {
    Write-Host "模拟器不在运行" -ForegroundColor Red; exit 1
}

if (-not $SkipInstall) {
    $apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
    $r = Invoke-Adb @("install", "-r", "-g", $apk) -AllowFail
    if ($r -match "Success") { Pass "安装 debug APK" } else { Fail "安装失败 -> $r" }
}

foreach ($perm in @("android.permission.POST_NOTIFICATIONS")) {
    Invoke-Adb @("shell", "pm", "grant", $AppPkg, $perm) -AllowFail | Out-Null
}
Invoke-Adb @("shell", "svc", "power", "stayon", "usb") -AllowFail | Out-Null
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") -AllowFail | Out-Null

# 冷启动
Invoke-Adb @("shell", "am", "force-stop", $AppPkg) -AllowFail | Out-Null
Start-Sleep -Seconds 2
Invoke-Adb @("shell", "am", "start", "-W", "-n", $AppActivity) -AllowFail | Out-Null
Start-Sleep -Seconds 5

# ---------------- 1. 明细列表（类别为标题）
$ui = Get-Ui "v140-detail"
Screenshot "v140-1-明细列表"
$title = Find-Node $ui "tvTitle" $null
if ($title) { Info "首行标题 = $($title.GetAttribute('text'))" }

# ---------------- 2. 记一笔 → 分类面板
$btn = Find-Node $ui $null "手动记一笔"
if (-not $btn) { $btn = Find-Node $ui $null "记一笔" }
if (Tap-Node $btn) {
    Start-Sleep -Seconds 2
    $ui = Get-Ui "v140-entry"
    if ((Get-TopActivity) -match "EditTransaction") { Pass "打开记一笔页" } else { Fail "没进记一笔页：$(Get-TopActivity)" }
    if (Tap-Node (Find-Node $ui "rowCategory" $null)) {
        Start-Sleep -Seconds 2
        # 面板是独立窗口，dump 看不到内容 —— 用截图留证 + 不跳页断言
        Screenshot "v140-2-分类选择面板"
        if ((Get-TopActivity) -match "EditTransaction") {
            Pass "分类面板已滑出（未跳转新页面）"
        } else { Fail "面板打开后页面变了：$(Get-TopActivity)" }
        # 按比例点「最近使用」第 3 格（标准数据下是「零食」）
        $sz = Get-ScreenSize
        Invoke-Adb @("shell", "input", "tap", [int]($sz[0] * 0.597), [int]($sz[1] * 0.472)) -AllowFail | Out-Null
        Start-Sleep -Seconds 2
        $ui = Get-Ui "v140-picked"
        $cat = Find-Node $ui "tvCategory" $null
        if ($cat) { Pass "分类已选 = $($cat.GetAttribute('text'))" } else { Fail "分类行没渲染" }
        Screenshot "v140-3-已选分类"
    } else { Fail "找不到分类行" }
    # 关掉编辑页
    Invoke-Adb @("shell", "input", "keyevent", "4") -AllowFail | Out-Null
    Start-Sleep -Seconds 2
} else { Fail "主界面找不到「手动记一笔」" }

# ---------------- 3. 报表 → 选择时间面板 → 月份 / 年度
$ui = Get-Ui "v140-home2"
$tab = Find-Node $ui $null "报表"
if (Tap-Node $tab) {
    Start-Sleep -Seconds 3
    $ui = Get-Ui "v140-report"
    Screenshot "v140-4-报表页"
    $sz = Get-ScreenSize
    if (Tap-Node (Find-Node $ui "tvMonth" $null)) {
        Start-Sleep -Seconds 2
        Screenshot "v140-5-选择时间面板"
        # 点 8 月宫格（比例来自 1080x2400 实测）
        Invoke-Adb @("shell", "input", "tap", [int]($sz[0] * 0.862), [int]($sz[1] * 0.845)) -AllowFail | Out-Null
        Start-Sleep -Seconds 3
        $ui = Get-Ui "v140-month-picked"
        $tv = Find-Node $ui "tvMonth" $null
        if ($tv -and $tv.GetAttribute("text") -match "8月") {
            Pass "月份宫格直达：$($tv.GetAttribute('text'))"
        } else { Fail "月份没切过去" }
        # 再开面板 → 年 → 当年年份 → 年度账单
        if (Tap-Node (Find-Node $ui "tvMonth" $null)) {
            Start-Sleep -Seconds 2
            Invoke-Adb @("shell", "input", "tap", [int]($sz[0] * 0.730), [int]($sz[1] * 0.662)) -AllowFail | Out-Null
            Start-Sleep -Seconds 2
            Screenshot "v140-6-年口径"
            Invoke-Adb @("shell", "input", "tap", [int]($sz[0] * 0.5), [int]($sz[1] * 0.921)) -AllowFail | Out-Null
            Start-Sleep -Seconds 3
            if ((Get-TopActivity) -match "YearReportActivity") {
                Pass "从面板打开年度账单"
                $ui = Get-Ui "v140-year"
                Screenshot "v140-7-年度账单"
            } else { Fail "没进年度账单：$(Get-TopActivity)" }
        }
    } else { Fail "报表页点不动月份标题" }
} else { Fail "找不到「报表」tab" }

Invoke-Adb @("shell", "input", "keyevent", "4") -AllowFail | Out-Null
Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "v1.4.0 交互验收全部通过。产物在 $OutDir" -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:Failures) 项未通过，产物在 $OutDir" -ForegroundColor Red
    exit 1
}
