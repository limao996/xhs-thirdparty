# 深度审查报告（2026-10-06）

> 审查对象：`xhs-thirdparty`（Kotlin + Compose，82 个 Kotlin 文件 / 15038 行，debug APK 25.15 MB）
> 审查方式：①`gradlew lintDebug` 全量跑（96 项：**40 error / 47 warning / 9 hint**，
> 报告 `app/build/reports/lint-results-debug.xml`）；②人工逐文件审读账号链路、播放器所有权、
> 数据库迁移、缓存清理、清单配置；③并行三路专项审查（网络与账号 / UI 与 Compose / 数据与构建，
> 结论见文末附录位）。
> 本文所有结论都带 `文件:行号`，可直接复核。

## 零、结论摘要（P0，按修复优先级）

| # | 问题 | 位置 | 来源 |
| --- | --- | --- | --- |
| P0-1 | 用了 API 26 的 `Constructor#getParameterCount`，而 `minSdk 24` → **Android 7.x 上所有页面都建不出 ViewModel**，与 README「支持 Android 7.0」矛盾 | `common/RepoViewModelFactory.kt:17` | 我（已复核） |
| P0-2 | 自动换号**没有单飞锁**：成功路径设的冷却 = 当前秒，判据恒为 false，并发会各建一个身份 | `data/XhsRepository.kt:405,422,488` | 专项A |
| P0-3 | 身份闸门是**全局普通布尔**：并发协程要么一起冲进换号、要么在闸门执行期间**整体绕过** | `net/XhsApi.kt:73,81-90` | 专项A |
| P0-4 | 闸门异常被 `runCatching` 静默吞掉 → 换号失败被伪装成"内容为空/付费墙" | `net/XhsApi.kt:85` | 专项A |
| P0-5 | 自愈重登后账号已变却不 bump 身份 epoch → 详情页继续用**旧账号发放的** media URL | `net/XhsApi.kt:104-113` | 专项A |
| P0-6 | 自愈失败时把**第一次的失败响应**当结果返回（`reestablished=true` 无条件置位） | `net/XhsApi.kt:107,112-113` | 专项A |
| P0-7 | 备份文件里写着 **WebDAV 明文密码**，而 `WebDavClient` 注释与 README/应用内文案都否认这一点 | `data/BackupManager.kt:94-100` | A/C（已复核） |
| P0-8 | 恢复会写 `settings.vipEnd`（导出从不写）→ 篡改/旧版备份填个大值即可**永久停摆换号** | `data/BackupManager.kt:183` | 专项A（已复核） |
| P0-9 | 恢复不校验 `rawJson`，而 `JSONObject("")` 在 ViewModel 里未捕获 → **启动崩溃** | `BackupManager.kt:231`→`ProfileViewModel.kt:43-45`、`LocalListViewModel.kt:43-48` | 专项A（已复核） |
| P0-10 | 取消系统文件选择器后 `systemPickerActive` 不复位 → **应用锁此后永久失效**（直到进程重启） | `ui/screens/BackupScreen.kt:100,115`（判据 `MainActivity.kt:276-282`） | B/C（已复核） |
| P0-11 | `enterPictureInPictureMode` 返回值被忽略、随后无条件 `onBack()` → 系统拒绝进小窗时留下**后台出声的孤儿播放器** | `ui/screens/DetailScreen.kt:198-211` + `PipController.kt:65-69` | 专项B |
| P0-12 | 「关于」页写着「**不破解付费校验**」——与实现相反的声明，正是硬约束 12 明令禁止的措辞 | `ui/screens/AboutScreen.kt:132`（入口 `ProfileScreen.kt:271`） | 专项B（已复核） |
| P0-13 | 仓库里提交了**真实 `user_token` + 设备身份 + 账号历史**（4 个 fixture） | `tools/fixtures/prefs_{backup,nopick,novip,stalevip}.xml:3,5,6,8` | 专项C（已复核） |
| P0-14 | 「覆盖」恢复**不是原子的**：先清三张表再逐条写，无 `withTransaction` → 中途失败 = 用户数据已清空 | `data/BackupManager.kt:214-276` | 专项C |
| P0-15 | Manifest **没有 `launchMode`**：深链会新建第二个 `MainActivity`，`onNewIntent` 永不触发（代码注释却断言会） | `AndroidManifest.xml:22-27` vs `MainActivity.kt:104-106` | 专项C（已复核） |
| P0-16 | `versionName` 仍是 `1.2.1`，而 v1.2.1 之后已落地十几批功能 → 关于页与"检查更新"报"已是最新"是错的 | `app/build.gradle:25` | 专项C |
| P0-17 | 交互规范（本项目自己的硬约束）被执行得**前后不一致**：作者页第 5 份自绘关注按钮（违反 21）；7 处「返回」与 9 处「关闭/取消」**错误地加了触感**（违反 19） | `AuthorScreen.kt:119-140`、`DetailScreen.kt:361`、`MediaPlayer.kt:632`、`ConfirmActionDialog.kt:39` 等 | 专项B（已复核 ①⑤） |

>
> 另有 3 条"低版本可能静默失效"的疑点值得单独提：`Haptics` 用的 `SegmentTick`/`Confirm`/`Reject`
> 是 API 30/34 才有的常量，而 `minSdk 24` —— **低版本上这三档触感可能根本没有效果**，
> 这或许正是用户反复反馈"触感不对/没有"的一部分原因（专项B-P2-30 / 专项C-P2-30，标注"待确认"）。

## 一、P0（当前就会坏）

### 1. `RepoViewModelFactory` 用了 API 26 的方法，而 minSdk 是 24
- 证据：`app/src/main/java/com/thirdparty/xhs/common/RepoViewModelFactory.kt:17`
  `it.parameterCount == 1` —— `java.lang.reflect.Constructor#getParameterCount` 在 Android 上
  **需要 API 26**（lint：`NewApi`）。同一处 `.parameterCount` 被 lint 单独点名。
- 影响：`README.md:47` 写明「要求 Android 7.0（API 24）及以上」，`docs/BUILD.md:72`、
  `docs/ai/CONTEXT.md:19` 也都写 minSdk 24。但在 API 24/25（Android 7.0/7.1）上，任何走这个工厂
  的 ViewModel（几乎每个页面：`HomeScreen`/`DetailScreen`/`SearchScreen`/`DiscoverTabScreen`…）
  创建时都会抛 `NoSuchMethodError` → **应用在这两个版本上不可用**。
- 修法（二选一）：
  1. 改成 API 1 就有的写法：`modelClass.constructors.firstOrNull { it.parameterTypes.size == 1 }`；
  2. 或把 `minSdk` 提到 26 并同步改 README / BUILD / CONTEXT 三处文档（不推荐：会丢 7.x 用户）。

## 二、P1（较可能出问题 / 安全与数据风险）

### 2. 自动换号没有单飞锁，并发会多建身份
- 证据：`data/XhsRepository.kt:361` `ensureAccountForRequest()`（由 `XhsApi.beforeAccountRequest`
  在**每个需要账号的请求前**调用）→ `:386 switchToVipAccount()`：
  判断依赖 `api.cachedVipEnd`（`:398`）、冷却判据 `nowS0 < nextSwitchAllowedAtS`（`:405`），
  失败计数 `switchFailStreak`（`:488`，普通 `var`，读写发生在 `Dispatchers.IO` 上）。
  全流程**没有 Mutex / 单飞标志**。
- 影响：界面首屏并发发多个请求时，若 VIP 窗口刚好过期，两路都会通过缓存判断 → 各自
  `api.freshRandomMac()` + `loginAsDevice()` → **注册两个身份、其中一个被孤儿化**，且换号发生在
  请求中途（同一个请求可能前半段用旧身份、后半段用新身份）。历史反馈"创建了多个账号"的另一半来源。
- 修法：`private val switchMutex = Mutex()`，把 `:386` 的函数体整体 `withLock`；`switchFailStreak`
  换 `AtomicInteger`（或全部放进同一个 `Mutex` 保护）；`nextSwitchAllowedAtS` 在成功路径也设一个
  短冷却（如 5s），防止成功瞬间的并发重复进入。

### 3. `.fallbackToDestructiveMigration()` 与本文件注释/硬约束直接冲突
- 证据：`data/XhsDatabase.kt:62` 有 `.fallbackToDestructiveMigration()`，而同文件 `:33` 的注释写
  「这一步必须是**真迁移**，不能靠 `fallbackToDestructiveMigration()` 兜底」。
- 影响：**今天**没问题（v2→v3 有真迁移 `:37`），但将来任何一次忘了写迁移的版本升级，Room 会
  **静默删除用户的收藏 / 最近浏览 / 关注 / 稍后观看**（"destructive" 的真正语义），而文档承诺的
  "不会丢数据"就不成立了。
- 修法：删掉这一行（宁可启动即崩，让人立刻发现漏写迁移）；若确实想要降级兜底，改成
  `fallbackToDestructiveMigrationOnDowngrade()`。

### 4. 备份与凭据的暴露面
- 证据：
  - `AndroidManifest.xml:15` `android:allowBackup="true"`；
  - `CredentialStore.kt:17` 明文 SharedPreferences `xhs_guest`（存 `device_identity`、`user_hash`）；
  - `WebDavClient.kt:163,172` 明文 SharedPreferences `webdav`（服务器地址 / 账号 / **密码**）；
  - `data/BackupManager.kt:94-99` **主动把 WebDAV 口令写进备份文件**（注释里是知情的）。
- 影响：系统云备份 / `adb backup` 会把访客身份与 WebDAV 口令一起带走；备份文件本身（本地文件或
  用户填的云端目录）也含口令，发到别人手里等于交出网盘账号。
- 修法：`allowBackup="false"`（或 `dataExtractionRules` 排除 `xhs_guest`/`webdav` 两个 prefs）；
  备份里的口令改成默认不含、由 UI 显式勾选并写明后果；至少用 EncryptedSharedPreferences。

### 5. 全局放开明文流量
- 证据：`AndroidManifest.xml:13` `android:usesCleartextTraffic="true"`，仓库里没有
  `res/xml/network_security_config.xml`。
- 影响：为了支持用户自填的 `http://` WebDAV 服务端而全局放开，等于对**所有**域名允许明文
  （包括本该强制 HTTPS 的接口），中间人可降级。
- 修法：加 `networkSecurityConfig`，只对"用户填写的 DAV 主机"放行明文（或干脆要求 HTTPS），
  主清单去掉 `usesCleartextTraffic`。

### 6. 两处状态收集不感知生命周期
- 证据：`MainActivity.kt:260` `App.INSTANCE.themeState.collectAsState()`；
  `ui/screens/HomeScreen.kt:335` `App.INSTANCE.themeState.collectAsState()`。
- 影响：页面不可见时仍在收集（后台耗电/无效重组）；项目其它地方都用的是
  `collectAsStateWithLifecycle()`，这两处是漏网的。
- 修法：统一改 `collectAsStateWithLifecycle()`。

### 7. PiP 的 API 守卫只在外层
- 证据：lint `NewApi` 8 处，其中 6 处在
  `ui/components/PipController.kt:133,136,139,140,182`（`PictureInPictureParams.Builder` / `RemoteAction`
  需 API 26），另 2 处是 `RepoViewModelFactory:17`（见 P0-1）与
  `res/values(-night)/themes.xml` 的 `windowLightNavigationBar`（需 API 27）。
  目前两个调用点都有 `SDK_INT >= O` 守卫（`MainActivity.kt:180`、`DetailScreen.kt:196`），
  所以**现在不会崩**，但守卫在调用方而不在被调用方。
- 修法：在 `buildParams()` / `actions()` 内部再守一次（`if (SDK_INT < O) return null`）；
  `windowLightNavigationBar` 挪到 `values-v27/themes.xml`。

## 三、P2（整洁性 / 规范 / 体验）

| # | 问题 | 证据 | 建议 |
| --- | --- | --- | --- |
| 8 | media3 未标注 `@UnstableApi`，lint 报 27 处 | 全在 `ui/components/VideoPlayer.kt` | 文件头加 `@file:OptIn(androidx.media3.common.util.UnstableApi::class)` |
| 9 | Compose `Modifier` 参数位置不符规范（5 处） | `XhsAsyncImage.kt:39,89`、`FeeBadge.kt:85`、`FullscreenImageViewer.kt:91`、`DiscoverTabScreen.kt:613` | `modifier` 放成第一个可选参数 |
| 10 | `LocalContext.current as? Activity`（2 处） | `navigation/AppNavHost.kt:247`、`HomeScreen.kt:120` | 换 `LocalActivity.current`（Compose 1.7+），语义更明确 |
| 11 | 组合里非可观察方式读 Locale | `ProfileScreen.kt:165` | 用 `LocalConfiguration` / 显式 `Locale` 格式化 |
| 12 | 未用资源 / 图标规范 | `res/values/colors.xml:3`（`icon_bg`）、`res/mipmap-anydpi-v26/ic_launcher.xml:7`（缺 monochrome） | 删无用颜色；补 monochrome（Android 13 主题图标） |
| 13 | `UseKtx` 20 处、依赖普遍落后一个大版本 | lint `GradleDependency` ×7 / `NewerVersionAvailable` ×2（core-ktx 1.17→1.19.1、okhttp 5.1→5.5、fragment-ktx 1.8.9→1.9.1 等） | 可择机升级；**`fragment-ktx` 升级前先看生物识别 requestCode 行为**（硬约束 4） |
| 14 | PiP 未按 targetSdk 31+ 的建议开启自动进入 | lint `PictureInPictureIssue`（`AndroidManifest.xml:12`） | `setAutoEnterEnabled(true)` + `setSourceRectHint(...)`（想支持"上滑回桌面自动小窗"时） |
| 15 | lint 目前是"红的"（40 errors 直接 fail 构建） | `gradlew lintDebug` → `BUILD FAILED` | 先修 P0/P1，再对纯规范项建 `lint-baseline.xml`，让 lint 能进 CI |

## 四、值得肯定的地方（不是客套，是可复核的工程习惯）

1. **播放器所有权**这一块是本仓库最扎实的设计：`PipController.isHandedOver` +
   `PlaybackHandoff` 把"谁该 release"讲清楚，连踩过的两个坑都写进了 `GOTCHAS H10/H12`。
2. **`proguard-rules.pro` 的注释记录了"跑过混淆构建才发现"的 ViewModel keep 规则** ——
   大多数项目的 proguard 文件是抄来的模板，这份是实证出来的。
3. 全仓库 **0 处 `!!`、0 处 `runBlocking`、0 处 `GlobalScope`**；`Log.*` 全部用 `BuildConfig.DEBUG` 门控
   （`MediaPlayer.kt:375,407,419`）。
4. 文档体系（`AGENTS.md` 24 条硬约束 + `CHANGELOG` 22 个阶段 + `GOTCHAS` 选题极细）——
   连"验证工具自己会骗人"（假 dump、vibrator 只留 50 条）都记了。
5. 缓存清理**逐项对应勾选**（硬约束 13）且 `isClearableTemp` 明确排除 `http_cache` 与 SQLite 锁文件
   （`data/AppCaches.kt:90`），这类"顺手多删一个"的坑被提前挡住了。

## 五、建议的修复顺序

1. **P0-13**（先处理仓库里的真实 `user_token`：换掉 fixture 里的凭证、必要时轮换该游客身份）——
   这一条是"已经泄露在版本库里的东西"，越早越好。
2. **P0-1**（一行改动，直接决定 Android 7.x 能不能用）。
3. **P0-10 / P0-12**（两处都是"用户会立刻感觉到不对"的：应用锁失效、关于页写着被明令禁止的措辞）。
4. **P0-2~P0-6**（换号 Mutex + 身份闸门单飞 + 闸门异常不许吞 + 自愈要 bump epoch / 不许把失败当成功）。
5. **P0-7 / P0-8 / P0-9 / P0-14**（备份安全与数据安全：口令、`vipEnd`、`rawJson` 校验、恢复事务）。
6. **P0-11 / P0-15 / P0-16**（PiP 进入失败、`launchMode`、版本号推进）。
7. **P0-17**（把交互规范执行齐：作者页换 `FollowPill`；把「返回/关闭」上的触感去掉）。
8. **P1**（换号只清 hash 的清凭据、备份含队列、`autoVip` 守卫、空备份拒绝、失败态与重试、
   分页 `try/finally`、图片按用途尺寸解码、`collectAsStateWithLifecycle`）。
9. **P2 + 文档一致性**（lint 先修 P0/P1 → 建 baseline 接进 CI；把附录 C 的"文档与代码不一致清单"逐条改掉；
   补 version catalog 与 schema 导出/迁移测试；CI 增加 `assembleRelease`）。

## 六、本轮未覆盖 / 需实机确认

- 真机（非模拟器）行为：小窗关闭后的音频收尾（`onStop` 兜底）**未实机取证** —— 系统小窗的
  「关闭」是覆盖层，不吃注入点击（`GOTCHAS H5`）。
- `Haptics` 的 `SegmentTick`/`Confirm`/`Reject` 在 API 24–33 上是否真的有效（待真机确认；
  很可能无效，见摘要末注）。
- release APK 的 R8 结果、Room 迁移在设备上的实际行为、备份往返在真实大库上的表现（本轮为静态审查）。


---

# 附录 A · 网络与账号层专项（并行审查，只读）

范围：`net/` 全部 7 个文件 + `data/` 中网络/身份相关文件 + 调用它们的 ViewModel / App / Activity / Manifest。

## A-P0

### A-P0-1 换号没有 single-flight，同一秒内可重复建号
`data/XhsRepository.kt:405,422,453-458,488-491`
`nextSwitchAllowedAtS = nowS`（422）用的 `nowS`（411）与判据用的 `nowS0`（394）同秒，
于是 `if (nowS0 < nextSwitchAllowedAtS)`（405）**恒为 false**，等于没节流；`switchFailStreak` /
`nextSwitchAllowedAtS`（488-491）既非 `@Volatile` 也无锁，而 `switchToVipAccount()` 有 4 个调用入口
（`XhsRepository.kt:372`、`GuestViewModel.kt:107`、`GuestViewModel.kt:134`、`DetailViewModel.kt:83`），
同秒并发都会走到 `freshRandomMac()` + `loginAsDevice()`（423-424）各建一个号。
→ 用 `Mutex` 包住整个函数；只在"决定要换"时设 `nextSwitchAllowedAtS = nowS + wait`；计数器改原子。

### A-P0-2 身份闸门是全局普通布尔：并发时要么一起冲、要么被整体绕过
`net/XhsApi.kt:73,81-90`
`private var insideAccountGate = false` 非 `@Volatile`；N 个协程可同时读到 false 一起执行闸门（踩 A-P0-1）；
反过来 A 置位后、`finally` 复位前，B **完全跳过闸门**（81 行条件为 false）就带着可能过期的账号发请求。
作者注释（67-72）说它只防同协程递归，实现成了全局开关。
→ 改为单飞 + 等待语义（`Mutex.withLock` 或 `AtomicReference<Deferred<Unit>>`）。

### A-P0-3 闸门异常被静默吞掉
`net/XhsApi.kt:85` `runCatching { gate() }`（无日志、无返回值判断）→ 换号失败后请求照发，
用户看到的是空内容/付费墙，而不是错误态。
→ 至少记录并在失败时重试一次或把失败上抛。

### A-P0-4 自愈重登后账号已变，却不通知 UI（epoch 未 bump）
`net/XhsApi.kt:104-113` vs `data/XhsRepository.kt:323-325`
`call()` 里身份失效后 `loginAsGuest()` 重登，但 `noteIdentityChanged()` 只在
`switchGuestTo()`/`switchToVipAccount()` 里调用（`XhsRepository.kt:310,432`）→
`DetailViewModel.kt:57-61`、`GuestViewModel.kt:48-55` 不会重载：详情页继续用**旧账号发放的** media URL
（代码注释本身说明这些 URL 按账号发放），我的页仍显示旧游客 ID。
→ 给 `XhsApi` 加身份变更回调，自愈成功且 token/hash 变化时触发。

### A-P0-5 自愈失败时把"失败响应"当正常结果返回
`net/XhsApi.kt:107,112-113` — `reestablished = true` 无条件设置（即使 `loginAsGuest()` 抛异常），
`getOrDefault(first)` 在第二次也失败时返回第一次的失败响应；上层只读 `data.list`（缺失即渲染空列表）。
→ 只有登录 `result == 1` 才置位，否则抛 `IOException` 让 UI 走错误态。

### A-P0-6 WebDAV 应用密码明文写进备份文件，而备份会离开设备
`data/BackupManager.kt:94-100`（`put("password", cfg.password)`），且与 `:22,:79` 的
"备份不含账号"以及 `net/WebDavClient.kt:17-20` 的"口令故意不放进备份"**三处互相矛盾**。
→ 二选一：不导出密码（恢复时重填），或用 Keystore 封装后再写入；同步改三处注释。

### A-P0-7 恢复备份会写入 `settings.vipEnd`，而导出从不写该字段
`data/BackupManager.kt:183` vs `:82-89` — 该字段只可能来自旧版或被篡改的备份。
写入超大值 → `XhsRepository.kt:367-368,398-399` 永远提前返回，**自动换号彻底停摆**（用户以为"换号坏了"）；
写 0 → 每个请求都多打一次 `v2/mine/user-info`。
→ 删掉这一行；VIP 缓存只允许由 `myProfile()` 写入（`XhsRepository.kt:519`）。

### A-P0-8 备份恢复不校验 `rawJson`，解析点在 ViewModel 里未捕获 → 崩溃
`data/BackupManager.kt:231,250,267` → `XhsRepository.kt:246,253,727` → `ProfileViewModel.kt:43-45`
（`LocalListViewModel.kt:43-48` 同样裸露）。`JSONObject("")` 抛 `JSONException`，而 `viewModelScope.launch`
里未捕获的异常直接终止进程；全仓库无 `CoroutineExceptionHandler`。
→ 恢复时校验可解析且 `noteId > 0`，坏条目跳过并计数；`savedList()/history()/watchLaterList()` 内部兜底。

## A-P1（摘要，均带证据见原文）

| # | 问题 | 位置 |
| --- | --- | --- |
| A-P1-9 | 换号只清 `user_hash` 不清 `user_token` → 存在"新身份 + 旧 token"窗口（服务端只会回 `-1 用戶ID錯誤`）；且没有登出/清凭据路径 | `net/XhsApi.kt:240-242,267-268`、`CredentialStore.kt:19-25,44-50` |
| A-P1-11 | AES/CBC 固定 IV、无 MAC（协议互操作约束，无法自选）；解密失败被降级成"网络错误"并重试，掩盖被篡改 | `net/XhsCrypto.kt:16-17,31-39`、`XhsApi.kt:197-202` |
| A-P1-12 | 4xx 也会被重试（401/403/404/429 原样重发一次，烧配额） | `net/XhsApi.kt:121-134,190-192` |
| A-P1-13 | `usesCleartextTraffic=true` + WebDAV Basic 认证 → http 场景口令 base64 过网 | `AndroidManifest.xml:13`、`WebDavClient.kt:51-56` |
| A-P1-14 | 闸门与 `accountEpoch` 构成回路；`DetailViewModel` 自己调闸门 → "换号成功但二次读失败"时每轮 epoch 多打一轮接口 | `DetailViewModel.kt:57-61,83` |
| A-P1-15 | `MutableStateFlow.update {}` 里做副作用（`emptyPages`、`PagingGuard`）→ CAS 重试会让计数多自增，**分页提前结束** | `DiscoverViewModel.kt:172-176,274-275`、`UserListViewModel.kt:63-71`、`SearchViewModel.kt:176-184` |
| A-P1-16 | `rotateGuest()` 没传 `advanceDevice`，换身份分支无任何调用方 → 与"每次启动更换游客账号"的文档描述不符，参数是死的 | `net/XhsApi.kt:226-243`、`XhsRepository.kt:507`、`GuestViewModel.kt:86` |
| A-P1-17 | `UpdateChecker` 用阻塞 `execute()`，作用域取消后请求与 socket 仍跑到超时（同仓库已有可取消的 `OkHttpAwait.await()`） | `net/UpdateChecker.kt:68` |
| A-P1-18 | 更新页直接 `ACTION_VIEW` 打开 API 返回的 `apkUrl`，无 host/scheme 白名单 | `UpdateScreen.kt:121,158`、`UpdateChecker.kt:91-97`、`OpenUrl.kt:9-12` |
| A-P1-19 | `CredentialStore.deviceId` 的 getter 有写副作用且非原子 → 并发下先返回的身份可能没落盘（孤儿号） | `CredentialStore.kt:35-41,44-50` |
| A-P1-20 | `fullSnapshot()` 内的网络调用位于裸 `appScope.launch`/`viewModelScope.launch`（无 try/catch）→ 一旦新的构造路径出现即崩溃 | `XhsRepository.kt:232,740,259-264`、`NoteActions.kt:60-85`、`DetailViewModel.kt:175-181` |

## A-P2（摘要）

- `XhsApi.kt:143-152` 死参数 `userIdOverride`/`tokenOverride`（注释提到的 account scanner 已不存在）。
- `XhsRepository.kt:38,50,526,533,551,594` `optJSONObject` 可能为 null 却传给非空参数 → 可诊断性退化为 NPE。
- `XhsRepository.kt:87-98` 视频摘要整页并发预取，`perNote` 提前停止发生在全部完成之后。
- `CredentialStore.kt:99` + `XhsApi.kt:167` 基址硬编码、无配置入口（DNS 污染时只能换网络）。
- `MainActivity.kt:57,78` 剪贴板去重状态混在凭据 prefs 里。
- `CredentialStore.kt:94-96`/`XhsApi.kt:287-289`：网络客户端替本地库决定保留条数（`historyLimit` 透传）。
- 过时注释：`XhsDao.kt:32`（"最多 100 条"实为默认 2000 可配）、`App.kt:76-81`（"5s poll"已删除）、`BackupManager.kt:143`、`XhsApi.kt:233-238`（引用不存在的 `DEVICE_POOL`）。
- `UpdateViewModel.kt:41-51` 检查更新页不写 `update_checked_at`，与启动检查互不感知。
- `XhsAsyncImage.kt:108-110` 解密失败把密文回退进解码器，掩盖真实原因。

---

# 附录 B · UI 层专项（并行审查，只读）

对照 `AGENTS.md` 硬约束 16/19/20/21/22/23/24 与 `GOTCHAS` C/D/H 节。

## B-P0（除已进摘要的 P0-11/12/17 外）

- **B-P0-5 取消文件选择器 = 应用锁永久失效**（已进摘要 P0-10）。
- **B-P0-6 画中画进入失败 → 孤儿播放器**（已进摘要 P0-11）。修法：判 `enterPictureInPictureMode`
  返回值，失败就 `closeAndRelease()` 并留在详情页；更稳的是让 `inPip` **只由**
  `onPictureInPictureModeChanged` 驱动（现在 `PipController.start()` 无条件置 true）。

## B-P1

| # | 问题 | 位置 |
| --- | --- | --- |
| B-P1-7 | 失败态被显示成空态且无重试出口（VM 有 `error`，UI 只判 `isEmpty`） | `UserListScreen.kt:87-95` vs `UserListViewModel.kt:74-82` |
| B-P1-8 | 早退分支跳过 `loading = false` → 该 query 下**分页被永久挡住**（`try/finally` 可解） | `SearchViewModel.kt:128,141,153` |
| B-P1-9 | 双击缩放只有 X 轴有动画、Y 轴瞬变 | `FullscreenImageViewer.kt:252-256` |
| B-P1-10 | `currentThemeMode()` 非生命周期收集 + 3 个死局部变量 | `HomeScreen.kt:118-120,335` |
| B-P1-11 | 评论区长列表全量组合（无 Lazy、无 key，可数百条） | `DetailScreen.kt:563-566,803-808` |
| B-P1-12 | 图片解码只有一档 1280 长边：40×56 缩略图与整屏封面同分辨率 | `XhsAsyncImage.kt:128,138-149` |
| B-P1-13 | 排序功能下线后的**残骸**：KDoc 仍写"长按拖动"、死常量/死 import/`setOrder()` 无人调用 | `WatchLaterScreen.kt:39-68`、`WatchLaterViewModel.kt:17-22,42-45` |
| B-P1-14 | 唯一路由注册处出现字面量路由；`Routes.PROFILE`、`HomeTab.route` 无人读取 | `AppNavHost.kt:444`、`Routes.kt:10,14,38-42` |

## B-P2（摘要）

- **漏触感**：`CacheScreen.kt:159-162`（Checkbox 自身无触感、同行有）、`ProfileScreen.kt:194-202,295`、
  `UserListScreen.kt:115-116`、`VideoPlayer.kt:615`（重试）、`AppNavHost.kt:143-190,209-217`（三个确认框与多选栏全无）。
- **死代码**：`Haptics.kt:60` `hapticClickable`（无人调用）、`VideoPlayer.kt:301,628`
  （`rememberExoPlayer`、基于 `media3.ui.PlayerView` 的实现 —— 与 `VideoSurface.kt:20-41`
  "禁用 SurfaceView 系 PlayerView"的结论**矛盾**，容易被再次误用）、`CacheViewModel.kt:58` `setAll`、
  `GuestViewModel.kt:131,142,174`、`Tokens.kt:121`、`DetailScreen.kt:996`；另有多文件重复 import。
- **注释与代码不符**：`XhsWaterfall.kt:155-156`（说 Box 是给 DropdownMenu 定位，实际是原生对话框）、
  `BufferedSlider.kt:73`（KDoc 写"拖动中按比例给换挡"，实现是"按下即触发"）。
- **三份"小标题 + 圆角卡片"实现**：`ProfileScreen.kt:363`、`SettingsScreen.kt:308`、`ListSection.kt:24`。
- 详情页标题 `combinedClickable(onClick = {})`：有波纹无动作，长按复制也没触感（`DetailScreen.kt:678-683`）。
- 路由参数解析失败静默回退 `0` → 被伪装成"内容加载失败"（`AppNavHost.kt:354,435`）。
- `PlaybackHandoff.held` 是强引用，未被认领时不会释放（`PlaybackHandoff.kt:99-116,136-143`）。
- `PipController.start()` 不释放已有会话（`PipController.kt:65-69`，正常路径难触发，标"待确认"）。
- `ImageGallery.kt:127` 分页 lambda 参数**遮蔽**同名入参（共享页号），建议改名。
- 重组期反复 `SimpleDateFormat`（`DetailScreen.kt:955`、`ProfileScreen.kt:164`）。
- `ResetZoomButton.kt:55` 硬编码 `Color.White` 绕过 `Scrim` 令牌。
- 文案不一致：`GuestViewModel.kt:179`「游客ID：」vs `ProfileScreen.kt:156`「游客 ID：」。

---

# 附录 C · 数据 / 构建 / 仓库卫生专项（并行审查，只读）

## C-P0（除已进摘要的 P0-7/13/14/15/16 外）

- **C-P0-2 迁移策略三份文档三种说法，且 destructive 兜底仍在链上**：
  `data/XhsDatabase.kt:60-63` 同时有真迁移与 `fallbackToDestructiveMigration()`；
  `CONTEXT.md:97` 说"当前是 destructive、改 schema 会清空"、`CONVENTIONS.md:19` 把它写成约定，
  而 `ARCHITECTURE.md:142-144`/`AGENTS.md:56` 说"不能靠它兜底"。`git log` 显示首个提交就是
  `version = 2`，所以它兜的 `1 → 3` 跳变**从未存在**。→ 删 fallback，三份文档统一为"必须写真迁移"。
- **C-P0-3 覆盖恢复非原子**（已进摘要 P0-14）。

## C-P1

| # | 问题 | 位置 |
| --- | --- | --- |
| C-P1-4 | **稍后观看队列完全不参与备份**，覆盖恢复也不清它（类注释却说导出"everything the user would miss"） | `BackupManager.kt:102-135` |
| C-P1-5 | 取消文件选择器不复位（=摘要 P0-10） | `BackupScreen.kt:100,115` |
| C-P1-6 | 无 `launchMode` → 深链双实例（=摘要 P0-15） | `AndroidManifest.xml:22-27` |
| C-P1-7 | `versionName` 未推进（=摘要 P0-16） | `app/build.gradle:25` |
| C-P1-8 | 备份内容被漏报：应用内文案与 README 没提"含搜索记录、指纹锁开关、历史上限、**WebDAV 明文凭据**" | `BackupScreen.kt:219`、`README.md:29` |
| C-P1-9 | 恢复 `autoVip` 少了 `if (s.has(...))` 守卫 → 缺键即**静默关掉"VIP 到期自动切换"**（核心机制开关） | `BackupManager.kt:179` |
| C-P1-10 | 空/残缺备份 + 覆盖 = 清空后写 0 条，还返回"成功" | `BackupManager.kt:214-276` |
| C-P1-11 | `GOTCHAS H3` 把"关闭小窗"的收尾写成 `onDestroy`，与硬约束 17 / H12 / 代码相反 | `GOTCHAS.md:279` |
| C-P1-12 | `GOTCHAS H2/H11` 描述的排序实现已被硬约束 20 明确否决，仍在文档里 | `GOTCHAS.md:270-272,390` |
| C-P1-13 | `BUILD.md` 声称 `.gitignore` 覆盖 `*.keystore`，实际规则在 `.gitattributes`（`git check-ignore` 无输出） | `docs/BUILD.md:151-152` |
| C-P1-14 | 4 个 fixture 提交了真实 `user_token`/设备身份/账号历史（=摘要 P0-13） | `tools/fixtures/prefs_*.xml` |

## C-P2（摘要）

- `isClearableTemp` 只挡 `.lck`，挡不住 SQLite 的 `-wal/-shm/-journal`（且该前提本身待确认）。
- `versionCode` 依赖本机时钟与**本地时区**：时钟早于 2026-10-01 会得到负数、跨时区同提交不同号
  → 与"可复现构建"的自我定位冲突（`app/build.gradle:26-29`）。
- `exportSchema = false`：没有 schema JSON、迁移与备份往返**零测试**（全仓库仅 1 个单测）。
- `savedIds()/watchLaterIds()` 为拿一个 ID 集合而 `SELECT *` 全表（含数 KB 的 `rawJson`）。
- `history ORDER BY viewedAt DESC` / `watch_later ORDER BY position` 无索引（当前量级可接受，**不必**加）。
- CI 只跑 `assembleDebug`，release/R8 路径无门禁（而仓库把"release 必须真机跑通"写成硬要求）。
- 无 `libs.versions.toml`，版本号在 5 处手工维护（已出现 `CONTEXT.md` Room 版本落后两版）。
- `gradle.properties:7-8` 注释仍写 "AGP 8.7 predates SDK 36"（实际 AGP 9.4.1 / compileSdk 37）。
- wrapper 未固定 `distributionSha256Sum`。
- 签名口令与 `release.jks` 入库（按 `BUILD.md` 是刻意为之，仅报告）。
- 深链自定义 scheme 无 `autoVerify`/`pathPrefix`（任何应用可注册同名 scheme）。
- Manifest 有一段指向**不存在的 `activity-alias`** 的注释。
- `tools/verify.ps1:50` 硬编码解锁码 `1234`（建议读 `$env:DEVICE_PIN`）。
- 过度吞异常：`AppCaches.kt:53-59`、`XhsRepository.kt:128-133,142-144` 把失败变成"0 B"，不可观测。

## 文档与代码不一致清单（专项C）

- `CONTEXT.md:37,89-95` 说数据库是 **v2 / 三张表**，代码是 `version = 3` + 四张表（含 `watch_later`）。
- `CONTEXT.md:97` / `CONVENTIONS.md:19` 的迁移策略描述与代码（有真迁移）相反。
- `ARCHITECTURE.md:142-144` / `AGENTS.md:56` 说"不能靠 destructive"，代码里 fallback 还在（三份文档打架）。
- `CONTEXT.md:47` 说 WebDAV 用 PROPFIND，代码里没有 PROPFIND。
- `README.md:29` / `BackupScreen.kt:219` 说备份只含四类，实际还含搜索记录与 WebDAV 明文密码。
- `AGENTS.md:62` / `VERIFY.md:99-101` 说锁文件在 `cache/`，`VERIFY.md:108` 自己把库写在 `databases/`。
- `docs/README.md:21` 说 AGENTS 有 **12 条**硬约束（实际 24 条）；`:25` 的 GOTCHAS 索引只到 **G**（实际 A–I）。
- `docs/README.md:39` / `CONVENTIONS.md:39` 要求"文档不含机器专属绝对路径"，而 `BUILD.md:7-9`、`CONTEXT.md:106-108` 写了 `D:\Scoop\...`。
- `BUILD.md:71,94,110-125` 把 md5/体积/versionCode 当"当前值"留档，但仓库**没有任何 git tag**，无法核对。
- `CHANGELOG.md:425` 与 `:439` 关于 RemoteAction 的记载前后相反（历史记录，建议加"已在阶段末修正"标注）。


