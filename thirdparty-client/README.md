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