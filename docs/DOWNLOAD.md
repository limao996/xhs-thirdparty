# 下载功能（视频存 mp4 / 图片存相册）

> 状态：**设计已确认，实施中**（2026-10-17 起）。设计四项决策由用户拍定：
> ① 后台下载 = 前台服务 + 常驻通知；② 视频要**真 mp4**（切片解密后转封装）；
> ③ 「多线程」= 并发任务 + 单文件并行切片（设置里两个开关）；
> ④ 落盘 = `Movies/小黄书/<标题>_<noteId>.mp4` 与 `Pictures/小黄书/<标题>_<noteId>/N.jpg`。
>
> 实施进度见本文件「任务」一节的勾选，以及 `docs/CHANGELOG.md` 对应阶段。

## 1. 目标与范围

**目标**：在作品详情页把内容下载到系统公共目录 —— 视频给出**可直接播放的单文件 mp4**，
图片进系统相册；下载在后台继续（切后台/锁屏不停），有下载管理页与配置项。

**范围内**：详情页标题栏菜单化、下载对话框（图片多选）、前台服务下载内核、公共目录落盘、
任务持久化、下载管理页、设置项。

**范围外（明确不做）**：BT/磁力、在线播放列表、把 HLS 原样存成文件夹、下载画质选择（源只有一路）、
断点续传到"字节级"（只到切片级）、iOS/桌面端。

## 2. 现状事实（探测所得，勿凭印象推翻）

| 事实 | 证据 |
| --- | --- |
| 视频源 100% 是 HLS，无 mp4 直链 | 采样 5 条 `note_media_url` 全是 `.../p.m3u8`（2026-10-17 探针） |
| 切片是 `localhost.tN.ts`、`video/mp2t`、约 1.3–1.5 MB、`Accept-Ranges: bytes` | 对真实切片发 HEAD |
| 播放列表带 `#EXT-X-KEY:METHOD=AES-128,URI="/static/m3u8/enc.key",IV=0x…`，密钥 16 字节 | 抓 `p.m3u8` 与 `enc.key` |
| 切片 AES-128-CBC 解密后是合法 MPEG-TS（`0x47` 同步、188 包长、PAT/PMT/视频 PID256+音频 PID257） | 解密 6 片合并 8,370,888 字节后逐包校验 |
| 项目已有 `media3-extractor`（exoplayer 传递），缺 `media3-muxer` | `gradle :app:dependencies` |
| 项目无 WorkManager / DataStore；现有 `UpdateDownloader` 是单连接流式下载 | `app/build.gradle` |

## 3. 架构

```
详情页 ⋮ 菜单 ──► 下载对话框（视频 1 项 + 图片多选）
                        │ 确认
                        ▼
              DownloadRepository（Room download_task）
                        │ 入队
                        ▼
   DownloadService（前台服务 + 常驻通知：汇总进度 / 暂停 / 取消）
                        │
        ┌───────────────┴────────────────┐
        ▼                                ▼
  HlsDownloader（视频）             ImageDownloader（图片）
  m3u8 解析 → 切片并行下载          并行下载 → 现有图片解密链路
  → AES-128 解密 → 顺序合并 TS
        ▼
  HlsRemuxer（TS → MP4 纯转封装，media3 TsExtractor + Mp4Muxer）
        ▼
  MediaStoreWriter → Movies/小黄书/… 或 Pictures/小黄书/…/N.jpg
```

**技术栈**：Kotlin + 协程（`Dispatchers.IO`，信号量控并发）、OkHttp（下载，独立客户端）、
`javax.crypto`（AES-128-CBC）、media3（`TsExtractor` / `media3-muxer.Mp4Muxer`）、
Room（任务表）、Android `MediaStore`（公共目录）。

**关键约束（沿用 AGENTS.md）**：纯 Compose、菜单用原生 `material3.AlertDialog`、
自绘按钮都要触感、输出简体中文、Room 改 schema 必须写真迁移。

## 4. 兼容与边界

| 面 | 处理 |
| --- | --- |
| API 26–28 写公共目录 | 需要 `WRITE_EXTERNAL_STORAGE`（运行时申请，`maxSdkVersion="28"`）；29+ 走 MediaStore 无需权限 |
| API 33+ 通知 | `POST_NOTIFICATIONS` 运行时申请；被拒时服务仍可跑，只是通知不可见（要降级提示一次） |
| API 34+ 前台服务 | 需 `FOREGROUND_SERVICE_DATA_SYNC` 类型 |
| 播放链路 | **不改**现有 HLS 播放（ExoPlayer 已能播 AES-128 HLS） |
| Room | 现有 4 张表不动，只加 `download_task` + `MIGRATION_3_4`（真迁移） |
| 签名/发布 | 不动 `keystore/`；发布按 AGENTS.md 硬约束 28（改版本号 → 构建 → 验证 → 才推送发布） |

## 5. 验证策略

- **纯逻辑**：`m3u8` 解析（切片/key/IV）、文件名清理与重名规则 → `app/src/test` 单测（JVM，避开 android 桩）。
- **转封装**：8 MB 量级真实 TS → mp4；用 `Mp4Extractor` 读回轨道与时长；系统播放器实播截图。
- **落盘**：`adb shell content query --uri content://media/external/video/media` 与 `.../images/media`
  证明进公共目录；相册截图。
- **后台**：按 Home 后进度继续（通知截图）；断网/恢复、仅 Wi-Fi、暂停/继续/删除逐项实测。
- **静态**：`assembleDebug` + `testDebugUnitTest` + `lintDebug`（lint 基线 9 条不变）。

**TDD Route**：`off` / `skipped`（用户未要求 TDD；纯逻辑补单测，其余以实机证据为准）。

## 6. 任务

- [x] **T1 转封装跑通（关键风险，先做）—— 2026-10-17 通过**
  文件：`download/HlsRemuxer.kt`。
  **做法（与计划的差异）**：手写驱动 `TsExtractor` + `Mp4Muxer` 在真实 TS 上不收敛
  （extractor 在"跳到尾部"与"回到开头"之间来回请求 `RESULT_SEEK`，mp4 产出 0 字节，见 GOTCHAS H29），
  改用 media3 公开的 **`Transformer`**（新增 `androidx.media3:media3-transformer`，它自带 extractor 驱动与 muxer 编排）。
  **证据（模拟器 API 34 实测）**：8,370,888 B 的 TS → **8,358,452 B** 的 mp4，`ExportResult.durationMs=60040`
  （源为 6×10 s 切片，吻合），耗时 **3.75 s**（体积几乎不变 → 纯转封装、未重编码）；
  拉回本地解析：`ftyp isom` + `moov`（**2 个 `trak`**：视频 + 音频，`stbl` 含 stsd/stts/ctts/stsz/stsc/co64/stss）、
  `mvhd timescale=10000 duration=600800` → **60.08 s**。
- [x] **T2 下载内核（可单测部分优先）—— 2026-10-17 通过**
  文件：`download/HlsPlaylist.kt`（解析，含相对/绝对地址、`#EXT-X-KEY` 的 URI 与 IV、
  媒体序号兜底、`EXT-X-MAP`/`BYTERANGE` 明确报不支持）、`download/DownloadNames.kt`（目录/命名规则）、
  `download/HlsDownloader.kt`（切片并行下载 + AES-128 解密 + 顺序流式合并，Semaphore 控并发）；
  单测：`HlsPlaylistTest` 5 例、`DownloadNamesTest` 6 例，全绿（连原有 `UpdateCheckerTest` 共 22 例通过）。
  **实机证据（模拟器 API 34，12 片 / 22 片）**：
  并发 1 路 **25,982 ms** → 并发 4 路 **10,645 ms**（**提速 2.44×**）；两轮产物完全一致
  （mp4 16,317,809 B、时长 120,040 ms，正好 12×10 s）。
  **临时探针**：`download/DownloadProbe.kt` + `App.onCreate` 的 DEBUG 钩子（T7 做出正式 UI 后删除）。
- [x] **T3 公共目录落盘 —— 2026-10-17 通过**
  文件：`download/MediaStoreWriter.kt`（API 29+ 走 `RELATIVE_PATH`+`IS_PENDING` 无需权限；
  API 26–28 自建目录 + `DATA` 插入并需要运行时 `WRITE_EXTERNAL_STORAGE`，清单里用
  `maxSdkVersion="28"` 限定）、`AndroidManifest.xml`（新增该权限）。
  **实机证据（模拟器 API 34）**：转封装好的 mp4 落盘 → `Movies/小黄书/下载探针_999.mp4`
  （16,317,809 B，`content://media/external_primary/video/media/1000000028`）；
  `content query --uri content://media/external/video/media` 显示
  `_display_name=下载探针_999.mp4  relative_path=Movies/小黄书/  _size=16317809`；
  图片两张落进 `Pictures/小黄书/下载探针_999/01.jpg`、`02.jpg` 并入库；
  `ls /sdcard/Movies/小黄书/` 与 `/sdcard/Pictures/小黄书/` 双向确认存在。
  API 26–28 分支**未实测**（模拟器是 API 34），代码按版本分支写好，待真机/低版本验证。
- [x] **T4 任务持久化（表与迁移部分）—— 2026-10-17 通过**
  文件：`data/DownloadTaskEntity.kt`（一行一个文件：视频 1 行、图片各 1 行，便于多选/并发/逐条重试）、
  `data/DownloadTaskDao.kt`、`XhsDatabase.kt`（version 3→4 + `MIGRATION_3_4` 真迁移 + `downloadTaskDao()`）。
  **实机证据（模拟器 API 34，覆盖安装带旧库）**：升级前 `user_version=3`、`history=17`、无 `download_task`；
  升级后 `user_version=4`、`history=18`（**旧数据保留**，只是本次浏览 +1）、`download_task` 表存在（0 行）、
  我的页正常、`crash: 0`（Room 打开时会校验 schema，能开起来即说明建表语句与实体一致）。
  待办：`DownloadRepository` 与"杀进程重开任务列表还在"的验证放到 T5/T6。
- [x] **T5 前台服务 + 通知（含下载引擎）—— 2026-10-17 通过**
  文件：`download/DownloadService.kt`（前台服务 + 常驻进度通知 + 暂停全部/继续全部动作）、
  `download/DownloadEngine.kt`（队列 / 并发 / 暂停 / 取消 / 重试 / 断点恢复 / 落盘，状态真相在 Room）、
  `download/DownloadSettings.kt`（并发任务数、并行切片、仅 Wi-Fi、完成通知）、
  `download/ImageDownloader.kt`（图片：下载 + **复用 `XhsCrypto.zdecrypt`** 解密 + 落相册）、
  `res/drawable/ic_download.xml`、清单新增 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` /
  `POST_NOTIFICATIONS` 与 `<service … foregroundServiceType="dataSync">`。
  **实机证据（模拟器 API 34）**：`服务已进前台（id=4801 type=1）`；
  `dumpsys activity services` → `isForeground=true types=00000001`；
  `NotificationRecord pkg=com.thirdparty.xhs.debug id=4801 channel=downloads actions=2`；
  暂停 → `暂停全部：1 个在跑的任务` 且任务行转 `PAUSED`；继续 → 回 `RUNNING`；
  **断点续传** → `跳过已下载的 15 片（断点续传）`（暂停在 15/22，继续时复用这 15 片）；
  杀进程重启 → `把 1 个上次中断的任务重新排队` → `恢复未完成任务 1 个` → 继续下载；
  图片 → 详情接口取真实图、解密后落 `Pictures/小黄书/服务验证图片_4242/01.jpg`（781,248 B）；
  视频完成 → `Movies/小黄书/服务验证视频_4242(5).mp4`（29,000,933 B、211,381 ms，与 22×10 s 吻合）；
  **崩溃 0**。
  **本轮修掉的两个真缺陷**：① 服务 `onCreate` 里等状态收集器才 `startForeground`，收集器先看到
  「没活干」就 `stopSelf()`，导致 `ForegroundServiceDidNotStartInTimeException` 崩进程（见 GOTCHAS H30）；
  ② 进程被杀时卡在 `RUNNING` 的任务永远不会被重新排队（新增 `DAO.requeueRunning`）。
  **临时探针**：`download/DownloadEnqueueProbe.kt` + App 钩子 + 服务的 DEBUG `DUMP` 动作（T7 后删除）。
- [x] **T6 下载管理页 —— 2026-10-17 通过**
  文件：`ui/screens/DownloadScreen.kt`、`ui/viewmodel/DownloadViewModel.kt`、
  `navigation/Routes.kt`（新增 `DOWNLOADS`）、`navigation/AppNavHost.kt`、
  `ui/screens/ProfileScreen.kt`（「其他」分组新增入口）、`ui/screens/HomeScreen.kt`（透传）、
  `data/XhsRepository.kt`（新增只读桥 `downloadTasks()`）、`DownloadTaskEntity`（新增 `cover` 列）。
  **实机证据（模拟器 API 34，逐项操作 UI）**：
  入口 → `我的`页有「下载管理」；进页面显示 `进行中 · 2 项` + 两行并发
  `下载中 4/22 片 · 5.2 MB` / `下载中 1/22 片 · 1.2 MB`；
  点「全部暂停」→ 两行变 `已暂停 · 10.3 MB` / `已暂停 · 6.7 MB`；点「全部继续」→ 回到
  `下载中 12/22 片` / `7/22 片`（**续传，不是重下**）；
  点「打开」→ 系统收到 `ACTION_VIEW dat=content://media/… typ=video/*` 并由相册
  （`com.google.android.apps.photos`）打开该视频；
  点「删除记录」→ `已完成 · 2 项` → `1 项`；点「清空已完成」→ 确认弹窗
  `清空已完成？/ 将移除 1 条已完成 / 失败的记录。已保存到相册或视频库的文件不会删除。` → 清空后
  空态 `还没有下载任务`；
  另外两条：故意坏地址的任务显示 `失败：无法连接服务器，请检查网络` 且点「重试」后引擎重新开跑；
  活动行点「取消下载」→ `进行中 · 5 项` → `4 项`（任务与临时目录一起清掉）。
  截图：`tools/out/dl-final2.png`（页面渲染：失败行红色文案 + 重试↻、完成行 打开▶ + 删除🗑）。
  **本轮顺带修的两处文案/一致性**：① 失败原因原先直接透出英文（`Failed to connect to /127.0.0.1:9`），
  违反文案约定 27 → 统一经 `humanError()` 收成「无法连接服务器，请检查网络」等中文；② 图片任务的
  `savedPath` 少了 `Pictures/` 前缀，与视频行格式不一致 → 改为用 `MediaStoreWriter` 返回的完整路径。
  缺口：`cover` 缩略图这一轮没截到（探针取图地址偶发取不到，退回了类型图标），缩略图渲染走的是
  页面既有的 `XhsAsyncImage`，待 T7 的对话框带上真实封面后再看图。
- [x] **T7 详情页菜单化 + 下载对话框 —— 2026-10-17 通过**
  文件：`ui/screens/DetailScreen.kt`（标题栏改为 收藏 + 「⋮」，新增 `DetailMenuDialog`、
  下载对话框接线、确认后建任务并 `onOpenDownloads()` 跳转、API 33+ 通知权限按需申请一次）、
  `ui/components/DownloadPickerDialog.kt`（视频行 + 图片网格多选，默认全选 / 全不选 / 全选，
  确认按钮显示「下载 N 项」）、`navigation/AppNavHost.kt`（详情页传 `onOpenDownloads`）。
  **实机证据（模拟器 API 34，走真实图文作品深链 `xhstp://note/34913`）**：
  标题栏动作只剩 `返回 / 收藏 / 菜单`；点菜单 → `操作 / 分享 / 全屏 / 下载 / 关闭`；
  点下载 → `图片 · 43/43 张`（默认全选）+ `全不选` + `保存位置：视频进 Movies/小黄书/，图片进 Pictures/小黄书/。`
  + `下载 43 项`；
  点「全不选」→ `图片 · 0/43 张` 且按钮变 `请选择内容`（不可确认）；
  点「全选」后取消两张 → `图片 · 41/43 张` + `下载 41 项`；
  点确认 → **自动跳到下载管理页**（标题 `下载管理`，`进行中 · 35 项` + 一排 `排队中`）；
  稍后全部完成：`已完成 · 44 项`，行内路径 `Pictures/小黄书/<标题>_34913/43.jpg` 等
  `Pictures/` 前缀与「标题_noteId」目录都正确；引擎日志 `图片解密后 811088 字节：40.jpg`；
  `content query` 的 MediaStore 里能看到这些图片。
  截图：`tools/out/t7-menu.png`（菜单）、`tools/out/t7-picker.png`（多选对话框）。
  说明：这条作品是图文（43 张图、无视频），所以「视频」行按设计不显示；视频成品为真 mp4 已由
  T5/T6 的实机流程证明（走的是同一个引擎）。
- [x] **T8 设置「下载」分组 —— 2026-10-17 通过**
  文件：`ui/screens/SettingsScreen.kt`（新增「下载」分组四项：同时下载任务数 1–5、
  单个视频并行切片 1/2/4/6/8、仅 Wi-Fi 下载、下载完成通知）、
  `download/DownloadSettings.kt`（新增 `Prefs` 数据类 + `read/write`，四项打成一个值，
  避免设置页参数从 11 涨到 19）、`data/XhsRepository.kt`（`downloadPrefs` / `setDownloadPrefs` 桥）、
  `download/DownloadService.kt`（新增**下载完成通知**：一批收尾时发 id=4802 的通知，
  受「完成通知」开关与通知权限双重约束 —— 之前这个开关是空的，属于自己挖的坑）、
  `download/DownloadEngine.kt`（一批收尾判定 + 网络恢复自动继续 + 排期串行化 + 防重复启动）。
  **实机证据（模拟器 API 34）**：
  设置页出现「下载」分组，文案 `当前 2 个，超出的排队等待` / `当前 4 路；越大越快，也越吃网络与电量`；
  点「同时下载任务数」→ `1 个 | 2 个（当前） | 3 个 | 4 个 | 5 个` → 选 1 个 → 行文案变
  `当前 1 个，超出的排队等待`，且 `shared_prefs/settings.xml` 读回 `download_concurrent_tasks=1`；
  **并发上限实测生效**：5 个任务时只有 1 个 `下载中`、其余 `排队中`，日志只有一条 `开始任务`；
  **仅 Wi-Fi 门禁实测生效**：打开开关（读回 `download_wifi_only=true`）后关掉 Wi-Fi 再入队，
  3 个任务全部落成 `PAUSED` 且原因是 `等待 Wi-Fi（已开启仅 Wi-Fi 下载）`；
  **网络恢复自动继续**：打回 Wi-Fi 后日志 `网络恢复：继续 3 个等待 Wi-Fi 的任务` 并继续下载；
  **完成通知实测生效**：一批结束日志 `一批下载结束：成功 4，失败 1`，
  `dumpsys notification` 里出现 `id=4802 channel=downloads`、`android.title=下载已结束`、
  `android.text=完成 4 项，失败 1 项`；最后把设置还原成默认（读回 `2 / 4 / false / true`）。
  **本轮修掉的三个真缺陷**：① 完成通知开关是空的（见上）；② `schedule()` 并发排期会突破并发上限
  （先用了布尔门，但 `finally` 在早退分支也会执行把标记清掉，等于没锁 → 换成 `Mutex`）；
  ③ 同一任务可能被重复启动（排期 / 网络恢复 / 用户点继续多路径同时到达）→ `startTask` 加"已在跑则跳过"。
  说明：模拟器只有 Wi-Fi 一种上网方式（`Transports: WIFI`、蜂窝无网），所以"仅 Wi-Fi"的隔离验证是
  用**关闭 Wi-Fi**（无可用网络）触发的同一条分支；真机上移动网络下的表现仍未实测。
- [x] **T9 文档与档案 —— 2026-10-17 完成**
  本文件（设计 / 事实 / 任务 / 缺口 / 验收）、`AGENTS.md`（仓库地图新增 `download/` 与 Room v4、
  权限清单更新、硬约束 **30/31/32**、单元测试清单）、`CLAUDE.md`（条目 14/15 + 总数 32）、
  `docs/ai/GOTCHAS.md`（H29–H33）、`docs/CHANGELOG.md`（阶段五十三）、
  `README.md`（功能列表 + **已知限制改写**：原文"不做下载 / 视频不落盘"已不成立）、
  `docs/ARCHITECTURE.md`（Room v4 + 下载子系统 + 新路由）、`docs/PROTOCOL.md`（HLS / AES-128 与落盘事实）、
  `docs/ai/CONTEXT.md`（代码地图与本地数据表）。
  **临时探针已全部删除**：`DownloadProbe.kt`、`DownloadEnqueueProbe.kt`、`DownloadService` 的 DEBUG `DUMP` 动作
  与 `App.onCreate` 的两个钩子；删除后 `assembleDebug + testDebugUnitTest + lintDebug` 全绿（lint 9），
  并重新走了一遍"详情页菜单 → 下载对话框 → 下载管理"的真实路径（见下方验收清单）。

## 7. 已知缺口（如实记录）

- **下载管理顶栏（2026-10-17 用户要求）**：只有返回与「清空已结束」；暂停/继续在**每个作品行**（以及每条作品通知）上，逐件操作。
- **分片内按字节续传（2026-10-17）**：该 CDN 支持 `Accept-Ranges: bytes`，分片断掉后带
  `Range: bytes=<已收>-` 接着拉（206 追加、200 截断重写），密文存在 `NN.ts.part`，拉完才解密成
  `NN.ts`；部分数据留在盘上，任务级重排队也接着传。**自适应降并发**：每次中断把并发降 1
  （下限 1），分片成功后再升回设置值。**半成品字节计入进度**：否则某片拉到 90% 又断时界面数字不动、
  速度显示 0 B/s，看起来就是卡住。
- **网络与账号链路（2026-10-17 用户要求）**：下载用 `XhsRepository.downloadHttp`
  （与主客户端同一连接池/拦截器，超时放宽到 connect 15s / read 30s / **callTimeout 120s**）；
  **下载与观看走同一条 VIP 链路**：每个执行单元开始前 + 每个分片前都过
  `ensureAccountForRequest()`（不足 60 秒的 VIP 换新游客号）。分片失败自动重试 4 次
  （退避 1/3/8 秒）；抓取阶段有看门狗（连续 90 秒没有一片完成 → 「网络不稳定，请重试」）；
  网络类失败**自动重排队**（最多 2 次、间隔 20 秒，复用已下好的分片续传）。
- **一个作品一条通知（2026-10-17 用户要求）**：服务按快照里的每个未结束作品各发一条通知 ——
  标题 = 作品标题、正文 = 它的进度、副标题 = 状态、进度条 = 它的真实比例（视频分片数 / 图片张数，
  `DownloadGroup.progressUnits()`）；每条通知带这件作品自己的「暂停」/「继续」
  （`DownloadEngine.pauseWork/resumeWork`）。前台服务那条固定复用 `NOTIFICATION_ID`，
  其余作品用 `4810` 起的临时 id，作品结束立即收掉。完成通知仍是批量口径。
- **文案口径（2026-10-17 梳理）**：量词统一（`1 个视频、12 张图片`）、单条进度带类型（`视频 5/14 片`）、同一个动作用同一个词（删除 / 取消 / 重试 / 清空）、不重复（保存路径只在选择对话框页脚说一次）、界面不出现内部术语（原「孤儿文件」→「多余文件」）。
- **图集按作品占一个并发名额（2026-10-17 用户要求）**：调度按**执行单元**分配名额 ——
  「一个视频 = 1 个单元」「一个作品的全部图片 = 1 个单元」（`data/DownloadUnits.kt`，带单测）；
  单元内部按「单件作品内并发数」并行（与视频分片同一档）。任务行仍是「一张图一行」（进度/失败/
  断点/去重都需要它），单元只是调度层分组。设置项文案：**同时下载作品数** / **单件作品内并发数**。
- **通知文案口径（2026-10-17 用户要求）**：标题 = `下载中 · <作品>`；正文 = 当前作品的进度行
  （`图片 26/43 张` / `视频 5/14 片 · 1.3 MB/s`，只在处理阶段补 `转换为 mp4`）；
  副标题 = `已完成 6 / 7 件作品`；完成通知按类型数（`完成 1 个视频、20 张图片`）。
  组装逻辑在 `data/DownloadCopy.kt`（纯函数 + 单测）。

- **转 mp4 可能在后台失败（2026-10-17 用户反馈，已修）**：部分视频 media3 会从转封装降级为
  **软件重编码**（实测 115 MB / 163 片走 `c2.android.avc.encoder`，耗时以分钟计）。
  所以：下载期间 `DownloadService` 持 `PARTIAL_WAKE_LOCK`；`HlsRemuxer` 每 5 秒读一次进度、
  **90 秒没前进就判定卡住**并失败（而不是等 10 分钟超时）；失败自动重试一次；
  失败原因按 `ExportException` 错误码翻成中文；「转换为 mp4…」超过 30 秒会补「视频较大，可能需要几分钟」。
- **完成通知的横幅与振动 = 渠道配置（2026-10-17 用户要求）**：`download_done` 渠道**只设**
  `IMPORTANCE_HIGH`（系统定义就是横幅 + 系统提示音 + 系统振动），不写自定义振动模式；
  也不需要 `VIBRATE` 权限。进度渠道 `downloads` 保持低优先级（安静）。
- **通知跳转不重复建页（2026-10-17 用户反馈）**：已在下载管理页时点通知不再 `navigate`，
  否则会压出第二层下载管理。
- **暂停要说明原因（2026-10-17 审查新增）**：「仅 Wi-Fi 下载」挡下的任务会显示
  `已暂停 · 等待 Wi-Fi（仅 Wi-Fi 下载已开启）`，组级汇总显示简称 `已暂停 1 项（等待 Wi-Fi） · 0/1 已完成`。
  原因本来就存在 `download_task.error` 里，只是以前没显示出来。
- **通知点开直达下载管理（2026-10-17 审查新增）**：进度 / 完成通知的 `PendingIntent` 带
  `DownloadService.EXTRA_OPEN_DOWNLOADS`，`MainActivity` 交给 `AppNavHost` 消费一次后 `navigate(Routes.DOWNLOADS)`。
- **取消不能被当成失败（2026-10-17 审查修复）**：写公共目录时若协程被取消，`MediaStoreWriter`
  先删掉刚插入的行、再把 `CancellationException` 重抛；否则任务会被判 `FAILED` 并可能留下
  `IS_PENDING=1` 的隐形行（见 `docs/ai/GOTCHAS.md` H34）。
- **实时速度（2026-10-17 新增）**：下载过程中组行显示 ` · 1.3 MB/s`。速度在**内存**里按字节差采样
  （≥400ms 一次 + 指数滑动平均），不落库（硬约束 30：状态真相只有 `download_task`）；格式化在
  `download/DownloadSpeed.kt`（纯函数 + 单测）。处理阶段仍然只显示已用秒数（那时没有可数进度）。
- **孤儿文件（2026-10-17 新增）**：记录被删、文件还留在相册/视频库里的，也会被列进「检查重复 / 孤儿文件」
  清单。图片按**目录级**判据（整个作品目录都没被引用才算），视频按文件级 —— 详见
  `download/DuplicateCleaner.kt` 里 `orphans()` 的注释（逐文件比对会误删仍在使用、只是被系统改过名的文件）。
- **入口自动提醒（2026-10-17 新增）**：进下载管理页静默扫一次，入口显示「… · 发现 N 个」；
  删除记录后立刻重扫刷新数字。
- **重复文件清理（2026-10-17 新增）**：加去重之前重复下载会在公共目录留下多份同名文件
  （`01.png.jpg` + `01.png (1).jpg`）。下载管理页列表底部「检查重复文件」会扫自己创建的这两个目录，
  按「目录 + 去掉重复后缀的基名」分组、每组保留一份（优先保留仍被记录引用的），
  **先列清单 + 总大小、确认后才删**；只删文件、不动记录。判定逻辑在 `download/DuplicateCleaner.kt`（带单测）。
- **通知渠道（用户 2026-10-17 反馈"完成通知总是不弹"，已修）**：进度通知与完成通知**分渠道** ——
  进度走 `downloads`（`IMPORTANCE_LOW`，不响不弹，避免下载中反复打扰），
  完成走 `download_done`（`IMPORTANCE_HIGH`，弹横幅 + 声音 + 抬到最前）。
  **渠道重要性创建后不可修改**，所以老用户看到的是新建的高优先级渠道。
  完成通知的项数是**本进程这一轮**的完成/失败数（不是库里历史总数）。
- **展开组后隐藏组级「打开」（用户 2026-10-17 要求）**：展开后每条子项都有自己的「打开」，组级那个不再显示。
- **失败项要单独成组、失败原因必须全中文（用户 2026-10-17 截图反馈，已改）**：分组判据原来只有
  `settled`（没有未完成项即结束），失败与成功混在同一个「已完成」列表里 —— 截图里
  `失败 1 项 · 视频` 就挂在 `已完成 · 3 件作品` 下面。现在 `DownloadGroup.failed`
  （结束且有失败）把它分到独立的「失败」分组，**默认展开**、组级文案按失败类型说明
  （`视频 下载失败` / `N 张图片下载失败` / `N 项失败 · …`）。同时分段下载的失败串
  改走 `humanError`（原来直接拼 `e.message`，界面上会出现 `切片 3 下载失败：timeout`），
  现在显示 `切片 2 下载失败，请重试`。
- **重复点下载（用户 2026-10-17 要求，已处理）**：同一个作品的同一条内容（作品 + 类型 + 序号）
  库里**只留一条有效任务** —— 已下载 / 在下载中的跳过，旧的 `FAILED` 行先删再建。
  判定在 `data/DownloadDedupe.kt`（纯函数 + 单测），`DownloadEngine.enqueue()` 返回入队计划供界面提示；
  下载对话框会把已存在的内容标成 `已下载 / 下载中 / 已暂停` 且不可再选，计数只算可下载的。
- **删除 / 清空是否连源文件一起删（用户 2026-10-17 要求，已加）**：删除一条或清空已完成的记录时，
  先弹两个选项 ——「仅删除记录」（文件留在相册 / 视频库）与「删除记录和文件」（经 `ContentResolver`
  删 MediaStore 行，文件一并删除，不可撤销）。**默认落在「仅删除记录」**：删文件不可撤销。
  未完成的条目点垃圾桶仍是直接「取消下载」（那时公共目录里还没有文件）。
- **下载管理页按作品成行（用户 2026-10-17 反馈，已改）**：原来一行一个文件，一个作品的几十张图片会把
  列表刷屏、用户反而找不到作品。现在列表**一行一件作品**（封面 + 标题 + 「已完成 · 3 张图片」这类汇总 +
  组级进度 + 展开箭头），点行/箭头展开才列出这一件的每条（视频、每张图片），每条可单独打开/重试/删除；
  进行中/已完成两个分组也按**作品**划分。聚合逻辑在 `data/DownloadGrouping.kt`（纯函数 + 单测）。
- **图片预览（用户 2026-10-17 反馈，已修）**：图片任务落库时 `savedUri` 曾是 `null`，
  点「打开」只会提示「找不到文件记录」；现在 `ImageDownloader` 带上 MediaStore 的 uri 并由引擎存库，
  点开走 `ACTION_VIEW … image/*` 交给系统相册（实测由 `com.google.android.apps.photos` 打开）。
  **注意**：这条修复不会自动补上"修改前已下载"的旧记录（那些行的 `savedUri` 仍是空），
  需要重新下载一次；本项目该功能尚未随任何正式版发布，所以没有写数据补齐脚本。
- **切片下完后的处理阶段原本没有状态（用户 2026-10-17 反馈，已修）**：切片下完还有
  「合并 TS → 转封装 mp4 → 保存到相册」三段，其中**转封装没有可数进度**（media3 `Transformer`
  只在结束时回调），界面与通知原本一直停在「下载中 22/22 片」，用户会以为卡住了。
  现在 `download_task` 增加 `stage` 列（真迁移 `MIGRATION_4_5`），按
  `FETCHING / MERGING / REMUXING / SAVING` 写库；界面与通知显示「合并中…」「转换为 mp4…」「保存中…」，
  并且处理阶段换成**不确定进度条 + 已用秒数**（`statusLine` 的 `elapsedSeconds`），
  通知文案追加「正在处理，完成后自动保存」。
  实测：视频作品下载中行内出现 `转换为 mp4… · 已用 2 秒`，通知标题 `转换为 mp4 · 我就蹭蹭不进去`；
  该视频只有 6 片，`合并中` 这一段太短（~1 秒）没有截到，其映射由单测覆盖。
- **图片地址只在详情接口里有**：发现页/推荐页的摘要不带 `images` 列表（实测发现页 39 条里
  `images` 全为空，只有 `fetchDetail` 才有），所以下载对话框要列出图片，必须逐条走详情接口取。
  真实实现由 T7 的对话框负责，不猜字段。
- **探针产物会重名**：探针多次运行会在公共目录留下 `xxx(2).mp4`、`01 (1).jpg` 这类文件
  （MediaStore 自动去重），这是探针重复入队造成的，正式流程标题/noteId 来自作品，不会这样。
- **未实测**：API 26–28 的 `WRITE_EXTERNAL_STORAGE` 分支（模拟器是 API 34）、
  通知权限被拒后的降级提示（T7 里做）、仅 Wi-Fi 开关的实际拦截（T8 里做）。

## 8. 风险与对策

| 风险 | 对策 |
| --- | --- |
| TS→MP4 转封装 API 未跑通（当前 T1 失败） | 先只攻这一项；自建驱动不收敛就改用 media3 `Transformer` |
| 长视频单遍流式处理的内存/磁盘 | 只保留"当前切片 + 合并流"，转封装边读边写；临时文件放 `cacheDir` 并在成功后删除 |
| 前台服务被系统限制（省电策略） | 通知常驻 + `startForeground` 不可取消；失败时降级为"仅前台下载"并提示 |
| 大文件公共目录写入失败 | 逐个错误码给具体原因（磁盘不足/无权限/MediaStore 拒绝）并可重试 |
| 通知权限被拒 | 服务照跑，提示一次"下载将不显示进度通知" |

## 8. 继任与清理

- `DownloadSpike.kt`（DEBUG 探针）与 `App.onCreate` 的钩子在 T1 跑通后**删除**；
- 旧路径无（全新增），无需退休项；`UpdateDownloader` 保持独立，不复用（语义不同：那是安装包）。
