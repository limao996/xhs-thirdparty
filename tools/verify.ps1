# 快速验证工具（本脚本**入库**；只有探针输出 tools/out/ 与截图 docs/images/screenshots/ 不入库）
#
# 为什么需要它：实测 adb 单步耗时
#   adb shell echo            37 ms
#   dumpsys window            49 ms      <-- 便宜的就绪探测
#   adb shell input tap       55 ms
#   uiautomator dump        2036~3500 ms <-- 贵 60 倍，绝不能放进轮询循环
#
# 之前的验证脚本每 600ms 轮询一次 dump，等于每轮询 3 秒，
# 一个"等 25 秒"实际跑了 8 次 dump；再加上每轮 pm clear（冷启动 20s）、
# 每轮 emu kill（重建 30s+），验证自然慢得离谱。
#
# 用法：
#   . .\tools\verify.ps1          # 导入函数
#   EnsureDevice                  # 解锁 + 关闭息屏（锁屏时 adb 会报
#                                 # "Activity class does not exist"，且有 3 秒误导成本）
#   LaunchApp                     # 冷启动并等到真正就绪
#   $d = DumpUi                   # 取一次界面
#   TapText '设置'                # 点文字
#   Shot 'name'                   # 截图

# adb 位置不写死：优先 $env:ADB，其次 Android SDK 环境变量，最后 PATH 上的 adb。
# 需要指定时在导入本脚本前 `$env:ADB = 'D:\path\to\adb.exe'`。
function Resolve-Adb {
    if ($env:ADB -and (Test-Path -LiteralPath $env:ADB)) { return $env:ADB }
    foreach ($root in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($root) {
            $p = Join-Path $root 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $p) { return $p }
        }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb not found: set $env:ADB, $env:ANDROID_SDK_ROOT, or put adb on PATH.'
}

$script:ADB = Resolve-Adb
$script:PKG = 'com.thirdparty.xhs.debug'
# 截图落在仓库内的 docs/images/screenshots（该目录不入库，见 .gitignore）。
$script:SHOTS = Join-Path (Split-Path -Parent $PSScriptRoot) 'docs\images\screenshots'
New-Item -ItemType Directory -Force -Path $script:SHOTS | Out-Null

function EnsureDevice {
    $st = (& $script:ADB shell 'dumpsys user | grep -m1 State:') -join ''
    if ($st -notmatch 'UNLOCKED') {
        & $script:ADB shell input keyevent 224 | Out-Null
        Start-Sleep -Milliseconds 700
        & $script:ADB shell input swipe 540 1800 540 900 150 | Out-Null
        Start-Sleep -Milliseconds 700
        & $script:ADB shell input text 1234 | Out-Null
        Start-Sleep -Milliseconds 500
        & $script:ADB shell input keyevent 66 | Out-Null
        Start-Sleep -Milliseconds 1500
    }
    # keep the screen from locking mid-test (a locked device makes every dump fail)
    & $script:ADB shell 'settings put system screen_off_timeout 1800000' | Out-Null
}

# 一次 uiautomator dump。贵（2-3.5s），所以每个界面状态只调用一次，
# 拿到结果后在其中断言所有条件 —— 不要在循环里调它。
#
# 先删掉上一次的 dump 文件：`uiautomator dump` 偶发失败时会打印
# "Failed to write while dumping service user: Broken pipe"（屏幕正在转场、
# 或者处于全屏/画中画），此时紧跟着的 `cat` 会把**上一次的旧文件**读出来 ——
# 界面明明已经变了，dump 却一直返回同一份内容，让人以为"点了没反应"（实测踩过）。
function DumpUi {
    & $script:ADB shell 'rm -f /sdcard/d.xml' | Out-Null
    & $script:ADB shell 'uiautomator dump --compressed /sdcard/d.xml >/dev/null 2>&1' | Out-Null
    return (& $script:ADB shell 'cat /sdcard/d.xml 2>/dev/null') -join ''
}

# 便宜的就绪探测（49ms）。用 dumpsys window 看焦点是否已经落在本应用。
function WaitFocused([int]$timeoutMs = 40000) {
    $t = 0
    while ($t -lt $timeoutMs) {
        $f = (& $script:ADB shell 'dumpsys window windows | grep -m1 mCurrentFocus') -join ''
        if ($f -match 'thirdparty') { return $true }
        Start-Sleep -Milliseconds 500
        $t += 500
    }
    return $false
}

function LaunchApp([string]$deepLink = '') {
    & $script:ADB shell "am force-stop $script:PKG" | Out-Null
    & $script:ADB logcat -c | Out-Null
    if ($deepLink -ne '') {
        & $script:ADB shell "am start -a android.intent.action.VIEW -d `"$deepLink`" -n $script:PKG/com.thirdparty.xhs.MainActivity" | Out-Null
    } else {
        & $script:ADB shell "am start -n $script:PKG/com.thirdparty.xhs.MainActivity" | Out-Null
    }
    return WaitFocused
}

function TapText([string]$txt, [string]$d = '') {
    if ($d -eq '') { $d = DumpUi }
    $pat = 'text="' + [regex]::Escape($txt) + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($d, $pat)
    if (-not $m.Success) { return $false }
    $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $script:ADB shell "input tap $cx $cy" | Out-Null
    return $true
}

function TapXY([int]$x, [int]$y) { & $script:ADB shell "input tap $x $y" | Out-Null }

# 界面里的全部可见文本，便于一眼看清当前在哪一屏
function Texts([string]$d) {
    return (([regex]::Matches($d, 'text="([^"]{1,20})"') | ForEach-Object { $_.Groups[1].Value }) | Select-Object -First 20) -join ' | '
}

function Shot([string]$name) {
    & $script:ADB exec-out screencap -p > (Join-Path $script:SHOTS "$name.png")
}

# 只数**本应用**的崩溃。
#
# 别用 `logcat -d | Select-String 'FATAL EXCEPTION'` —— uiautomator dump 自己会崩
# （AccessibilityNodeInfoDumper NPE，实测过），那种 FATAL 会被算成应用的崩溃，
# 于是"验证通过"的界面配着"crash: 1"，白查半天。按 pid 过滤才准。
function CrashCount {
    $p = ((& $script:ADB shell "pidof $script:PKG") -join '').Trim()
    if ($p -eq '') { return 0 }
    return (& $script:ADB logcat -d --pid=$p 2>$null |
        Select-String 'FATAL EXCEPTION' | Measure-Object).Count
}

# 读取 Compose 开关的状态。
#
# 不要用 `text="某行标题".*?checked="(true|false)"` —— 那会匹配到标题 TextView
# 自身的 checked 属性（恒为 false），从而误报「开关没生效」。
# Compose 的开关在 uiautomator dump 里不是 android.widget.Switch，
# 而是带 checkable="true" 的节点；用 y 坐标和标题行对应。
function SwitchStates([string]$d) {
    $rows = @{}
    foreach ($t in [regex]::Matches($d, 'text="([^"]{2,26})"[^>]*?bounds="\[\d+,(\d+)\]')) {
        $rows[[int]$t.Groups[2].Value] = $t.Groups[1].Value
    }
    $out = @()
    foreach ($s in [regex]::Matches($d, '<node[^>]*checkable="true"[^>]*>')) {
        $n    = $s.Value
        $chk  = [regex]::Match($n, 'checked="(true|false)"').Groups[1].Value
        $cy   = [int][regex]::Match($n, 'bounds="\[\d+,(\d+)\]').Groups[1].Value
        $near = ($rows.Keys | Where-Object { [Math]::Abs($_ - $cy) -lt 90 } | ForEach-Object { $rows[$_] }) -join ' / '
        $out += "$near = $chk"
    }
    return $out -join ' | '
}
