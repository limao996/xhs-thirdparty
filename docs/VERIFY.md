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

`verify.ps1` 提供的函数：`EnsureDevice`（等待并在需要时解锁，解锁密码 `1234`）、`LaunchApp([deepLink])`、
`WaitFocused`、`DumpUi`（返回 UI dump 文本）、`TapText`（按文本点击）、`TapXY`（按坐标点击）、
`Texts`（把 dump 里的文本节点列出来，做断言方便）、`Shot`（截图到 `docs/images/screenshots/`）、
`CrashCount`（崩溃计数）、`SwitchStates`（开关状态读取）、`Resolve-Adb`（解析 adb 路径）。

默认包名 `com.thirdparty.xhs.debug`（`$script:PKG`）；截图目录由 `$PSScriptRoot` 推导，不依赖机器路径。
`adb` 位置不写死：优先 `$env:ADB`，其次 Android SDK 环境变量，最后 `PATH`（见脚本顶部注释）。

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
$d -match '清除缓存'              # 断言目标文本
Shot 'settings_after_tap'
```

要点：

- `DumpUi` 可能**崩溃并静默返回空文本**（耗时 2–3.5 秒，本身也会干扰时序）。空结果先怀疑 dump，
  不要直接判定"界面没渲染"。
- 断言失败时，**先怀疑断言**（选择器、文本、时序），再怀疑实现。
- 用 dump 判断"列表滚到哪儿了"时，**先滤掉常驻 chrome**（底部 tab、子 tab 标签、分类 chip、`去看看`/`刷新`/`共 N 个作品`），
  只比剩下的**内容项**；只取"前 N 个文本节点"会被 chrome 占满，产生假通过（见 `ai/GOTCHAS.md` A7）。
- 每完成一轮验证：`CrashCount` 确认无新增崩溃。

## 4. UI 改造的证据要求

| 改动类型 | 需要的证据 |
| --- | --- |
| 新增控件/入口 | 点击前后的 `DumpUi` 文本（证明可达 + 状态真的变了） |
| 视觉/主题/布局 | `Shot` 截图（改造前 + 改造后各一张），并说明"看哪一处不同" |
| 状态/时序（播放、缓冲、刷新） | 连续两次 dump 或两张截图，证明状态**随时间变化正确** |
| 数据相关 | dump 中的真实字段值（标题、计数），不要只截图看"有内容" |
| 列表/滚动状态保持 | **首项文本 + 其 y 坐标**：滚动后记一次，切 tab / 跳转返回后再记一次，两处必须完全一致（只比"有没有内容"不算证据；回归脚本见下文 §4.1） |

### 4.1 滚动保持的回归做法（2026-10-04 实测通过的那套）

1. 冷启动 → 进目标页 → **轮询等内容真正出现**（列表还空着的时候滑动是空操作，会得出"没滚"的错误结论）；
2. 记下"滚动前首项"→ 上滑若干次 → 记"滚动后首项"，两者相同说明手势根本没生效，先修脚本；
3. 切子 tab / 进详情或作者页 → 返回 → 再记首项：**文本与 y 都要一致**；
4. 截图各阶段留证，并在汇报里写明截图路径。

**"改了主题"不等于"UI 变了"**：如果改的是主题变量而渲染结果没变化，必须明说，不能算完成。

### 4.2 缓存清理的"一项只清一项"回归（2026-10-04 实测通过的那套）

对应 `ai/GOTCHAS.md` D8。清磁盘缓存曾经连带把内存位图缓存也清了，所以每次动 `AppCaches.clear()` 都要跑：

1. 冷启动 → 刷一会儿首页（把磁盘缓存与位图缓存都填起来）→ 进「设置 → 数据 → 清除缓存」；
2. dump 记下各项体积：`全部缓存 / 图片与封面缓存 / 图片内存缓存 / 其它临时文件`；
3. **只勾「图片与封面缓存」** → 底部按钮应显示「清除选中（1 项 · <磁盘那一项的体积>）」→ 确认框里只列这一项；
4. 清完后 dump 再记一次：磁盘那项归 0 B，而**内存位图缓存必须仍非 0**（这是关键断言）；全部缓存 = 剩余项之和；
5. 返回设置页，行内「共 X」应与新总值一致；`CrashCount` 为 0。

```powershell
# 只看体积数字，按出现顺序
(([regex]::Matches($d, 'text="([\d.]+ ?(?:B|KB|MB|GB))"') | ForEach-Object { $_.Groups[1].Value }) -join ' , ')
```

**"刚清完又变回来"要分清是哪个缓存**：磁盘缓存清掉后继续浏览会重新下载（体积回升正常），
内存缓存清掉只影响解码一次。判断时看的是**清理前后同一界面**的两组数字，不要跨浏览行为比较。

**「其它临时文件」的边界**：它只包含 `cache/` 下除了 `http_cache/`（有自己的勾选框）和
`xhs_local.db.lck`（SQLite 锁文件，0 B，删了零收益还可能破坏加锁，见 `AppCaches.isClearableTemp`）
之外的东西。所以它通常显示 `0 B` 才是正常的；如果它长期非 0，先 dump 一下 `ls -la cache/` 看到底是什么。

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

## 7. 播放器 / 小窗类改动的取证手法（2026-10-06 实测可用）

这类改动光看界面常常看不出差别（"到底在播还是暂停""小窗隐藏没隐藏"),下面几条是能给出**数字**的手法：

| 要证明的东西 | 命令 | 判据 |
| --- | --- | --- |
| 音频到底在不在播 | `adb shell dumpsys audio` 里按 `AudioPlaybackConfiguration piid:.*<uid>` 过滤，数 `state:started` / `state:paused` | `started=N` 就是在放；改前改后**对比**，别只看一次 |
| 有没有多起播放器（泄漏/重建） | `adb shell logcat -d | Select-String 'ExoPlayerImpl: Init'` 数行数 | 一条路径前后计数应保持一致；"展开小窗"从 1 变 2 就说明详情页自建了播放器 |
| 小窗窗口的真实尺寸/比例 | `adb shell dumpsys input`，找 `com.thirdparty.xhs.debug/...MainActivity` 那条的 `frame=[l,t][r,b]` | 宽高比应与视频比例一致（如 4:3 片 → 533×400）；这也是唯一稳定的"小窗还在不在"证据 |
| 小窗是否还在 PiP | `adb shell dumpsys activity activities | Select-String pictureInPicture`（`mLastReportedPictureInPictureMode=true`） | 注意：**不能**用它判断"用户是不是关掉了小窗"，关闭时它可能仍是 true |
| 触感有没有触发（点击/翻页/长按） | `adb shell dumpsys vibrator_manager`，按 `opPkg=com.thirdparty.xhs.debug` 过滤 | 只看**最近 50 条**（会滚动截断），所以**用 `createTime` 时间戳**判断"这次操作新增了几条"，不要用累计数（GOTCHAS I2/I3） |
| 前台到底是哪个应用 | `adb shell dumpsys activity activities | Select-String -m1 ResumedActivity` | 模拟器刚重启时界面可能停在别处，**不确认前台就点击会点到别的应用**（本轮真踩过） |
| 图片/视频"是不是真的在动" | 间隔 1.3 秒各截一次屏，比较 md5：`adb shell screencap -p /sdcard/p1.png`（换 p2），`adb shell md5sum /sdcard/p1.png /sdcard/p2.png` | md5 不同 = 画面在变化（在播/在加载）；相同 = 停住了 |
| "小窗里画的是不是只有视频" | `Shot` 截图后**看图**（或比对截图里是否出现详情页的标题/按钮文本） | 小窗里出现详情页 UI 就是状态错了（`inPip` 没藏住导航内容） |
| 锁屏/息屏 | `adb shell input keyevent 26`（再按一次亮屏，无 PIN 时上滑解锁） | 用于验证"切后台/锁屏该不该暂停"这类规则 |

**小窗往返之后，必须看画面 + 读进度**：只量「首项文本 / y 坐标」会漏掉「有声音没画面、进度回 0:00」
这类回归（2026-10-06 真踩过，见 `ai/GOTCHAS.md` H17）。做法：`screencap` 看一眼画面，
再让控制栏显出来读 `x:xx / y:yy`。

**取不到的**：系统小窗的**关闭手势**（那是 SystemUI 的覆盖层，`input swipe` 注入不进去）。要验证"关闭小窗"的收尾，
用同一条代码路径的等价触发：小窗播放中息屏（Activity 走 `onStop`、会话仍在），或等宽限任务到期；并在汇报里写明这不是真手势。

## 8. 临时探针要收尾

为定位问题临时加的日志、临时代码、临时环境改动，验证完成后：

1. 删除探针代码；
2. `grep -ri "probe" app/src`（或对应目录）复查无残留；
3. 还原临时环境改动（例如临时改的网络配置、临时的 SharedPreferences 值）；
4. 重新编译一次，确认清理后仍可构建。

## 9. 汇报格式

```
需求：<原话或摘要>
做了什么：<文件:行 + 一句话>
证据：<dump 片段 / 截图路径 / 命令输出关键行>
状态：已完成 | 未完成（原因） | 未验证（缺什么）
```

没做完的必须写"未完成"，不要用"基本完成"这类措辞。
