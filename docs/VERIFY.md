# 设备验证

本项目对"已验证"的定义很硬：**在真实设备/模拟器上，用可复现的证据证明行为**。
"代码看起来对"、"编译通过"、"控件存在"都不算。

配套工具：[../tools/verify.ps1](../tools/verify.ps1)（PowerShell 函数库）。

## 1. 准备

```powershell
# 设备（或启动 AVD xhs_test：API 34 / 1080×2400 / 420dpi）
adb devices

# 载入函数库（会解析 adb 路径：优先 $env:ADB → ANDROID_SDK_ROOT/ANDROID_HOME → PATH）
. .\tools\verify.ps1
```

`verify.ps1` 提供的函数：`EnsureDevice`（等待并在需要时解锁，解锁密码 `1234`）、`LaunchApp`、
`WaitFocused`、`DumpUi`（返回 UI dump 文本）、`TapText`（按文本点击）、`Shot`（截图到
`docs/images/screenshots/`）、`CrashCount`（崩溃计数）、`SwitchStates`（开关状态读取）。

默认包名 `com.thirdparty.xhs.debug`（`$script:PKG`）；截图目录由 `$PSScriptRoot` 推导，不依赖机器路径。

## 2. 安装必须校验（不能只看"安装成功"）

```powershell
$apk = 'app\build\outputs\apk\debug\app-debug.apk'
(Get-FileHash $apk -Algorithm MD5).Hash          # 本地产物 md5

adb install -r $apk
adb shell am force-stop com.thirdparty.xhs.debug  # 必须：否则测的是旧进程
adb shell pm path com.thirdparty.xhs.debug        # 确认设备上安装位置
adb shell md5sum /data/app/*/com.thirdparty.xhs.debug-*/base.apk   # 与本地 md5 比对
adb shell am start -n com.thirdparty.xhs.debug/com.thirdparty.xhs.MainActivity
```

判据：本地 md5 == 设备上的 base.apk md5，且 `am force-stop` 已执行。任一条不满足，后面所有观察都无效。

## 3. 最小验证回路

```powershell
. .\tools\verify.ps1
EnsureDevice
LaunchApp
$d = DumpUi                      # 先用它确认应用真的起来了
if (-not $d) { throw 'dump 为空，可能被锁屏/弹窗遮挡' }
TapText '设置'
$d = DumpUi
$d -match '音量键翻页'            # 断言目标文本
Shot 'settings_after_tap'
```

要点：

- `DumpUi` 可能**崩溃并静默返回空文本**（耗时 2–3.5 秒，本身也会干扰时序）。空结果先怀疑 dump，
  不要直接判定"界面没渲染"。
- 断言失败时，**先怀疑断言**（选择器、文本、时序），再怀疑实现。
- 每完成一轮验证：`CrashCount` 确认无新增崩溃。

## 4. UI 改造的证据要求

| 改动类型 | 需要的证据 |
| --- | --- |
| 新增控件/入口 | 点击前后的 `DumpUi` 文本（证明可达 + 状态真的变了） |
| 视觉/主题/布局 | `Shot` 截图（改造前 + 改造后各一张），并说明"看哪一处不同" |
| 状态/时序（播放、缓冲、刷新） | 连续两次 dump 或两张截图，证明状态**随时间变化正确** |
| 数据相关 | dump 中的真实字段值（标题、计数），不要只截图看"有内容" |

**"改了主题"不等于"UI 变了"**：如果改的是主题变量而渲染结果没变化，必须明说，不能算完成。

## 5. 测试数据注入

需要构造本地数据时，直接改设备上的数据库（`noteId >= 900001` 作为测试段，便于清理）：

```powershell
adb shell "run-as com.thirdparty.xhs.debug cp /data/data/com.thirdparty.xhs.debug/databases/xhs_local.db /sdcard/xhs_local.db"
# 在主机上修改后推回；或用 sqlite3 直接在设备上写入 saved_notes / history
```

验证结束要清理测试数据（否则下一次验证会被脏数据误导）。

## 6. 已知干扰项（先排除再下结论）

| 干扰 | 表现 | 处理 |
| --- | --- | --- |
| 设备锁屏 | `am start` 报 `Activity class does not exist` 之类误导性错误 | 先解锁（`EnsureDevice`） |
| 剪贴板残留分享口令 | 启动后弹「检测到分享内容」挡住界面 | 先点「取消」再继续 |
| DNS 污染（`app.xiaohuangbook.net` → `199.59.148.89`） | 请求失败/超时，界面出现错误态 | 换网络或用可信 DNS；确认解析结果后再怀疑代码 |
| `uiautomator dump` 自身 | 空文本、超时、偶发崩溃 | 重试并校验非空；把它算进时序 |
| 旧进程未杀 | 观察到的行为与新代码不一致 | `am force-stop` 后再启动 |

## 7. 临时探针要收尾

为定位问题临时加的日志、临时代码、临时环境改动，验证完成后：

1. 删除探针代码；
2. `grep -ri "probe" app/src`（或对应目录）复查无残留；
3. 还原临时环境改动（例如临时改的网络配置、临时的 SharedPreferences 值）；
4. 重新编译一次，确认清理后仍可构建。

## 8. 汇报格式

```
需求：<原话或摘要>
做了什么：<文件:行 + 一句话>
证据：<dump 片段 / 截图路径 / 命令输出关键行>
状态：已完成 | 未完成（原因） | 未验证（缺什么）
```

没做完的必须写"未完成"，不要用"基本完成"这类措辞。
