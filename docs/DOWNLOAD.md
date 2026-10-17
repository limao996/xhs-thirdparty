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
- [ ] **T4 任务持久化**：`data/DownloadTask.kt`（Room 实体）、DAO、`MIGRATION_3_4`、`download/DownloadRepository.kt`；
  验收：升级安装后旧数据仍在；杀进程重开任务列表还在。
- [ ] **T5 前台服务 + 通知**：`download/DownloadService.kt` + 清单权限；
  验收：下载中按 Home 进度继续；通知可暂停/取消（截图）。
- [ ] **T6 下载管理页**：`ui/screens/DownloadScreen.kt`、`ui/viewmodel/DownloadViewModel.kt`、`navigation/Routes.kt`、
  `ui/screens/ProfileScreen.kt`（入口）；
  验收：UI dump + 截图；暂停/继续/取消/重试/打开/删除逐项实测。
- [ ] **T7 详情页菜单化 + 下载对话框**：`ui/screens/DetailScreen.kt`、`ui/components/DownloadPickerDialog.kt`；
  验收：标题栏只剩 收藏 + ⋮；菜单三项可用；图片多选（默认全选）→ 确认后建任务并跳转（dump + 截图）。
- [ ] **T8 设置「下载」分组**：`ui/screens/SettingsScreen.kt` + prefs（并发任务数 / 并行切片 / 仅 Wi-Fi / 完成通知）；
  验收：改成 1 并发后同时下载数确实为 1；仅 Wi-Fi 打开后在流量下任务自动暂停。
- [ ] **T9 文档与档案**：本文件定稿、`AGENTS.md`（仓库表 + 新硬约束：下载/公共目录/权限）、
  `docs/ai/GOTCHAS.md`（`RESULT_SEEK` 坑、MediaStore 版本差异）、`docs/CHANGELOG.md`、`README.md` 功能列表。

## 7. 风险与对策

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
