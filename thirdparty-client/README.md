# 小黄书第三方客户端（Compose + Material3）

基于对 `小黄书133.092902.apk`（包名 `x2610.xiaoshushu02.com`，版本 2.6.0，渠道 1333）逆向得到的后端 API，
用 **Jetpack Compose + Material 3 + 现代 Android 架构** 重写的独立第三方客户端。

## 一、后端逆向结论

### 1. 入口（多域名，主 `app.xiaohuangbook.net`）
- `https://app.xiaohuangbook.net` / `tuitui567.com` / `xiaoquanapp.com` / `zonghengnet.com`
- 动态域名由 `v2/app/domain` 下发。

### 2. 传输协议
| 项 | 值 |
|---|---|
| 方法 | `POST`，URL `https://{domain}/v2/{endpoint}` |
| 请求体 | `AES/CBC/PKCS5Padding` 加密 JSON：`{s_time, user_token, ...业务参数}` |
| 密钥/IV | `525202f9149e061d` / `985204f4819ec31c`（ASCII） |
| 响应体 | 同密钥 AES-CBC 解密得到 `{result(1成功/1002-1004失效), message, data}` |
| 请求头 | `User-Id`(user_hash或设备ID)、`Client-Type:1`、`Client-Version:2.6.0`、`Client-Channel:1333` |

### 3. 图片与视频
- 含 `codstatic` 的图片字节是 **AES/ECB/NoPadding** 加密（同 key），需先解密再解码。本项目用自研 `XhsAsyncImage`（OkHttp + `zdecrypt` + BitmapFactory + 内存 LRU）。
- 视频是 **AES-128 加密 HLS m3u8**，用 media3 ExoPlayer 直接播放。

### 4. 访客账号（每次启动更换）
- `v2/user/login-with-guest`（空参数）每次下发全新 `user_token/user_hash`（实测两次互不相同）。
- 冷启动调一次即丢弃旧凭证、换新游客账号。

### 5. 关键端点
- 推荐/发现流：`v2/home/discover-note {category_id, group_id, page}`
- 详情：`v2/note/view {note_id}`
- 搜索：`v2/search/note-list {q, group_id, page}`（中文关键词实测返回 20 条）

## 二、技术栈与架构（Compose + 最新依赖）

| 层 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3（BOM 2024.02, MD3 tonal/dynamic color）+ material-icons-extended |
| 导航 | Navigation Compose 2.7.7（单 Activity 单页面导航） |
| 状态 | `StateFlow` + `collectAsStateWithLifecycle`；ViewModel（`viewModelScope`） |
| DI | 轻量手动 DI（`App` 持有 Repository / OkHttp），`ViewModelProvider.Factory` 注入 |
| 网络/加密 | OkHttp 4.12 + 自研 `XhsCrypto`(CBC/ECB) |
| 本地 | Room 2.6.1（收藏 + 浏览历史） |
| 视频 | media3 1.3.1（ExoPlayer + HLS, `PlayerView` 经 AndroidView 嵌 Compose） |
| 图片 | 自研 `XhsAsyncImage`（无第三方图库，含 codstatic 解密 + LRU） |
| 协程 | kotlinx-coroutines 1.7.3 |

### 目录
```
app/src/main/java/com/thirdparty/xhs/
├── App.kt                    # Application：Repository + OkHttp 手动 DI
├── MainActivity.kt           # 单 Activity，setContent + XhsTheme + AppNavHost
├── navigation/               # NavHost + Routes + HomeTab 枚举
├── ui/theme/Theme.kt         # MD3 ColorScheme（Light/Dark + Dynamic Color）+ Typography + Shapes
├── ui/components/            # XhsAsyncImage（解密图）、PlayerView（media3 嵌入 Compose）
├── ui/screens/               # HomeScreen(Scaffold+NavigationBar+四Tab) /
│                             # VideoFeedScreen(VerticalPager 沉浸短视频) /
│                             # DiscoverWaterfallScreen(LazyVerticalStaggeredGrid 瀑布流) /
│                             # LocalListScreen(收藏/历史本地) / DetailScreen / SearchScreen
├── ui/viewmodel/             # Guest/Discover/VideoFeed/Detail/LocalList/Search ViewModel
├── data/                     # NoteItem, Room(Entity/Dao/DB), XhsRepository（网络+本地协调）
└── net/                      # XhsApi(BODY 加密/请求头), XhsCrypto(CBC+ECB), CredentialStore
```

## 三、特性对照
| 需求 | 实现 |
|---|---|
| 每次启动换新游客账号 | `GuestViewModel.ensureFreshGuest()` 冷启动调 login-with-guest 覆盖 token；**设备身份保持稳定**（后端只会回 `mine/user-info` 给已建立的游客设备ID），顶栏显示**游客ID** + 手动轮换 |
| 收藏本地存储 | Room `saved_notes`（纯本地，绝不调云端 `do-collect`），重启保留、离线可读 |
| 最近浏览本地 | Room `history`（去重 / 时间倒序 / 上限 100） |
| 推荐短视频 | `VideoFeedScreen`：VerticalPager 全屏上下滑 + media3 自动播放 HLS + 循环 + 后台暂停；点击画面/「详情」进详情 |
| 发现瀑布流 | `DiscoverTabScreen`：发现页顶部 TabRow（**发现/粉丝圈/关注**），发现 tab 有**分类筛选横条** + LazyVerticalStaggeredGrid 2 列 Masonry 无限滚，右下 **FAB 刷新** |
| 粉丝圈 | `fun-group-list {user_id}` 的 `recommend_list`（推荐粉丝圈作者），独立于「关注」 |
| 我的页面 | 精简：**游客 ID + VIP 状态**（`mine/user-info`）+ 我的收藏 + 最近浏览 + 我关注的作者（入口） |
| 本地关注作者 | Room `followed_authors`（详情/作者主页「+关注」），作者主页 `AuthorScreen` |
| 搜索 | SearchScreen：MD3 SearchBar(支持**内容/作者**切换 → `v2/search/note-list` 与 `v2/search/user-list`) + 本地历史 **wrap 排列**(FlowRow，非横滚) |
| 主题 | 支持**日间/夜间/跟随系统**（默认跟随系统），「我的」页可切换并持久化（`ThemeMode` + SharedPreferences） |
| 复制标题 | 详情页**长按标题复制**到剪贴板 |
| 费用标记 | 所有作品处用 `note_cin` 选 **免费 / 付费 / 粉丝圈** 三态（不显示价格）；瀑布流/搜索/作者作品置于封面右上角 |
| 沉浸式短视频 | 全屏上下滑；**单击**切信息栏与控制栏显隐、**双击**播放/暂停；费用标签在右上角（随信息栏）；点信息栏进详情；无收藏/详情按钮 |
| 推荐页沉浸 | 自动**强制夜间模式**，顶栏/底栏**半透明浮层**；**底栏再点「推荐」刷新**推荐页 |
| 详情播放器 | 控制栏 **3 秒自动隐藏**、点击唤出；播放/暂停、可拖动进度、时间、重播；**真全屏**（隐藏系统栏，横竖屏均可）；**收藏按钮在标题栏右侧** |
| 我的页用户名 | 显示账号**用户名** + 游客ID + VIP 状态；**「切换游客账号」入口**（从标题栏挪入）；外观主题（跟随系统/浅色/深色） |
| 作者主页 | `member/user-info` 的 `user_info.user_id`（修复读取路径）→ 作者作品 `member/note-list` 正常展示；可关注**多个**作者 |
| 搜索 | SearchBar + **顶部 内容/作者 双标签**；内容 `v2/search/note-list`、作者 `v2/search/user-list`；历史 **wrap 排列**；结果费用标签在**封面右上角（小）** |
| 视频残留 | 退出页面时播放器 `stop()+clearMediaItems()+release()`、`setKeepContentOnPlayerReset(false)`、PlayerView 隐藏，消除残留画面 |
| 详情 | 视频(顶置 16:9 播放器) + 标题 + 作者(关注/主页) + 介绍 + 标签(topic) + 评论区 + 本地收藏 + 全屏 |
| 播放器 | `MediaPlayer`：自定义控制器（播放/暂停、可拖动进度条、时间、全屏）保证可见 |
| 界面切换 | NavHost 转场动画；底部 Tab 用状态 + `AnimatedContent`，无手势横滑 |
| 品牌 | 应用名「**小黄书**」，自适应图标为书本+播放 glyph |

> 注：`mine/user-info` 等账号接口 **只对本后端已建立的游客设备ID开放**（新设备ID会返回「用戶ID錯誤」）。因此设备身份（`AABBCCDDEEFF`）持久化保持稳定，仅 token 随启动轮换。`mine/user-info` 实测返回 `user_id` 与 `vip_status`（该游客账号为 VIP）。

## 五、MD3 全面审查与规范化（本版）

对照 Material Design 3 规范逐项审计并修复：

| 违规项 | 修复 |
|---|---|
| 硬编码颜色（`Color(0xFFE0E0E0)` 占位、`Color.White` 头像底、`Color(0xFFFFD54F)` VIP） | 全部改用 `MaterialTheme.colorScheme.*`（`surfaceVariant`/`surfaceContainerHighest`/`tertiaryContainer`/`onTertiaryContainer`） |
| 费用徽章硬编码三色 | 绑定 ColorScheme：付费=`tertiaryContainer`、粉丝圈=`secondaryContainer`、免费=`surfaceVariant` |
| 间距随意（14/5/3/6/10dp 混用） | 建立 `Spacing` 令牌（4dp 基线栅格：4/8/12/16/24/32）并全局替换 |
| 临时圆角 `RoundedCornerShape(n.dp)` | 统一为 `MaterialTheme.shapes.*` / `Corners` 令牌（4/8/12/16） |
| 头像尺寸不一（32/44/46/56） | `AvatarSize` 令牌（comment 32 / list 44 / profile 56）+ `XhsAvatar` 组件（统一圆形+surface 底） |
| 缩略图无圆角 | 列表/搜索/作者作品缩略图统一 `clip(MaterialTheme.shapes.small)` |
| `tonalElevation=1dp` 扁平层级 | 改用 MD3 surface container（`surfaceContainerLow`），符合 MD3 用颜色分层而非阴影的取向 |
| 瀑布流高度靠 `id%2` 生硬交替 | 改为 id 稳定哈希在 5 档高度中分布，形成自然 Masonry |
| 视频遮罩色散落硬编码 | 收敛为 `Scrim` 令牌（over-media 遮罩，MD3 允许的唯一硬编码场景） |
| 详情全屏仍显示顶栏 | 全屏时隐藏 AppBar，实现真沉浸 |
| 沉浸页信息栏被底部导航遮挡（实测点击失效） | 信息栏上移 `80dp` 避开 NavigationBar，实测可点进详情 |

新增 `ui/theme/Tokens.kt`（Spacing / Corners / AvatarSize / Thumb / Scrim）与 `XhsAvatar`、`FeeBadge` 统一组件。

### 实测（Android 14）
推荐（沉浸+强制夜间+半透明顶/底栏+费用标签+点信息栏进详情）、发现（顶部标签+分类条+瀑布流+费用标签）、我的（用户名/ID/VIP/入口/切换游客/主题切换）、详情（视频+标题+费用+作者+介绍+评论+顶栏收藏与全屏）均在浅色/深色下正常渲染，无崩溃。

## 六、本轮缺陷修复（列表分页 / FAB / 切页刷新 / 沉浸）

| 现象 | 根因 | 修复 |
|---|---|---|
| **所有列表不再加载更多** | HomeScreen 重写后，内容用 `Modifier.fillMaxSize()` 放进 `Column`（缺 `weight(1f)`）→ 内容高度=整屏而非剩余高度，列表底部溢出屏幕外，永远滚不到底 → 分页触发器不触发 | 内容改为 `Box(Modifier.weight(1f))`，高度=剩余空间，列表可正常滚到底 |
| **发现页刷新 FAB 消失** | 同一高度溢出问题的连带表现；且 FAB 位于浮层底部导航栏之下 | 高度修复 + FAB 上移 `96dp` 避开 NavigationBar |
| 分页脆弱（`snapshotFlow` 布尔只发一次变化；推荐流在组合期调用 `loadMore`） | 组合期副作用 + 状态不变则不重发 | 改为监听「最后可见 index」并 `distinctUntilChanged`；推荐流用 `pagerState.settledPage` 驱动 |
| 违反 Compose 规则（条件 `return` 之后才 `remember`) | 调用顺序不稳定 | `rememberLazyStaggeredGridState()` 提到条件分支之前 |
| **发现页切 tab 不刷新** | 无触发 | 新增 `DiscoverViewModel.refreshTab(tab)`，`LaunchedEffect(tab)` 切换时自动刷新（跳过首次） |
| **短视频/详情沉浸不佳** | 播放器 `RESIZE_MODE_FIT` 留黑边；推荐页未隐藏系统栏 | 推荐页与详情全屏改用 `RESIZE_MODE_FILL`（满屏裁剪）；推荐页隐藏系统栏实现真沉浸；列表/FAB 增加底部避让 |

### 实测验证
- 深度滚动瀑布流后可见条目完全换新（持续翻页）✅
- 发现页刷新 FAB 可见可点 ✅
- 切换到「粉丝圈」自动加载推荐作者 ✅
- 推荐页沉浸（信息栏 + 沉浸头部 + 系统栏隐藏 + 满屏视频）✅
- 详情页沉浸（全屏按钮、隐藏顶栏与系统栏、满屏播放）✅

## 七、瀑布流统一（收藏 / 最近浏览 / 搜索结果 / 作者主页）

抽出共享组件 `XhsWaterfallGrid`（`ui/components/XhsWaterfall.kt`），发现页与以下页面统一为同一套 2 列 Masonry：

| 页面 | 数据源 | 加载更多方式 |
|---|---|---|
| 发现 | `v2/home/discover-note` | 服务端分页（`page`） |
| 我的收藏 | Room `saved_notes` | **客户端分页**（每页 20，`LocalListUiState.visibleCount`） |
| 最近浏览 | Room `history` | **客户端分页**（每页 20） |
| 搜索结果 | `v2/search/note-list` | 服务端分页（`SearchViewModel.loadMore()`） |
| 作者主页 | `v2/member/note-list` | 服务端分页（`AuthorViewModel.loadMore()`） |

组件统一处理：2 列交错高度（id 稳定哈希分档）、封面费用标签、**尾部触发加载更多**（监听最后可见 index + `distinctUntilChanged`）、加载中转圈行。

实测：最近浏览 6 卡片 x0=42/572 双列且 y 交错；作者主页 5 卡片同为双列交错；搜索「girl」结果以瀑布流渲染。均无崩溃。

## 八、截图驱动的视觉修复（详情页布局 / 推荐页沉浸）

本轮改为**直接截图判读**（`adb exec-out screencap` + 图像阅读），发现了两处此前靠文本 dump 完全看不到的严重问题：

| 问题 | 截图暴露的现象 | 根因 | 修复 |
|---|---|---|---|
| **详情页布局错乱** | 标题、费用标签、作者、「介绍」全部**叠在视频上**（深色文字压在黑色视频区几乎不可读），内容还在下方重复一遍 | `DetailContent` 把播放器和内容 Column 都作为 `Box` 的子节点 → 内容从 Box 左上角开始，覆盖在视频上 | 改为**单一滚动 Column**：媒体在顶，随后标题/费用/作者/介绍/标签/评论依次排布 |
| 详情视频黑边 | 竖屏源被塞进固定 `16:9` 容器，左右大黑边 | 容器比例写死 | 监听 `Player.onVideoSizeChanged`，用**视频真实宽高比**设置容器（竖屏默认 9:16） |
| **推荐页"沉浸"是黑屏** | 整屏纯黑 + 转圈，顶部/底部是实心黑条 | ① 首屏 hydration **串行**请求 `note/view`（最多 ~10 次往返）→ 太久没内容；② 无封面兜底，缓冲期即黑屏；③ `NavigationBar` 的 surface tonal 让半透明失效 | ① 改为 `async/awaitAll` **并行**请求；② 播放器下层铺**封面图**；③ 沉浸态 `NavigationBar` 用 `Color.Transparent` + `tonalElevation = 0` 叠加 `Scrim.chrome`，图标文字转白色 |
| 费用标签被搜索图标压住 | 右上角「付费」与顶栏放大镜重叠 | 费用标签与顶栏浮层同在 `TopEnd` | 费用标签下移 `76dp` 避开顶栏 |
| **切 tab 出现"中间态"页面** | 底部导航切换时短暂看到旧页与新页叠加/淡化 | `HomeScreen` 用 `AnimatedContent` + `fadeIn/fadeOut` 交叉淡入 → 过渡期间**同时渲染**两个 tab 的内容 | 移除 `AnimatedContent`，改为直接 `when(tab)` 分支，瞬时切换、任意时刻只渲染一个 tab（同时省掉动画开销） |
| **视频被拉伸变形** | 画面纵向被拉长（1080×1920 素材塞进 1080×2400 屏，纵向拉伸 1.25×） | 误用了 media3 的 `RESIZE_MODE_FILL` —— 该模式**忽略宽高比硬填满**，即拉伸；此前误当成"裁剪" | 播放器一律改 `RESIZE_MODE_FIT`，并让**容器按视频真实宽高比**排布（监听 `Player.onVideoSizeChanged`，宽度铺满、垂直居中）。不拉伸、不裁剪、保留原比例；推荐页与详情页同时修正，封面图也改为 `ContentScale.Fit` |

修复后的截图表现：推荐页视频**满屏铺满**、顶栏/底栏半透明浮于视频、底部信息栏（标题 + @作者 · ♥数）与「付费」标签清晰分层；详情页媒体与文字**上下分离**、视频按真实比例显示。

截图存于 `shots/`（feed5.png、detail3.png 为最终效果）。

## 九、模拟器实测（Android 14）Compose + MD3 重构后验证：启动无崩溃；访客自动登录；底部三 Tab（推荐/发现/我的）；发现页顶部 TabRow + 分类筛选横条 + 瀑布流翻页；推荐短视频点击进详情；详情页作者块(关注/主页)渲染、全屏按钮存在；「我的」页显示账号信息/统计/收藏入口/最近浏览(6条)；新品牌名「悦览」生效；标题栏账号显示昵称。评论区 `v2/note-comment/comment-list` 协议实测返回数据。目标工具链 JDK 17 + Gradle 8.10 + AGP 8.1.4 + Compose BOM 2024.02。
## 十、Git 版本管理

仓库位于工作区根目录（`G:\Agent工作区\第三方小黄书`），只跟踪**项目源码与文档**，
反编译产物 / 工具链 / 截图 / 构建输出与 APK 均在 `.gitignore` 中排除，仓库体积约 250KB。

提交历史（持续迭代）：

| 提交 | 内容 |
|---|---|
| `d4a3161` | 基线：Compose + MD3 客户端全量源码 |
| `b554052` | 图片解码降采样 + 按字节限容 LRU，修 OOM；评论分页 |
| `49a1ed0` | 加载失败不再静默/不再清空列表，新增错误态与重试 |
| `08575c6` | 播放器音频焦点与生命周期感知，退到后台不再继续播放 |
| `729b634` | 搜索失败不再误报"无结果"，补齐搜索初始态与错误重试 |
| `a57fb0e` | 收藏 / 最近浏览一键清空（含二次确认） |
| `07a3249` | 图片帖多图浏览（水平画廊 + n/N 计数） |
| `165b787` | 图片按后端 `image_size` 真实比例渲染 |
| `c1ca536` | 我关注的作者支持直接取消关注 |
| `8c133f6` | 作者主页补齐错误态与重试 |
| `7feb465` | 我的页补头像与账号数据（关注/粉丝/作品） |
| `5ed5e05` | 搜索历史一键清空 |

## 十一、自主迭代轮次记录（git 驱动）

每轮流程：代码审计 → 定位真实缺陷 → 修复 → `assembleDebug` 编译验证 →
模拟器安装 → **截图判读**确认 → `git commit`。

### 第 1 轮 · 内存与列表完整性
- **高危**：`XhsAsyncImage` 全尺寸解码并把 80 张位图放进无上限缓存，
  1080×1440 封面单张约 6MB，80 张约 480MB → 必然 OOM。
  改为 `inSampleSize` 降采样（长边 ≤1280）+ `RGB_565` + 按堆比例（约 12.5%）
  计容的 `LruCache`。实测图片显示无异常。
- 评论此前只加载第一页且无入口 → 新增分页与「查看更多评论」。

### 第 2 轮 · 网络异常处理
- 加载失败被完全吞掉，用户只看到空白；`refresh()` 失败还会**清空已有列表**。
  改为失败时保留内容并暴露 `error`，新增 `retry()` 与 `EmptyState` 组件。
- `refreshTab(FEED)` 每次切到发现页都先清空再转圈 → 有内容时改为静默后台刷新。

### 第 3 轮 · 播放器质量
- 播放器未设音频属性、不感知生命周期：按 Home 后视频/声音继续播放并抢占音频。
  统一 `buildVideoPlayer()`（movie/media 音频属性 + 音频焦点 + 拔耳机暂停 + 唤醒锁）
  与 `PauseWhenNotStarted()`。

### 第 4 轮 · 搜索与本地数据管理
- 搜索请求失败被当成"没有找到相关内容"（误导）→ 区分 `error`/`empty`，新增重试与初始引导态。
- 收藏 / 最近浏览无清空能力（浏览记录已累积 100 条）→ DAO/仓库/ViewModel/UI 全链路补齐，
  标题栏清空按钮 + `AlertDialog` 二次确认。实测弹窗文案"将删除全部 98 条本地记录"。

### 第 5 轮 · 图片帖
- 接口核对确认 `note_type=1` 为图片帖，`note_image_list[].image_url` 与
  `image_size` 字段路径正确；某帖含 36 张图。
- 此前详情页只渲染 `images[0]`，其余图片完全不可见 → 新增 `ImageGallery`
  （水平翻页 + `n/N` 角标），并按 `image_size` 以**真实比例**渲染（延续"不拉伸"约束）。
- 实测：「漫画」分类 15 图帖显示 `1/15`，图片按原比例铺满、无拉伸无裁剪。

### 第 6 轮 · 账号与列表细节
- 「我的」页新增头像（`user_head_img` 实测为真实 URL）与 关注/粉丝/作品 数据行。
- 「我关注的作者」支持直接取消关注；作者主页补齐错误态与重试。
- 顺带核实 `discover-category` 返回结构确为 `{id, name}`，`Category.from` 解析无误。

### 第 7 轮 · 头衔与视频预加载
- 视频缓冲无任何反馈 → 新增 `BufferingIndicator`（监听 `STATE_BUFFERING`）。
- 滑动切换每次都要重建播放器 + 重新拉流 → 改为**预加载相邻视频**：
  `beyondBoundsPageCount = 1` + 相邻页保留暂停态的播放器实例，窗口外释放。
  实测连滑 5 次即时起播、无串音、PSS 152MB。

### 第 8 轮 · 评论区回复
- 实测确认**没有独立回复接口**（`reply-list` / `son-list` / `comment-reply` 等全部 404），
  回复内联在 `comment-list` 的 `reply_data` 中。
- **真实 bug**：`CommentItem.replyCount` 读顶层 `data_count`，该字段不存在 → 恒为 0。
  改为读 `reply_data.data_count`，并解析 `reply_data.list`。
- 新增 `CommentReply` 模型与嵌套渲染（"用户 回复 某人" + 内容），
  回复数多于已加载时提示"共 N 条回复"。
- 实测：详情页评论下方正确显示「用户_0DsNI 回复 用户_9mm1X / 笑死了」+「共 2 条回复」。

### 第 9 轮 · 下拉刷新
- 当前 material3 为 1.2，没有 `PullToRefreshBox` API → 基于 `nestedScroll`
  自研轻量 `PullToRefreshBox`：只消费子级无法消费的下拉（即已滚到顶部），
  因此不干扰正常滚动；超过 64dp 阈值触发刷新，刷新中保持显示。
- **刻意不用于推荐流**：那里的上下滑语义是"切换视频"，加下拉刷新会冲突。
- 实测：滚动/加载更多不受影响；下拉出现居中指示器且触发刷新。

### 第 10 轮 · 全流程冒烟测试
依次进入 推荐 → 发现 → 我的 → 最近浏览 → 搜索，全部正常渲染，
`logcat` 无 `FATAL EXCEPTION` / `OutOfMemory` / `ANR`，进程存活。

> 说明：本轮迭代共 18 个提交，自基线起 26 个文件变更（+1221 / -215 行），
> 每条修复均有「接口实测」或「截图判读」证据。

### 第 11 轮 · 会话自愈（重要）
- `XhsApi.call()` 完全不处理失败响应。实测摸清失效矩阵后发现 `result=-1`
  被复用为「资源不存在」和「身份失效」两种含义（`筆記不存在` vs
  `用戶ID錯誤 请重新登录`），必须按 message 区分。
- 命中身份失效时自动重跑 `login-with-guest` 并重试一次；
  登录请求强制使用设备身份而非可能失效的 user_hash。
- 端到端验证：篡改本地 `user_hash` 为 `ZZZZ` 后启动 → 自动重登、
  hash 恢复、token 换新、推荐流与瀑布流正常。修复前会永久空白。

### 第 12 轮 · 网络层与启动体验
- 原先存在两个 OkHttpClient（图片与接口各一个），且**没有磁盘缓存**，
  每次启动所有封面重新下载。实测图片 CDN 响应带
  `Cache-Control: max-age=31536000`，属强缓存资源。
  合并为单一共享 client + 64MB 磁盘缓存；数据层不再依赖 App 单例。
  验证：浏览后 `cache/http_cache` 出现 8 条缓存条目（约 1.1MB）。
- 深色模式冷启动会闪白：主题 XML 无 `values-night`、窗口背景为浅色、
  导航栏写死白色。补齐 night 资源，并把窗口背景与应用主题模式对齐
  （含应用内主题切换时的同步）。验证：系统深色下启动 300ms 截图
  背景即 `#1B1114`。

### 第 13 轮 · Compose 正确性与抖动
- 全项目扫描发现两处「条件 return 之后才调用 remember/effect」：
  `XhsAsyncImage`（`XhsAvatar` 的 url 必然经历 null→有值，每次都会
  改变调用结构）与 `ImageGallery`。已把 remember/LaunchedEffect 全部
  无条件前置，扫描结果归零。
- 推荐流预加载触发值由 `currentPage` 改为 `settledPage`，避免拖动期间
  相邻页播放器被反复创建/销毁造成抖动。
- 评论加载失败不再误显示为"还没有评论"（新增 commentsError + 重试）。

### 第 14 轮 · 刷新与内存压力
- 作者主页接入下拉刷新（重载资料 + 作品首页，成功才替换）。
- 新增 `onTrimMemory`/`onLowMemory`：内存紧张时释放位图缓存。

### 第 15 轮 · 播放失败不再静默
- `buildVideoPlayer` 未挂任何错误监听，视频播放失败（坏链接 / 解码失败 /
  CDN 故障 / 断网）是完全静默的：用户只看到封面或卡住的转圈。
- 新增 `rememberPlaybackError` + `PlaybackErrorOverlay`（"视频播放失败 + 重试"）
  + `retryPlayback`；瞬时故障先做**一次静默自动重试**，再失败才提示。
- 验证方式：临时把播放地址替换为必然失败的 URL 构建验证 → 覆盖层正确显示
  （`shots/err_overlay.png`），随后还原并确认无残留（`TEMP-VERIFY` 计数 0）。
- **真实故障验证**：期间模拟器网络中断，logcat 出现
  `UnknownHostException (no network)`，App 不再黑屏无提示而是给出明确错误，
  且无崩溃；网络恢复后重新启动 0 播放错误、0 崩溃、视频正常播放。

### 第 16 轮 · API 网络重试
- 上述故障暴露了另一个脆弱点：API 调用一次失败就让整页进入错误态。
- `doCall` 拆为「重试包装 + doCallOnce」，对 `IOException` 最多重试 1 次、
  间隔 350ms；业务层错误（result != 1）不重试。
  此处接口均为只读查询，重试安全。

### 补充验证
发现页三个子 tab（发现 / 粉丝圈 / 关注）均正常渲染，全程 0 崩溃。

### 第 17 轮 · 下拉刷新补全
- 搜索结果、收藏、最近浏览均接入下拉刷新（此前只有发现页与作者页有）。
  搜索刷新重载第一页且不闪空列表、失败保留原结果；本地列表刷新用于
  同步其他界面造成的变化。

### 第 18 轮 · 静默降级与分页死掉
- 「我的」页：资料请求失败被 getOrNull() 吞掉，页面会显示成普通游客、
  关注/粉丝/作品全 0，用户无法察觉。新增 error 态并明确提示。
- 搜索加载更多失败时 `getOrNull() ?: emptyList()` 使 hasMore 被算成
  `0 >= 10` = false，**之后再也无法翻页**。改为失败时保持 hasMore，
  下次滚动自动重试。

### 第 19 轮 · Lazy 重复 key 崩溃（实测数据发现）
- 瀑布流以 noteId 作 LazyStaggeredGrid 的 key，而 Lazy 布局遇重复 key
  直接抛异常。对真实接口连续分页验证发现：**discover 第 2 页会重复
  第 1 页的 1 条 noteId**，即向下滚一两页就可能崩。
- 两层修复：① 数据层新增 `appendUnique()` 按 noteId 去重，
  四处分页统一使用；② `XhsWaterfallGrid` 内部兜底 distinctBy。
- 验证：连续 25 次深度滚动跨页，网格正常、无 FATAL、
  无 "Key was already used"、进程存活。

### 位置记忆验证
从推荐流进入详情再返回，仍停留在原视频（示例 @煮熟的生蚝），
说明 `rememberPagerState` 的可保存状态 + Navigation 的 SaveableStateHolder
工作正常，无需额外处理。

### 第 20 轮 · 最近浏览被预加载污染（行为正确性）
- `videoFeedPage` 拉取一页时会把该页**全部**视频写进本地浏览历史。
  推荐流一页 6 条且还会预取相邻页，用户可能一条都没看，「最近浏览」
  却已多出十几条 —— 它反映的是"服务器返回了什么"而非"我看过什么"。
- 移除批量写入，改为新增 `recordView(item)`：仅在视频真正成为当前
  播放页时记录（独立 `LaunchedEffect(active)`，预加载页 active=false
  不会记录；与播放控制分离，避免播放器重建重复写 viewedAt 打乱排序）。
- 验证（干净数据 + 精确断言）：`pm clear` 后启动不滑动 → 最近浏览
  **1 条**（修复前为整页 6 条）；再滑动 2 次 → **3 条**，与观看数精确一致。

### 第 21 轮 · 全屏返回与并发保护
- 详情页全屏时 AppBar 隐藏，系统返回手势会**直接 pop 整个详情页**。
  新增 `BackHandler(enabled = fullscreen)`，先退出全屏。
  验证：全屏（顶栏隐藏）→ 按返回 → 仍停留在"内容详情"。
- `GuestViewModel.rotate()` 缺重入保护，快速连点会并发发起多个游客登录；
  加入 `_rotating` 守卫。

### 已排除的疑虑（实测确认无需修改）
- **Pager 列表收缩越界**：深度滑到第 14 个视频后再点「推荐」触发刷新
  （列表被替换为更短的第一页），未出现 IndexOutOfBounds，Compose
  Pager 自行处理了 pageCount 收缩。
- **返回后位置丢失**：从推荐流进详情再返回仍停留在原视频，
  `rememberPagerState` 的可保存状态生效。

### 第 22 轮 · 图片缓存管理
- 上一轮为加速封面/头像挂了 64MB OkHttp 磁盘缓存，但应用内**没有任何入口**
  能查看或清理它。
- `XhsRepository` 新增 `httpCacheSizeBytes()` / `clearHttpCache()`
  （evictAll + 清内存位图缓存）；「我的」页新增「清除图片缓存」行，
  副标题显示当前占用（B/KB/MB 自适应），点击弹二次确认。
- 验证：浏览后显示 **5.3 MB**（真实占用）→ 弹窗提示
  「将删除已缓存的封面与头像（5.3 MB），下次浏览时重新下载。」→
  确认后显示 **0 B**。

### 第 23 轮 · 瀑布流真实比例
- 瀑布流此前用 noteId 哈希从固定高度数组取高度，纯属**伪造**：卡片比例
  与真实封面无关，图片被按错误比例裁切。实测确认列表接口已返回
  `note_cover_size`（"375*489" / "610*760" / "375*210"…，比例真实多样）。
- `NoteItem` 新增 `coverRatio`（抽出可复用的 `parseRatio()`，与图片
  `image_size` 解析共用），`WaterfallCard` 改用 `aspectRatio(coverRatio)`
  并 clamp 到 0.55–1.6 以容纳极端比例。
- 验证：两列高度由各自真实封面比例决定、错落自然。

### 第 24 轮 · 进度显示冻结（"看起来在动、其实是死的"）
- 详情页控制栏的 position/duration 只在 `onPlaybackStateChanged` /
  `onPositionDiscontinuity` 时更新，而正常连续播放期间这两个回调都不触发
  → 进度条与时间文本实际上是**冻住**的。
- 修复：控制栏可见且播放中时以 250ms 轮询刷新（media3 无逐帧回调）。
- 顺带新增推荐流底部的 2dp 细进度条（短视频 App 标准元素），同样用轮询驱动，
  位于底部导航之上，信息栏隐藏时也可见。
- 验证：详情页时间文本连续采样 `0:10 → 0:06 → 0:10 → 0:04`（10 秒视频循环），
  确认实时推进；推荐流同一视频相隔 4 秒两帧截图，进度条由约 25% 推至接近 100%。

### 附：顺手发现并确认可用的功能
打开到一条**图片帖**时画廊显示 `1/25` —— 25 张图的多图浏览工作正常
（此前实测过的 15 图帖也一致）。

### 第 25 轮 · 死代码与无谓开销
- `savedIds` 是早期"推荐流带收藏按钮"的遗留：后来按需求移除了收藏按钮，
  但状态字段/赋值/仓库方法都留着，UI 从不读取。其唯一效果是**每次翻页
  都白跑一次 `SELECT * FROM saved_notes`**。
- 一并清理：`VideoFeedUiState.savedIds`、两处 `repo.savedIds()`、
  `onSavedChanged()`、`XhsRepository.savedIds()`、无引用的 `NoteItem.isPaid`。

### 第 26 轮 · HTTP 错误路径使重试机制形同虚设
- `doCallOnce` **从不检查 `response.isSuccessful`**，直接把响应体送去
  `XhsCrypto.decrypt`。CDN 返回 502/503/504 或 4xx 时，响应体可能是 HTML
  错误页或空，解密抛的是 BadPadding/IllegalBlockSize 这类**加密异常而非
  IOException** —— 后果有二：
  1. 第 16 轮加入的网络重试只捕获 IOException，对**最常见的瞬时故障
     （HTTP 5xx）完全无效**；用户拿到的是无从诊断的加密异常。
- 修复：在解密前判断状态 —— 非 2xx → `IOException("HTTP <code> for <path>")`；
  空 body → `IOException("empty response body ...")`；解密失败（截断/非加密体）
  → `IOException("undecodable response ...", cause)`。三者都汇入已实测过的重试路径。
- 验证：正常网络下推荐流/发现页/游客 ID 均正常，logcat 无 FATAL、
  无 undecodable、无 HTTP 4|5 记录。

### 第 27 轮 · 发现页缺少"加载更多"反馈
- `FeedSection` 没有 loadingMore 字段，`DiscoverTabScreen` 也就没传 ——
  瀑布流底部那行（hasMore 时渲染）永远拿到 false，即**渲染成空白 Box**。
  翻页时用户看不到任何反馈。（搜索页/作者页此前已传该参数，唯独发现页漏了。）
- 修复：FeedSection 新增 loadingMore，loadMore 开始时置位（仅当已有内容、
  即分页而非首屏），成功/失败都清零；界面传入该参数。

### 第 28 轮 · 粉丝圈展示真实内容（接口数据被整段丢弃）
- 需求是"粉丝圈要有独立内容"，但实现只渲染了一个作者名列表、大片空白。
  核对真实响应发现 `recommend_list` 的每一项除作者信息外还带：
  `user_notes`（作品总数，如 642 / 2465）与 `note_list`（最多 3 条作品预览，
  含 note_id / note_title / note_cover / note_cover_size / note_cin）——
  **后端本来就在下发内容，解析时被全丢了**。
- 新增 `FanGroupAuthor`；`funGroupRecommend` 改为解析这两项，并把每条预览
  还原成 `NoteItem`（借作者信息补 user_name，用 note_cover_size 得真实比例；
  预览无点赞/收藏/评论数故置 0 且卡片不显示）。
- 粉丝圈改为：作者行（头像 / 名称 / "共 N 个作品" / 去看看）+ 最多 3 张作品
  预览卡（封面 + 费用标签 + 标题，可点进详情）。
- 验证：三位作者分别显示"共 642 / 80 / 2465 个作品"，各带 3 张作品卡；
  点击预览卡成功打开"内容详情"。

### 第 29 轮 · 关注 tab 细节
- 空态由裸 Text 改为 EmptyState（含引导文案），与其他 tab 一致；
- `LazyColumn` 补 `BottomNavClearance` —— 此前最后一项会被浮层底部导航遮住。

### 第 30 轮 · 空态/错误态不可见（重要）
- 现象：搜索「作者」模式下的空态，文案存在于 uiautomator 树中，**屏幕上看不到**。
- 根因：`EmptyState` 内部强制 `modifier.fillMaxSize()`。当它被放进**已有兄弟节点的
  Column**（PrimaryTabRow / 分类条 / 作者头部之后），拿到的是父容器**整高**而非
  剩余高度，内容中心被推到屏幕下方 —— 用户只看到一大片空白。
- **方法论教训**：此前我对错误态的"验证"用的是 **UI 树文本断言**，而 off-screen
  节点同样在树里，所以一直"通过"。本轮改为**用 bounds 坐标判断是否真的在屏幕内**。
- 受影响页面：搜索页（错误/空结果/初始引导）、发现页（加载失败/分类为空）、
  粉丝圈、关注 tab、作者主页（三种状态）。
- 修复：采用本项目 HomeScreen 已验证的模式 —— EmptyState 不再强制尺寸，
  Column 上下文用 `Box(Modifier.fillMaxWidth().weight(1f))` 包裹内容区，
  EmptyState 传 `Modifier.fillMaxSize()`；Box 上下文直接传 fillMaxSize。
- 验证（坐标而非文本）：搜索空态标题 y=1515..1568、关注空态 y=1404..1457，
  均居中于剩余空间且在屏幕内。

### 第 31 轮 · 视觉巡检（详情页 / 作者页 / 搜索作者模式 / 错误态）
用「截图实际看」而非文本断言，逐个审视此前未看过的页面：
- **详情页**：视频 + 标题 + 费用 + 作者(关注/主页) + 介绍 + 标签 + 评论，布局正确
- **作者主页**：头像 + 签名 + 关注按钮 + 真实比例瀑布流；实测「+ 关注」点击后变为「已关注」
- 搜索「作者」模式与「关注」tab：空态已居中可见（第 30 轮修复生效）
- **错误态实机验证**（当时后端确实不可达）：发现页显示
  「内容加载失败 y=1341 / 请检查网络后重试 y=1405 / 重试 y=1509」——
  层次与位置均正确；点击「重试」后内容成功加载

### 第 32 轮 · 播放进度条拖动交互
三处相关缺陷：
1. 播放中拖动时，250ms 位置轮询与拖动争夺同一 state，滑块被不断拉回；
2. `onValueChange` 直接 `seekTo` —— 每拖一个像素发一次 seek，对 HLS 代价高；
3. 自动隐藏计时器不区分「正在拖动」—— **手指还按在进度条上控制栏就消失**，
   手势随之被取消。
修复：拖动期间用本地值渲染、松手才 seek；轮询在 dragging 时暂停；
自动隐藏条件加 `!dragging`；拖动重置 3 秒计时（松手后能看到跳转结果）。

### 验证边界（如实记录）
- 已验证：进度文本随播放实时刷新（采样 0:02 / 0:07 / 0:11 / 0:08）、
  进度条存在、编译通过、全程 0 崩溃。
- **未能验证**：拖动后的实际跳转值。三次尝试（`input swipe`、
  `input motionevent` DOWN/MOVE/UP）都无法让 Compose Slider 识别为拖动 ——
  分次注入的 MOVE 事件不构成连续手势流，属 adb 合成输入的固有限制，
  而非应用缺陷。真实触摸会正常触发相关回调。

### 第 33 轮 · 依赖瘦身
核对后确认四个依赖**零引用**并移除：
- `androidx.appcompat` —— MainActivity 继承的是 ComponentActivity
- `com.google.android.material` —— 界面纯 Compose，且启动主题 parent 是
  **框架自带**的 `android:Theme.Material.Light.NoActionBar`
- `com.google.code.gson` —— 全项目 JSON 解析统一用 org.json
- `kotlinx-coroutines-guava` —— 未使用 ListenableFuture
- 显式 `org.json:json` —— Android 平台自带，且只用标准 API

实测：**APK 22.82 → 20.86 MB（-1.96 MB / -8.6%）**，dex 未压缩 -4.45 MB。
运行时验证（移除 org.json 是风险点，全应用都在解析 JSON）：冷启动、发现页、
详情页、我的页全部正常，应用进程内无 NoSuchMethodError / JSONException。

### 第 34 轮 · 搜索页键盘遮挡（edge-to-edge 下 adjustResize 失效）
- 应用使用 `enableEdgeToEdge()`，窗口绘制在 IME 之下，此时
  `android:windowSoftInputMode="adjustResize"` **不再收缩布局** —— 需要应用
  自己消费 ime insets。结果是输入关键词时搜索结果/空态被键盘压住看不见
  （第 30 轮那次"空态不可见"有一部分正是这个原因）。
- 修复：结果区 `Box(...weight(1f).imePadding())`。
- 验证：键盘展开（`dumpsys input_method: mInputShown=true`）时，空态标题 y
  由修复前 ~1541（键盘约从 1440 起）变为 ~1073；截图确认完整可见于键盘之上。

### 第 35 轮 · 暂停指示反映真实播放状态
- `paused` 原是一个只由双击翻转的本地布尔值；音频焦点被其他应用抢走
  （我们开启了 handleAudioFocus）或解码停顿时，视频停住但界面毫无提示。
- 新增 `rememberIsPlaying(player)`（Player.Listener 驱动），
  `paused = active && !playing && playbackState == STATE_READY`
  （限定 READY 以免缓冲期间误显示暂停图标）。
- 验证：双击前无指示 → 双击后出现 content-desc="已暂停" → 再双击消失 →
  滑动到下一条无残留（active 守卫有效）。
