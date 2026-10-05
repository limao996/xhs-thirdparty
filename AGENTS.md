# AGENTS.md — AI 协作入口

> 本文件是**给 AI 编码代理看的**，不是给人读的项目介绍（那在 [README.md](README.md)）。
> 目标：任何 AI 在 30 秒内知道「这是什么、能改什么、不能动什么、改完怎么证明」。
> 与本文件冲突的仓库外习惯一律以本文件为准。

## 0. 硬约束（违反即视为改动失败）

| # | 约束 | 原因 / 症状 |
| --- | --- | --- |
| 1 | **输出简体中文**（代码、命令、标识符保持原文） | 用户偏好 |
| 2 | 纯 Compose，**不引入 XML 布局**，不新增 `appcompat` / `com.google.android.material` | 已刻意移除，UI 全在 `ui/` 下的组合函数里 |
| 3 | material3 **不得降到 BOM 版本**（保持 `1.5.0-alpha29`） | `MaterialExpressiveTheme` / `MotionScheme` 只在 alpha 线公开 |
| 4 | `androidx.fragment:fragment-ktx` **不得低于 1.8.9** | 1.2.5 会让 biometric 抛 `Can only use lower 16 bits for requestCode` |
| 5 | `CLIENT_VERSION` buildConfigField **保持 `2.6.0`** | 它与服务端协议对齐，不是 App 版本 |
| 5b | `minSdk` 保持 **26**（Android 8.0）：画中画本身就是 API 26 起，播放器主路径与小窗绑在一起；不要再降回 24 —— 7.x 上有 `Constructor#getParameterCount` 之类的 API 26 调用会直接崩（见 `docs/REVIEW.md` P0-2） | 已发布说明（README / BUILD / CONTEXT）都必须同步成「Android 8.0 及以上」 |
| 6 | 不修改 `keystore/release.jks`；不改 `signingConfigs.release` 口令 | 换密钥会导致已装用户无法覆盖升级 |
| 7 | `versionCode` 用 `appVersionCode` 表达式（时间戳推导），不手写常数 | 必须 32 位内且单调递增 |
| 8 | **不提交** APK / 截图 / 反编译产物 / `local.properties` | 见 `.gitignore`；仓库只放源码与文档 |
| 9 | 改源码一律用 `edit` / `write` 工具，**不要用 PowerShell 拼字符串写文件** | 拼串会静默产生乱码或丢内容 |
| 10 | 声明"已修复/已验证"之前必须有可复现证据（编译输出、UI dump、截图） | 见 `docs/VERIFY.md` |
| 11 | **不得移除或绕开游客账号轮换链路**（`v2/app/init` → `login-with-guest` → 校验 VIP → 退避） | 这是本项目唯一的核心功能；少了它页面只剩付费墙（见第 1 节与 `docs/ARCHITECTURE.md` §3.1） |
| 12 | 文档里**不要写"不破解付费 / 不绕过付费限制"这类与事实相反的声明** | 本 App 的机制就是自动化领取新游客 VIP 福利，描述必须与实现一致 |
| 13 | 缓存清理**一项勾选只清一项**：`AppCaches.clear()` 按 `CacheKind` 分别调用，`XhsRepository.clearHttpCache()` 只清磁盘、不得顺手清内存位图缓存 | 之前清磁盘会连带清内存，确认框列的是 2 项、实际清了 3 项，与用户勾选不符 |
| 14 | 启动自动检查更新**只在真有新版时弹窗**，其余（无正式版 / 限流 / 断网 / 已是最新）一律静默；「跳过这个版本」必须持久化到 `settings` | 每次启动都弹会骚扰用户；GitHub 匿名 API 只有 60 次/小时/IP，实测会 403 |
| 15 | 自动检查更新**12 小时一次**（`settings.update_checked_at`），且只有**成功**的检查才写时间戳 | 每次冷启动都查既打扰用户、也会把匿名额度烧光（实测 403） |
| 16 | 任何菜单 / 弹窗一律用**原生的 `material3.AlertDialog`**（带遮罩与动画），不要用 `DropdownMenu`，也**不要自绘对话框外壳**（不要自己画遮罩/自己写入场动画，用户明确否决过） | 下拉面板没有半透明遮罩、没有入场动画，铺在瀑布流卡片或全屏视频上还容易被边缘裁掉；原生对话框系统会负责遮罩与动画（动画受系统"动画时长比例"影响，用户关掉就瞬间完成，这是设置不是缺陷） |
| 17 | 画中画：**进小窗时不要退出详情页**（不要 `onBack()`）—— 小窗期间**导航内容照常组合**（`AppNavHost` 一直挂着），只是上面盖一层 `Color.Black` + 视频把整页 UI 挡住（**不要**改回 `if (!pipActive) { AppNavHost(...) }`：那样目标页的 `rememberSaveable`（滚动位置 / 全屏状态 / 图片页码）会随组合一起丢，展开回来就回顶部），返回栈记录与 ViewModel 原样保留；退出小窗后的判定是"**先交接、再恢复**"：`onResume`/宽限任务（**2.5s**，实测展开到 `onResume` 要 1.15s）→ `handBackForDetail()` → `PlaybackHandoff.givePlayer()` → **最后**才 `inPip=false`；「关闭」的收尾是"有会话 + 走到 `onStop`"，**但锁屏/息屏不算关闭**（`!PowerManager.isInteractive \|\| KeyguardManager.isKeyguardLocked` 时不动会话，否则解锁后小窗里会变成"视频外面套着详情页 UI"），并在 `onResume` 里幂等兜底 `isInPictureInPictureMode && hasSession() → inPip = true`；**判定"小窗还在不在"不要靠 `isInPictureInPictureMode`**（关闭时它可能仍是 true，会永远不释放），权威信号是退出回调 + `onResume`；收到退出回调又走到 `onStop` 时用 300ms 确认窗口兜底，`onDestroy` 再兜一次 `closeAndRelease()`；`PipController.isHandedOver` **只认小窗会话**（`_session.value?.player === p`，用于"要不要暂停/释放"；**不要把 `PlaybackHandoff` 的持有并进来** —— 信息流交给详情页的那台会永远留着那个标记，一合并详情页切后台/锁屏就永不暂停、返回时也不释放）；"展开小窗刚交回详情页"那一瞬间另用 `PipController.isReturningToDetail`（= `PlaybackHandoff.isHeldForHandBack`）放过那次 `ON_STOP`；详情页/信息流切后台、锁屏要暂停（`PauseWhenNotStarted` 的 `ON_STOP`，回前台按 `resumeOnStart` 续播）；小窗比例按**旋转修正后**的视频尺寸算并在 `onVideoSizeChanged` 时重设，小窗画面按视频比例信箱式绘制；入口在**播放器的菜单**，控制栏用**三个** `RemoteAction`：后退 10 秒 / 播放暂停 / 前进 10 秒；**进小窗失败要回滚**（`enterPictureInPictureMode()` 返回 false 时调 `PipController.abortStart()`，否则 `inPip` 永久 true、导航内容被永久藏起来）；**「系统说还在 PiP 但 Activity 被 stop」**（来电 / 别的应用全屏）按「暂停 + `PIP_LOST_CHECK_MS`（15s）复查」处理，不当成关闭；**所有覆盖层都要 `!pipActive` 门禁**（应用锁封面、剪贴板回流对话框、更新弹窗），否则会被画进小窗 | 缺交接登记 = 小窗黑屏；不释放 = 关掉小窗后还有声音（`GOTCHAS` H10/H12/H14）；进小窗时 Activity 会走一次 `ON_STOP`，无条件 pause 会让小窗里停在暂停；退出小窗时 `destination.route` 是**模式串**（`detail/{noteId}`），比对前要把实参拼回去；PiP 最多 3 个自定义按钮 |
| 17b | 信息流（`ui/screens/VideoFeedScreen.kt`）里**所有"停播"分支**都必须先问播放器归属：`LaunchedEffect(active, player)` 的 `active=false → pause()`、`inPip` 分支、页面 dispose —— 判据统一 `PipController.isHandedOver(p)`（**只认小窗会话**：从推荐页点进详情的那台播放器在信息流这一侧只是"在底下"，无差别 pause 会把小窗里的画面按停；但它**没有**被小窗接管时，信息流该暂停就暂停） | 这是"从推荐页进详情再开小窗，小窗自动暂停"的根因（实测日志 `feed active=false handedOver=false`）；深链路径没有信息流这一层，所以只在推荐页路径复现 |
| 18 | 同一角落的浮动按钮必须**一起排**（`ui/components/CornerFabStack.kt`：刷新在上、稍后观看在下），不许各画各的 | 曾经两个 FAB 各自贴在右下角，后画的把前一个完全盖住，用户当场发现"稍后观看替代了刷新" |
| 19 | 交互一律用**系统触感 API**（`ui/components/Haptics.kt` → `LocalHapticFeedback`），**不要用 `Vibrator`**；四档语义按注释用（轻点 `tick()`=`ContextClick`；长按 `longPress()`；换挡 `segment()`；确认/移除 `confirm()`/`reject()`）。**后三档用的常量是 API 30/34 才有的：必须在 `Haptics` 内部按 `Build.VERSION.SDK_INT` 降级**（低版本用 `ContextClick`/`LongPress` 代替），否则低版本上系统会静默忽略、表现为"点了没感觉"。新写的可点元素直接用 `haptics.click { }` / `confirmClick { }` / `rejectClick { }` 包装。**屏幕上我们自己画的按钮一律要有触感**（包括返回 / 关闭 / 取消）；**不加**的只有系统返回手势本身 —— 那一下系统自己会给。翻页触感只有 `PagerPageHaptics(pagerState)` 一个实现，两处图文（嵌入画廊 / 全屏查看器）必须共用 | 系统 API 尊重用户的触感开关与强度，也不需要 `VIBRATE` 权限；成员版包装函数让"加触感"只是一行，避免反复漏加 |
| 20 | 稍后观看队列**不提供排序**：按加入时间排列，**不要**序号、**不要**上移/下移按钮、**不要**长按拖动（三种排序实现都被用户否掉了）。作品列表项（收藏 / 最近浏览 / 队列）统一用 `FeeBadge` 显示标签，不要在作者名前加序号 | 队列顺序不是用户要的功能，而每一种排序交互都带来一类新问题（拖动不稳、按钮挤、序号占位）；标签与瀑布流保持一套即可 |
| 21 | 关注按钮只有一套实现：`ui/components/FollowPill.kt`（未关注 = 主色实心「关注」，已关注 = 次级容器色「已关注」），详情页 / 关注页 / 关注 tab / 粉丝圈 tab 全部用它，**不要再引入 `Button` / `OutlinedButton`**；取消关注一律先弹 `ConfirmActionDialog`，关注直接生效 | M3 的 Button 在列表行里又高又宽，把作者名挤窄（用户反馈"按钮太大"）；取消关注误触会让作者从列表里消失，而页面本身不给反馈 |
| 22 | 分享一律走**系统分享面板**（`Intent.ACTION_SEND` + `text/plain` + `createChooser`），不要再自己写"剪贴板 + Toast"式的分享。同时：①**分享前**用 `ShareText.markSelfShared(noteId, text)` 登记，`MainActivity` 的剪贴板回流检测遇到与自己登记一致的内容要直接忽略（否则"复制自己刚分享的口令"会绕回同一个笔记）；②用 `Intent.EXTRA_EXCLUDE_COMPONENTS` 把自己从面板里排除；③`EXTRA_TITLE` 只在面板预览显示、不会发给目标，用固定的用户面向文案（「来自「小黄书」的分享」），不要放作品标题 | 用户点分享是要"发出去"；分享面板里同样能复制，而"复制→回应用"正是口令回流链路，必须区分"别人给我的"和"我自己刚发出去的" |
| 23 | 拖动类控件（进度条 / 倍速条）的触感是**按下即触发**（`Modifier.pressHaptic(haptics)`：`PointerEventPass.Initial` 观察 Press，不消费事件），**不要**在拖动过程中按比例连发 | 用户要的是"按下去抖一下"，拖动中连发会显得吵；观察而非消费事件，所以不影响控件自身手势 |
| 24 | 可点区域里**不要同时**挂单击与双击语义（`combinedClickable(onClick=…, onDoubleClick=…)`）：单击必须等双击判定窗口（~300ms）才触发，用户会感觉"点了半天才跳转"。信息条这类"点一下就走"的区域用普通 `clickable`，双击暂停留给视频画面那一层 | 这是系统手势判定的固有代价，不是性能问题；把两种语义分层放，点击才跟手 |

## 1. 项目一句话

小黄书（老司机软件）第三方 Android 客户端，桌面名「小黄书」（debug 安装为「小黄书.debug」）：Kotlin + Jetpack Compose（M3 Expressive），Room 本地缓存，OkHttp + AES 全量加密包体，media3/HLS 播放器，WebDAV 备份，指纹应用锁。包名 `com.thirdparty.xhs`（debug 加 `.debug`）。

**核心机制（动账号相关代码前必读）**：VIP 不是"破解校验"骗出来的，是**不停换新游客号**换出来的 ——
服务端会给每一个**新注册**的设备身份发一段 VIP 体验窗口，客户端在窗口快用完时换一个新身份，就又能接着看。
链路（顺序不能变）：`IdentityGuess.randomFresh()` 生成随机身份 → `v2/app/init` 注册身份（**这一步才建号**）→
`v2/user/login-with-guest` 拿 `user_token`/`user_hash` → `v2/mine/user-info` 确认新号真的带 VIP
（`XhsRepository.switchToVipAccount()`）。触发点是 `XhsRepository.ensureAccountForRequest()`，由
`XhsApi.beforeAccountRequest` 在每个"需要账号的请求"前调用；不足 `VIP_MIN_REMAINING_S = 60L` 秒就换。
身份必须持久化（`CredentialStore` 的 `device_identity`，换号时清 `user_hash` 与 VIP 缓存），
一次尝试只建**一个**新身份，失败按 `VIP_SWITCH_BACKOFF_BASE_S = 60L` 翻倍退避、上限 `VIP_SWITCH_BACKOFF_MAX_S = 30 分钟`。

## 2. 仓库地图

| 路径 | 职责 | 改动注意 |
| --- | --- | --- |
| `app/build.gradle` | 版本号、签名、`buildConfigField`、依赖矩阵 | 版本号规则见 `docs/BUILD.md` |
| `app/src/main/AndroidManifest.xml` | 权限、Activity、深链 `xhstp://note`、`launchMode="singleTask"`（深链复用同一实例，避免第二个界面/第二个小窗）、`supportsPictureInPicture`、`usesCleartextTraffic`（只为局域网 http WebDAV） | 新增权限要在 `docs/BUILD.md` 的权限清单与 `docs/PROTOCOL.md` 里同步（README 是用户向介绍，不放权限表）（清单里只声明 INTERNET / ACCESS_NETWORK_STATE / WAKE_LOCK；合并后的 APK 还会带 biometric 库的 USE_BIOMETRIC / USE_FINGERPRINT 与 androidx 的 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION） |
| `data/XhsRepository.kt` | **单一数据源**，所有网络读写都在这里 | 新接口先加在这里，不要在 UI 直接调 `XhsApi` |
| `data/XhsDatabase.kt` | Room 数据库 `xhs_local.db`（v3：saved / history / followed / watch_later） | 改 schema 必须 bump version **并加真迁移**，`addMigrations` 要成对维护：现存 `MIGRATION_1_2`（v1 从未发布，这条**刻意 DROP 重建**三张缓存表）与 `MIGRATION_2_3`（只 `CREATE TABLE watch_later`）；**不要**再挂 `fallbackToDestructiveMigration()` |
| `data/BackupManager.kt` | 备份/恢复内容与格式 | 改字段要同步 `docs/ai/CONTEXT.md` 的备份清单 |
| `net/XhsApi.kt` | 请求封装、重试、会话自愈 | 重试次数/退避在这里（`NETWORK_ATTEMPTS` / `RETRY_BACKOFF_MS`） |
| `net/XhsCrypto.kt` | AES/CBC 加解密 + CDN 图片 AES/ECB | 参数见 `docs/PROTOCOL.md`，不要"顺手"改 |
| `net/WebDavClient.kt` | WebDAV 客户端 | 备份固定写 `xhs/` 子目录 |
| `net/UpdateChecker.kt` | 检查更新（GitHub Releases，**唯一不加密的外网请求**；WebDAV 也不经 AES，见 `net/WebDavClient.kt`） | 必须用**独立的 OkHttpClient**（共用的带 64 MB 磁盘缓存会把应答缓存住），且必须带 `User-Agent`（否则 GitHub 403）；见 `docs/ai/GOTCHAS.md` G1。启动时由 `App.checkUpdateOnLaunch()` 自动查一次，仅 `Newer` 且不等于「已跳过版本」时才写 `App.pendingUpdate` → `MainActivity` 弹 `ui/components/UpdateAvailableDialog.kt`；GitHub 匿名 API 上限 60 次/小时/IP，超了是 403（映射成 `Failed`，UI 如实显示"检查失败：GitHub 限流"） |
| `data/AppCaches.kt` | 可清理缓存的枚举、逐项体积与清理（含顶层 `formatBytes`） | 每种缓存**各自一个 `CacheKind`、各自一个勾选框**，清理必须一一对应（硬约束 13）；`IMAGE_DISK` 走 `XhsRepository.clearHttpCache()`，`IMAGE_MEMORY` 走 `ui/components/XhsAsyncImage.kt` 的 `clearImageMemoryCache()`；`TEMP_FILES` 只含 `cache/` 下除 `http_cache/` 与 `*.lck`（SQLite 锁文件，实现按后缀判断）之外的文件（`isClearableTemp`） |
| `ui/components/PipController.kt` + `ui/components/WatchLaterFab.kt` / `WatchLaterBar.kt` + `ui/screens/WatchLaterScreen.kt` | 画中画小窗的持有者（会话 / 控制栏 `RemoteAction` / 展开与关闭）与稍后观看队列的三种入口 | 画中画的播放器所有权见硬约束 17 与 `docs/ai/GOTCHAS.md` H 节；队列按加入时间排列、不提供排序（硬约束 20） |
| `navigation/AppNavHost.kt` + `Routes.kt` | 唯一路由注册处 | 新页面必须同时登记 `Routes` 常量与 `HomeTab`（如属底部页） |
| `ui/screens/*.kt` | 页面级组合函数 | 每个 screen 对应一个 `ui/viewmodel/`；子 tab 内容要包 `rememberSaveableStateHolder()`，列表滚动状态要按 `resetKey` 分组（`key(resetKey) { … }`），身份位同名却是新列表时必须单调递增（GOTCHAS C2/C8） |
| `ui/components/*.kt` | 可复用组件（瀑布流/播放器/画廊/对话框/水印状态…） | 组件不要直接访问 Room |
| `ui/viewmodel/*.kt` | 状态与业务编排 | 用 `RepoViewModelFactory` 注入仓库 |
| `ui/theme/Theme.kt`, `ui/theme/Tokens.kt` | M3 Expressive 主题、间距/圆角/`Scrim` 令牌 | 新颜色优先用 `MaterialTheme.colorScheme`，scrim 只用于媒体之上 |
| `tools/verify.ps1` | 设备验证函数库（解锁、启动、dump、点击、截图、崩溃计数） | 是 PowerShell，改动后要真跑一次；**入库**，只有 `tools/out/` 与截图不入库 |
| `app/src/test/` | 单元测试（目前只有 `net/UpdateCheckerTest`） | `gradlew.bat testDebugUnitTest`；改了版本比较 / GitHub 应答解析就跑 |
| `tools/fixtures/` · `tools/probes/` · `tools/webdav_server.py` | 测试用 SharedPreferences fixture、探针、本机 WebDAV 服务 | fixture 里有真实会话凭据（审查 P0-13，用户明确暂不处理）；fixture 不得被应用运行期引用 |
| `docs/` | 项目文档 | 结构变更同步 `docs/README.md` |
| `docs/ai/` | **AI 档案本体** | 见下节 |

上表中未写目录前缀的源码路径，均相对于 `app/src/main/java/com/thirdparty/xhs/`（例：`data/XhsRepository.kt` 即 `app/src/main/java/com/thirdparty/xhs/data/XhsRepository.kt`）。

## 3. 常用命令

```powershell
# 构建（Windows；已解压的 Gradle 直调更快，省掉 wrapper 校验的 ~120s）
gradlew.bat assembleDebug                        # 标准路径
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.8.0-bin\*\gradle-9.8.0\bin\gradle.bat" assembleDebug

# 安装 + 冷启动（必须 force-stop，否则测的是旧进程）
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.thirdparty.xhs.debug
adb shell am start -n com.thirdparty.xhs.debug/com.thirdparty.xhs.MainActivity

# 设备验证函数库
. .\tools\verify.ps1 ; EnsureDevice ; LaunchApp ; $d = DumpUi ; TapText '设置' ; Shot 'step1'
```

验证回路与取证细节：`docs/VERIFY.md`。

## 4. 变更流程

1. **定位**：先 `grep` 到唯一职责文件（上表），不要在 UI 里打补丁绕过仓库层。
2. **复现**：出现缺陷时先复现（真实设备/模拟器），再改。没有复现的"修复"不许提交。
3. **编辑**：用 `edit`；一次只改一个关注点。
4. **编译**：编辑落盘后再启动构建（顺序反了会测到旧代码）。
5. **验证**：`docs/VERIFY.md` 的最小回路；UI 改动给截图或 `DumpUi` 文本。
6. **同步档案**：动了结构/约束/坑，就更新 `AGENTS.md` 与 `docs/ai/GOTCHAS.md`。
7. **提交**：中文提交信息 + 前缀；一次提交一个关注点。**默认只做本地提交 —— 用户明确说"推上去"才 `git push`。**

## 5. 汇报格式（用户要求的）

- 逐条需求 → 逐条汇报：`需求 → 做了什么 → 证据 → 状态`。
- 没做完的必须显式写「未完成 / 未验证」，不许用"基本完成"含糊过去。
- 不给结论性形容词（"更流畅了"），给数字或可复现的观测。

## 6. 相关文件

- 项目介绍与快速开始：[README.md](README.md)
- 一页上下文：[docs/ai/CONTEXT.md](docs/ai/CONTEXT.md)
- 约定：[docs/ai/CONVENTIONS.md](docs/ai/CONVENTIONS.md)
- 踩坑规则：[docs/ai/GOTCHAS.md](docs/ai/GOTCHAS.md)
- 提示词模板：[docs/ai/PROMPTS.md](docs/ai/PROMPTS.md)
- 架构 / 协议 / 构建 / 验证：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)、[docs/PROTOCOL.md](docs/PROTOCOL.md)、[docs/BUILD.md](docs/BUILD.md)、[docs/VERIFY.md](docs/VERIFY.md)
