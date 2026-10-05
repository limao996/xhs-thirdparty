# AI · 一页上下文

> 给 AI 代理的**最小充分上下文**。先在 3 分钟内读完这一页，需要细节再跳转对应文档。
> 规则与硬约束在 [../../AGENTS.md](../../AGENTS.md)；踩坑规则在 [GOTCHAS.md](GOTCHAS.md)。

## 1. 项目是什么

`com.thirdparty.xhs` —— 小黄书（老司机软件）第三方 Android 客户端，桌面名「小黄书」（debug 变体为「小黄书.debug」，包名带 `.debug`）。一个人手写的、单模块、纯 Kotlin + Compose 工程，
目标是"可维护的现代 Android 样板"：单一数据源、全链路加密、可离线、可备份、可复现构建。

**这个 App 的核心机制是"游客账号轮换"**：服务端给每个**新注册**的设备身份发一段 VIP 体验窗口，客户端在窗口
将尽时用一个新的随机身份重新建号登录，于是 VIP 一直续下去（细节见 [../ARCHITECTURE.md](../ARCHITECTURE.md) §3.1
与 [../PROTOCOL.md](../PROTOCOL.md) §4.1）。不是绕过付费校验，而是自动化地领"新游客福利"。

| 项 | 值 |
| --- | --- |
| 语言 / UI | Kotlin 2.1.21 / Jetpack Compose（BOM 2026.02.01）+ material3 **1.5.0-alpha29** |
| 构建 | AGP 9.4.1 / Gradle 9.8.0 / KSP 2.1.21-2.0.2 / JDK 17 |
| SDK | minSdk 26、targetSdk 37、compileSdk 37 |
| 包名 | release `com.thirdparty.xhs`，debug `com.thirdparty.xhs.debug` |
| 版本 | `versionName 1.3.0`；`versionCode = 当前秒数 − 2026-10-01T00:00:00 的秒数` |
| 模块 | 只有 `:app` 一个模块（`settings.gradle`） |
| XML 布局 | 无。模块只有一个 `AndroidManifest.xml` + 图标资源 |
| 第三方 UI 框架 | 无（不用 appcompat / material / 任何 UI 库） |

## 2. 代码地图（改哪儿找哪儿）

```
app/src/main/java/com/thirdparty/xhs/
├── App.kt                     Application：单例仓库/数据库初始化
├── MainActivity.kt            唯一 Activity：edge-to-edge + 主题 + 应用锁
├── DeepLink.kt                解析 xhstp://note/<id>
├── common/RepoViewModelFactory.kt   所有 ViewModel 的工厂（注入 XhsRepository）
├── data/
│   ├── XhsRepository.kt       ★ 单一数据源：全部接口调用与缓存写入
│   ├── Models.kt / NoteItem.kt / ShareText.kt   DTO 与 UI 模型
│   ├── XhsDatabase.kt         Room 数据库（xhs_local.db，version 2）
│   ├── XhsEntity.kt / XhsDao.kt / FollowDao.kt 实体与 DAO
│   ├── BackupManager.kt       备份/恢复（本地文件 + WebDAV）
│   └── AppCaches.kt           可清理缓存（CacheKind：磁盘图片 / 内存位图 / 临时文件）与 formatBytes
├── net/
│   ├── XhsApi.kt              请求封装、重试、会话自愈
│   ├── XhsCrypto.kt           AES/CBC 包体 + CDN 图片 AES/ECB
│   ├── OkHttpAwait.kt         协程桥接
│   ├── CredentialStore.kt     SharedPreferences 存账号/设备标识
│   ├── IdentityGuess.kt       设备身份生成（四种形式：MAC / IMEI / android_id / 截断 30 位的 android_id）
│   ├── WebDavClient.kt        WebDAV（PROPFIND/PUT/GET/MKCOL）
│   └── ...
├── navigation/AppNavHost.kt + Routes.kt   唯一路由注册处
├── ui/screens/*.kt            页面（Home/DiscoverTab/Detail/VideoFeed/Search/Author/...）
├── ui/components/*.kt         可复用组件（XhsWaterfall/VideoPlayer/ImageGallery/BiometricLock/...）
├── ui/viewmodel/*.kt          状态编排（每个 screen 一个）
└── ui/theme/Theme.kt + Tokens.kt   M3 Expressive 主题、设计令牌
```

关键约定：**UI 层永远不直接调 `net/`**，只能经 `data/XhsRepository.kt`；`ui/viewmodel/` 用
`RepoViewModelFactory` 拿到仓库。

## 3. 数据流（三个方向都要记住）

```
读：Screen ──collectAsState──► ViewModel ──► XhsRepository ──┬─► Room(xhs_local.db)  缓存命中直接返回
                                                             └─► XhsApi(AES/CBC) ──► 服务端
写：用户操作 ──► ViewModel ──► XhsRepository ──► 先写 Room（离线可用）──► 再发请求 ──► 失败不清本地
恢复：Room 启动时为空 ──► 备份文件/WebDAV（xhs/ 子目录）──► BackupManager 回填 Room
```

- 断网/失败语义：**已缓存内容不清空**，只叠加错误态（见 `GOTCHAS.md` 的失败态规则）。
- 账号门（VIP 续期）：所有"需要账号"的请求都会在 `XhsApi.call()` 里先走 `XhsRepository.ensureAccountForRequest()`——
  缓存的 VIP 剩余不足 `VIP_MIN_REMAINING_S = 60L` 秒就注册新游客身份（`v2/app/init` → `login-with-guest` → 校验 `isVip`），
  换号成功会 `bump accountEpoch` 让 UI 刷新。**没有定时轮询**：门只挂在真实请求上，界面空闲时不会发任何建号请求
  （代价是"长时间挂着不动、窗口过期后再点开某个页面"会先触发一次换号）。三个前置短路：`autoSwitchOnVipExpiry == false`、
  `currentUserHash()` 为空（还没建过号，首个账号由启动路径 `GuestViewModel.ensureFreshGuest` 负责）、缓存窗口仍够用 → 直接放行。
  细节见 [../ARCHITECTURE.md](../ARCHITECTURE.md) §3.1。
- 会话失效：`XhsApi.needsReauth()` 检测到身份失效 → 重新登录 guest → 原请求重试一次（`NETWORK_ATTEMPTS = 2`，退避 `RETRY_BACKOFF_MS = 350L`）。

## 4. 协议摘要（细节见 [../PROTOCOL.md](../PROTOCOL.md)）

- `POST https://app.xiaohuangbook.net/v2/{endpoint}`，包体为 **AES/CBC/PKCS5Padding** 加密后的 JSON。
- 密钥 `525202f9149e061d`、IV `985204f4819ec31c`（来自对官方客户端的静态分析，见 PROTOCOL 的合规说明）。
- 请求头需带 `User-Id`、`Client-Type: 1`、`Client-Version: 2.6.0`、`Client-Channel: 1333`、`Accept: application/octet-stream`。
- 包体内含 `s_time`（毫秒时间戳）与 `user_token`。
- `Client-Version` 是**协议版本**（2.6.0），与 App 的 `versionName` 无关，不要一起改。
- 已用接口（`data/XhsRepository.kt`）：`home/discover-note`、`search/note-list`、`search/user-list`、`note/view`、
  `mine/user-info`、`home/discover-category`、`note-comment/comment-list`、`note-comment/comment-reply-list`、
  `member/user-info`、`member/follow-list`、`member/fun-list`、`member/note-list`、`member/fun-group-list`；
  `net/XhsApi.kt` 另有 `user/login-with-guest`、`app/init`。

## 5. 本地数据（Room v2，`xhs_local.db`）

| 实体 | 内容 | 说明 |
| --- | --- | --- |
| `SavedNoteEntity` | 收藏 | 一键清空（二次确认） |
| `HistoryEntity` | 最近浏览 | 上限可配；不记录未观看的视频 |
| `FollowedEntity` | 我关注的作者 | 支持取消关注（二次确认） |

迁移策略当前是 `fallbackToDestructiveMigration()` —— **改 schema 就升级 version，数据会清空**，
所以不能把"必须保留"的数据只放在这里。备份内容：收藏 / 最近浏览 / 关注 / 设置（主题、自动换号开关、历史上限、应用锁）/
搜索记录 / WebDAV 配置（**含 URL、用户名与密码**，JSON 里是明文，注意备份文件本身的存放位置）；
**不含账号凭据**（identity / token / hash / VIP 窗口都不导出——旧窗口恢复回来也没有意义）。

## 6. 环境事实（本机，构建用）

| 项 | 值 |
| --- | --- |
| JAVA_HOME | `D:\Scoop\apps\temurin17-jdk\current` |
| Android SDK | `D:\Scoop\apps\android-clt\current`（adb 在 `platform-tools\adb.exe`） |
| 解压版 Gradle（快） | `%USERPROFILE%\.gradle\wrapper\dists\gradle-9.8.0-bin\*\gradle-9.8.0\bin\gradle.bat` |
| 模拟器 AVD | `xhs_test`（API 34、1080×2400、420dpi），位于 `%USERPROFILE%\.android\avd`，**不在仓库里** |
| 构建产物 | `app/build/outputs/apk/{debug,release}/`（不入库） |
| 最近一次实测 | 2026-10-04：`assembleRelease` 成功（Gradle 9.8.0 / AGP 9.4.1 / JDK 17），产物 `app-release.apk` 3,306,226 B、md5 `6d799ca08d1fab281aef854961c70057`、versionName `1.2.1` / versionCode `326862`；模拟器上装正式包实测通过（冷启动注册游客身份并拿到 VIP、设置 → 清除缓存逐项勾选与清理、关于页显示 `v1.2.1（build 326862）`、检查更新页正常），全程 `crash: 0` |

## 7. 当前状态

- 功能面：浏览（推荐/发现/搜索/详情/评论区/作者页）、本地（收藏/最近浏览/播放器）、应用（主题/应用锁/设置/深链/备份）均已实现。
- 已知边界：不做发评论；付费内容靠**换新游客号**领取新体验窗口获得访问（不是破解校验，见 §1 与 [../ARCHITECTURE.md](../ARCHITECTURE.md) §3.1）；
  `app.xiaohuangbook.net` 在部分网络环境会被 DNS 污染。
- 仓库维护面：AI 档案、CI、Issue/PR 模板、文档随代码同步更新（约定见 [CONVENTIONS.md](CONVENTIONS.md)）。
- 缓存与数据的边界（改"清除缓存"前必读）：可清理的只有三种 —— `cache/http_cache`（OkHttp 磁盘图片与封面，上限 64 MB）、
  进程内位图 LRU（`ui/components/XhsAsyncImage.kt` 的 `BitmapCache`，`maxMemory/8`）、`cache/` 下的其它临时文件。
  收藏 / 最近浏览 / 关注在 Room（`xhs_local.db`）里，是**数据不是缓存**，清理不会动它们；视频不落盘（边看边下，退出即释放）。
  入口：设置 → 数据 → 清除缓存（`ui/screens/CacheScreen.kt` + `ui/viewmodel/CacheViewModel.kt`）。
- 检查更新：`net/UpdateChecker.kt` 查 GitHub `releases/latest`；启动时 `App.checkUpdateOnLaunch()` 自动查一次，
  只有 `Newer` 且未被「跳过这个版本」（`settings.ignored_update_version`）时才弹 `UpdateAvailableDialog`。
  GitHub 匿名额度 60 次/小时/IP，超出是 HTTP 403（页面上显示「检查失败：GitHub 限流」），限流/断网不会弹窗。
