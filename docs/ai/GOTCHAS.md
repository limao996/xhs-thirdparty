# AI · 踩坑规则（GOTCHAS）

> 每条都是"在本项目里真实踩过、且会重复踩"的坑，写成**触发条件 → 正确做法**的形式。
> 命中触发条件时不要重新推理，直接照正确做法执行。

## A. 改代码与验证的流程

**A1 · 改源码**
- 触发：要修改任何 `.kt` / `.gradle` / `.py` / `.ps1` 文件。
- 正确做法：用 `edit` / `write` 工具落盘。**不要**用 PowerShell 拼字符串（`Set-Content`、`-replace` 链）写源码
  会静默产生乱码或截断，且 `-notmatch` 之类的守卫可能失效。改完**回读文件**确认内容真的变了。

**A2 · 声明"已修复"**
- 触发：想写"已修复 / 已验证 / 应该好了"。
- 正确做法：先给出可复现证据（编译输出行、`DumpUi` 文本、截图、日志）。没有证据就写「未验证」。
  **断言本身也会说谎**：验证失败时先怀疑断言，再怀疑实现。

**A3 · 先复现**
- 触发：用户报缺陷。
- 正确做法：先在真机/模拟器复现同一现象，再动代码；一次只改一个关注点，改完再复现同一路径。
  另外：**构建必须在编辑落盘之后启动**，否则测的是旧代码。

**A4 · 更便宜的假设优先**
- 触发：现象离奇（例如"Activity class does not exist"）。
- 正确做法：先查最便宜的：设备是否**锁屏**（锁屏会伪装成上述报错）、进程是否被 `force-stop`、APK 是否真的是新装的那个。
  只有这些都排除后才怀疑代码。

**A5 · 测量会干扰被测对象**
- 触发：用 `uiautomator dump` 做 UI 断言。
- 正确做法：`dump` 本身耗时 2–3.5 秒且可能崩溃并**静默产生空文本**。做时序相关断言时，把它当成影响因素的
  一部分（留足等待、校验 dump 非空、必要时重复两次取一致结果）。

**A6 · 轮询要按绝对时间**
- 触发：等待某状态出现。
- 正确做法：用绝对时间戳比较或带 deadline 的循环，不要写 `sleep(N)` 后假设状态已就绪。

**A7 · 内容断言先排除屏幕 chrome**
- 触发：用 UI dump 判断"列表滚到哪儿了 / 内容变没变"。
- 正确做法：先按文本与 y 坐标滤掉常驻 chrome（底部 tab、子 tab 标签、分类 chip、`去看看`、`刷新`、`共 N 个作品`），
  只拿剩下的**内容项**做对比。只取"前 N 个文本节点"会把 chrome 当内容，产生**假通过**
  （实测：`发现`/`粉丝圈`/`关注` 三个标签 + 三个分类 chip 正好占满前 6 个节点，于是"切换前后一致=True"毫无意义）。
- 另外：对比"滚动前首项"与"滚动后首项"，相同就说明手势没生效，先别下结论。

## B. 依赖与构建

**B1 · 版本不是"越新越好"，有些是钉死的**
- `material3` 必须是 `1.5.0-alpha29`：降到 BOM 自带的版本会**编译失败**（expressive 主题 API 在低版本是 internal）。
- `fragment-ktx` 必须 ≥ `1.8.9`：`1.2.5` 会让 biometric 抛 `Can only use lower 16 bits for requestCode`。
- `CLIENT_VERSION`（buildConfigField）= `2.6.0` 是**协议版本**，不要跟着 App 版本改。

**B2 · 不要相信本地缓存里的版本号**
- 触发：想知道"实际用的是哪个依赖版本"。
- 正确做法：看编译输出/`./gradlew :app:dependencies`，或直接读 `app/build.gradle`。缓存目录里的名字不代表生效版本。

**B3 · 别自己"近似"官方组件**
- 触发：想快速复刻一个 Material 组件（如进度条）的外观。
- 正确做法：直接搬官方源码再改（本项目缓冲进度条就是这么做的），自己画一个近似版本会在细节（内边距、动画、对比度）上永远对不齐。

**B4 · 构建命令**
- 触发：要编译。
- 正确做法：`gradlew.bat assembleDebug`；想快就用已解压的 Gradle
  `%USERPROFILE%\.gradle\wrapper\dists\gradle-9.8.0-bin\*\gradle-9.8.0\bin\gradle.bat`（wrapper 每次多花约 120 秒校验）。
- 仓库路径含中文依赖 `android.overridePathCheck=true`（已开启），别删。

**B5 · 装了不等于装上了**
- 触发：`adb install -r` 之后要验证新包生效。
- 正确做法：`adb shell am force-stop <pkg>` → 安装 → 比对设备上的 `adb shell pm path <pkg>` 与产物 md5 →
  再 `am start`。只看"安装成功"不算验证。

## C. Compose 与 UI

**C1 · `LaunchedEffect` 每次进入组合都会跑**
- 触发：把"只应发生一次"的逻辑（记录、上报、自动刷新）放在 `LaunchedEffect` 里。
- 正确做法：确认 key 与重组/返回栈行为；需要跨返回保留的状态用 `rememberSaveable` / `rememberSaveableStateHolder`。

**C2 · `when` 分支不是状态容器**
- 触发：在 `when` 的不同分支里各自 `remember` 页面状态。
- 正确做法：分支切换会丢弃另一个分支的组合状态，用 `rememberSaveableStateHolder`（见 C8）或提升状态。

**C3 · 修饰符顺序决定绘制与命中范围**
- 触发：`background` / `padding` / `clip` / `clickable` 组合出非预期外观或点击区域。
- 正确做法：按"越靠前越外层"逐层心算一次；点击区域只与它之前的 `size`/`padding` 有关。

**C4 · 可点击子元素会吞掉父手势**
- 触发：父容器用 `clickable` + 子元素也可点（如卡片里的按钮、进度条）。
- 正确做法：明确谁负责手势；需要同时响应时用 `combinedClickable` 或把子元素区域的点击显式透传。

**C5 · "改了主题" ≠ "UI 变了"**
- 触发：声称完成了一次视觉改造。
- 正确做法：截图或 dump 对比。改的是主题变量但实际渲染没变化，是本项目反复出现的假完成。

**C6 · 空态/错误态可能渲染到屏幕外**
- 触发：写 `Column` 里的空态或错误态。
- 正确做法：确认它在可见视口内（滚动容器里尤其要看），别只看代码分支存在。

**C7 · 控件没被点过就等于没验证**
- 触发：新加了一个按钮/开关/入口。
- 正确做法：真的点一次（或 `TapText`），并观察结果状态变化；只确认控件存在不算。

**C8 · 列表滚动位置：三种成因，一个正确写法**
- 触发：列表/瀑布流在"切走再回来""跳转返回"或"切换分类"后的位置不对。
- 期望一：子 tab / 列表在**切走再回来、跳转详情返回**后要**保留**位置。两个前提：
  1. 分支内容有状态容器：`rememberSaveableStateHolder().SaveableStateProvider(key)`
     （`HomeScreen` 的底部 tab、`DiscoverTabScreen` 的子 tab 都这么做，key 用稳定字符串如 `tab.name`）。
  2. 列表自己的滚动状态可保存，且**不要**再用 `LaunchedEffect(resetKey) { scrollToItem(0) }` 去重置它：
     "跳过第一次运行"的 flag 是错的（被重新激活时 flag 仍为 true，恢复好的位置会被这次运行推回顶部）。
- 正确写法（2026-10-04 第二次修正后的最终形态，`ui/components/XhsWaterfall.kt`，`FanGroupTab` 同理）：
  **把滚动状态按 `resetKey` 分组**：换 key 得到新状态（顶部），同 key 恢复（保留位置）：
  ```kotlin
  val gridState = key(resetKey) { rememberLazyStaggeredGridState() }
  ```
- 期望二：`resetKey` 变了必须**真的**回到顶部（刷新、切分类）。注意"变了"要能被 key 区分出来：
  `HorizontalPager` 的**每一页是独立的 saveable 作用域**（lazy 布局按页 key 存取状态），所以
  "推荐 → 最新 → 推荐"回到同一个分类 id 时，旧偏移会被恢复进刚重新拉取的列表 → 用户落在"从没看过顶部"的列表中间。
  **凡"同一个名字可能代表一份新列表"，身份位就必须单调递增**：本项目用 `DiscoverUiState.feedEpoch`
  （只在 `selectCategory` 里 +1），`resetKey = state.refreshTick to state.feedEpoch`。
  另外不要复用 `refreshTick` 做分类切换：它一变，粉丝圈列表的位置也会被重置。
- 症状对照：滚 → 切子 tab / 进详情返回 → 归零 = 缺"期望一"；滚 → 滑到别的分类再滑回来 → 落在列表中间 = 缺"期望二"。
- 验证方式：滚动 → 切子 tab / 进详情或作者页 → 返回，比对**首项文本与 y 坐标**是否一致（`docs/VERIFY.md` §4）；
  分类切换看**首卡是否完整可见**（内容文本会随重新拉取而变，断言不可靠，用截图判定）。

## D. 数据、播放器与资源生命周期

**D1 · 失败态不能显示成空态**
- 触发：请求失败。
- 正确做法：保留已有数据，叠加错误态并提供重试；"还没有评论"和"评论加载失败"必须可区分。

**D2 · 预加载会污染"最近浏览"**
- 触发：把预取到的对象也写进历史/统计。
- 正确做法：只在用户真正进入/开始播放时写入。

**D3 · 共享资源必须复查所有释放钩子**
- 触发：把 `ExoPlayer`、`OkHttpClient`、图片缓存等在多个界面/多个 ViewModel 间共享（本项目推荐页→详情页是"播放交接"同一实例）。
- 正确做法：逐个检查 `dispose` / `close` / `release` / `onCleared` 是否会在持有者离开时误释放别人的资源；
  交接路径要明确"谁最后负责释放"。

**D4 · 进度/缓冲显示要与真实状态一致**
- 触发：显示进度条、缓冲百分比、暂停图标。
- 正确做法：从播放器状态派生，不要在拖动中让轮询覆盖用户输入；暂停图标必须反映真实播放状态。

**D5 · 环境干扰项**
- 触发：设备验证结果不稳定。
- 正确做法：先排除已知干扰：`app.xiaohuangbook.net` 在部分网络会被 DNS 污染（解析到 `199.59.148.89`）；
  剪贴板里留有分享口令会弹出「检测到分享内容」挡住界面（测试前先点「取消」）；
  `uiautomator dump` 自身可能崩（见 A5）。

**D6 · 测试数据注入**
- 触发：需要构造本地数据做界面验证。
- 正确做法：直接改设备上的 `/data/data/<pkg>/databases/xhs_local.db`（`saved_notes` / `history`），
  `noteId >= 900001` 作为测试段；验证结束清理。

**D7 · 探针必须收尾**
- 触发：为调试临时加代码或临时改环境。
- 正确做法：验证完成后删除探针并 `grep -ri 'probe'` 复查；临时环境改动要还原（否则会成为下一个"诡异现象"）。

**D8 · 一个缓存一个勾选框：清磁盘不得顺手清内存**
- 触发：改「清除缓存」页或 `AppCaches.clear()` / `XhsRepository.clearHttpCache()`。
- 症状（实测过）：只勾「图片与封面缓存」（63.9 MB）并确认，清完「图片内存缓存」也跟着变成 0 B
  确认框写的是"清除选中的 2 项"，实际清了 3 项，界面与用户勾选不符。
- 原因：`clearHttpCache()` 原实现是 `evictAll()` + `clearImageMemoryCache()` 两件事。
- 正确做法：`XhsRepository.clearHttpCache()` 只 evict 磁盘；内存位图缓存只在勾了 `CacheKind.IMAGE_MEMORY` 时由
  `XhsAsyncImage.clearImageMemoryCache()` 清。验证方式：只勾磁盘 → 清完磁盘为 0 B、内存仍非 0（见 `docs/VERIFY.md`）。

## E. 文档与仓库

**E1 · 不把非项目内容搬进仓库**
- 触发：想提交 APK、截图、反编译/解包产物、`local.properties`、构建缓存。
- 正确做法：都不提交。需要时本机再生成（例如图标由 `tools/probes/gen_icon.py` 生成）。

**E2 · AI 档案与项目文档分离**
- 触发：更新规则/地图/坑。
- 正确做法：改 `AGENTS.md` 与 `docs/ai/*`；不要把人读的叙事性交接文档塞进 AI 档案，也不要照搬旧仓库的档案文本。

**E3 · 路径不能写死机器**
- 触发：写脚本或文档里的路径。
- 正确做法：脚本用 `$PSScriptRoot` / `Path(__file__).resolve().parents[n]`；外部工具走环境变量。

**E4 · 不要"顺手纠正"账号轮换机制**
- 触发：看到 `app/init` + `login-with-guest` + VIP 校验这套链路，觉得"多此一举"或"看着像作弊"就想删/改。
- 正确做法：那是本项目**唯一的核心功能**（换新游客号续 VIP），删掉它 App 就只会显示付费墙。要动先读
  `AGENTS.md` 第 1 节与 `docs/ARCHITECTURE.md` §3.1。

**E5 · 文档声明必须与实现一致**
- 触发：想写"本工具不破解付费 / 不绕过访问控制"这类免责话术。
- 正确做法：本项目的机制就是**自动注册新游客身份、领取服务端发给新游客的 VIP 窗口**，照实描述；
  与之相反的免责声明属于事实错误（AGENTS.md 硬约束 12）。

## F. 游客账号与 VIP 续期

**F1 · `v2/app/init` 不能省**
- 触发：新增"换账号 / 登录"路径，或重构 `XhsApi.loginAsGuest()`。
- 症状：新生成的身份一律返回 `result = -1 用戶ID錯誤`，看起来像"服务端不允许创建账号"。实测先跑 `app/init` 8/8 成功、不跑 0/4。
- 正确做法：任何"用新身份登录"的路径都必须 **先 `app/init`，再 `login-with-guest`**（`loginAsDevice()` 里已经这么做了）。

**F2 · 身份必须持久化，`user_hash` 必须清**
- 触发：写 `CredentialStore.deviceId` 之类的 getter。
- 症状：每次读都返回一个新的随机身份 → `app/init` 与随后的登录用了不同身份 → 每次启动都建一个一次性账号、VIP 状态永远对不上。
- 正确做法：`deviceId` 首次生成即 `apply()` 落盘；换号时同时清 `user_hash`（`getUserId()` 优先用它，残留会把会话钉在旧账号上）与 `vipEnd` 缓存。

**F3 · 身份长度是硬约束**
- 触发：自己拼一个身份或改 `IdentityGuess`。
- 正确做法：只用四种合法形式且长度精确：`<12hex>889X`、`<15digits>X`、`<16hex>I`、`<30hex>AI`；`AI` 形式 30 字符可建号、32 字符不行（官方先截断再拼后缀）。

**F4 · 一次只建一个新身份，且必须退避**
- 触发：觉得"多试几个总能碰到有 VIP 的"，于是在一次检查里循环建号。
- 症状：一次检查就产生一堆孤儿账号；后端不再发 VIP 时会把号刷爆（历史上的 `VIP_SWITCH_ATTEMPTS` 就是这么写的）。
- 正确做法：`switchToVipAccount()` 保持"一次一个新身份"，失败走 `switchFailStreak` 指数退避（60s 起、上限 30 分钟）。

**F5 · 换号发生在请求内部，UI 必须跟着 epoch 走**
- 触发：在请求路径里改账号，或新增显示账号信息的界面。
- 症状：「游客ID」停在旧账号、VIP 标记与实际账号不一致。
- 正确做法：换号时调 `noteIdentityChanged()` 触发 `accountEpoch`，界面订阅它刷新；不要在 UI 里自己缓存账号串。

**F6 · 判定 VIP 前先确认新号真的带 VIP**
- 触发：写"切到有 VIP 的账号"的逻辑。
- 正确做法：切完必须读 `myProfile()`（`v2/mine/user-info`）验证 `isVip`，不能假设"新号一定有 VIP"（`Models.kt`：`vipStatus >= 1 || vipEnd > now`）。

**F7 · 离线不换号**
- 触发：网络异常时仍走账号门。
- 正确做法：`hasNetwork()` 要求 `NET_CAPABILITY_VALIDATED`（半连的 Wi-Fi 会挂住请求）；无网直接放弃切换，让请求自己报错。

## G. 检查更新（`net/UpdateChecker.kt`）

**G1 · 检查更新必须用独立的 OkHttpClient，并且必须带 User-Agent**
- 触发：新增任何直接访问公网 JSON 接口的功能（检查更新走 `api.github.com`）。
- 症状：结果永远不变（"已是最新"或者"没有发布版本"卡住不刷新）；或者请求直接 403。
- 原因：`App.httpClient` 带着 64 MB 磁盘缓存（本来是给图片 CDN 的 `max-age=31536000` 用的，见 `App.kt`），
  GitHub 的应答会被缓存住；而 GitHub 对没有 `User-Agent` 的请求直接 403。
- 正确做法：`UpdateChecker` 自己建一个不带缓存的 client，并设 `User-Agent: xhs-thirdparty/<VERSION_NAME>`。

**G6 · 检查更新的主路径是 `releases.atom`，不是 api.github.com**
- 触发：用匿名 REST API（`api.github.com/repos/.../releases/latest`）做"进应用就查一次"。
- 症状：用户"明明没怎么查过"却被限流（HTTP 403），匿名额度是**每 IP 每小时 60 次**，而且和这台机器上
  其它工具共享（CI、其它脚本都算在一起）。
- 正确做法：先取 **`https://github.com/<owner>/<repo>/releases.atom`**（GitHub 发布页自己的 feed，
  **不计 API 额度、不需要 token**），一次就带回 tag / 页面地址 / 更新说明；下载地址按仓库的资产命名约定
  `xhs-thirdparty-<version>-release.apk` 拼出来（拼完仍要过 `isTrustedDownloadUrl`）。只有 feed 失败
  才回落到 API，那时才可能 403。
- 解析要点：`<link rel="alternate" href=".../releases/tag/vX.Y.Z">` 取 tag 最稳（`<title>` 是"小黄书 vX.Y.Z"
  这种给人看的文案）；`<content>` 是 **HTML 转义**过的，要还原实体再去标签（`htmlToText`）。
- 配套：既然不吃额度，就**每次进前台都查**（`App.onActivityStarted` 的 0→1）；只留两道保护
  重复触发去重 3s、失败退避 5min。测试时**别反复手点检查**（那才是真的瞎折腾 GitHub）。

**G2 · JVM 单元测试里 Android 自带的 `org.json` 是空壳**
- 触发：给解析 JSON 的代码写本地单元测试（`app/src/test`）。
- 症状：`java.lang.RuntimeException: Method optString in org.json.JSONObject not mocked.`
- 正确做法：`testImplementation 'org.json:json:20260814'`（只影响测试 classpath，不改应用的运行期行为）。

**G3 · debug 包的 `VERSION_NAME` 带 `-debug` 后缀**
- 触发：写版本比较（检查更新）或把版本号显示给用户。
- 正确处理：`UpdateChecker.numbers()` 会先截掉 `-`/`+` 之后的部分，所以 `1.2.0-debug` 与 `1.2.0` 相等；
  比较逻辑不要自己去 `split('-')`，复用它，否则 debug 包会永远报"有新版本"。

**G4 · 匿名 GitHub API 只有 60 次/小时/IP，超了是 403**
- 触发：反复冷启动验证「自动检查更新」，或同一出口 IP 下多台机器在测。
- 症状（实测过）：检查更新页显示「检查失败：GitHub 限流（HTTP 403），过一会儿再试」，
  重启多少次都不弹更新弹窗：因为根本没查成功，不是弹窗逻辑坏了。
- 正确做法：先查配额再下结论：`Invoke-RestMethod https://api.github.com/rate_limit` 看
  `resources.core.remaining` 与 `reset`（本地是匿名额度；`gh` 已登录走 5000 次/小时的另一个额度，不会替你省额度）。
  等 `reset` 过后再复测；应用侧则必须把 403 映射成 `Failed` 并静默降级（硬约束 14）。

**G5 · `releases/latest` 不含 prerelease / draft**
- 触发：为了验证"发现新版本"临时发一个版本号很高的 release。
- 症状：发了 `v9.9.9 --prerelease` 后应用依旧报"已是最新版本"。
- 原因：`GET /repos/{owner}/{repo}/releases/latest` 只返回最新的**正式**（非 prerelease、非 draft）release。
- 正确做法：临时验证版本要发成正式 release（`prerelease=false`、`draft=false`），验证完立刻
  `gh release delete <tag> --yes --cleanup-tag`，并清掉设备上 `shared_prefs/settings.xml` 里的
  `ignored_update_version`（否则那台设备永远不会再弹这个版本号）。

## H. 稍后观看、长按菜单与画中画

**H1 · 作品长按菜单用对话框，不用 `DropdownMenu`**
- 触发：给瀑布流卡片加长按菜单。
- 正确做法：`ui/components/NoteActionDialog.kt`（`AlertDialog`：标题是作品名，正文是动作行）。
  下拉面板以卡片为锚点，而瀑布流卡片只有半屏宽、菜单会被边缘裁掉；推荐页更是整屏视频，压根没有锚点。
- 这是用户明确提过的要求（阶段十反馈），见硬约束 16。

**H2 · 队列排序：三段折腾的结论是"别用拖动"**（本节描述的"序号 + 上移/下移按钮"实现**已删除**：硬约束 20 的最终结论是**不提供排序**、按加入时间排列；保留本节只为记录那三种交互为什么都被否掉）
- 走过的三版：①"每过半行换位"（只能挪一格，原因见下）；②内容坐标 + translationY 让位（实机仍不稳）；
  ③再加边缘自动滚动与双指补偿（用户仍然反馈不灵）。
- 根因（第①版）：换位会让这一行的**基准位置**立刻跳一行，而手势位移是在节点局部坐标里累加的，
  基准一跳就抵消掉累加量。
- ~~**当时的最终形态**：每行左侧序号 + 右侧竖直排列的小号上移/下移按钮……~~ **已废弃**：用户后来要求彻底不排序
  （硬约束 20），序号与按钮全部删除，下面这段只用来记录"为什么按钮方案也不行"。~~
  立刻写回数据库（`committed` 本地顺序避免写库往返期间的闪一下）。不再做任何拖动排序。
- 结论：列表排序这类需求，按钮的确定性 > 拖动的"手感"；先把交互做对，再谈手感。

**H3 · 画中画：播放器所有权只有一份，交接必须登记**
- 触发：详情页把 `ExoPlayer` 交给 PiP 小窗（`PipController.start`）后退出详情页。
- 症状：小窗里黑屏（播放器被详情页的 `onDispose` release 了）；或者关掉小窗之后声音还在放。
- 正确做法：`PipController.isHandedOver(player)` 为真时详情页不得 release；
  「展开」= `onPictureInPictureModeChanged(false)` → `pendingDetailId` → `PlaybackHandoff.givePlayer` → 回详情页；
  「关闭」的收尾挂在 **`onStop`**（`MainActivity` 里有序 `when`：退出回调 300ms 确认 / 刚进小窗瞬停 /
锁屏只暂停 / 系统说仍在 PiP 则暂停 + 15s 复查 / 其余才 `closeAndRelease()`），`onDestroy` 只是兜底。
**不要**再写成"关闭 = Activity 销毁"（见 H12 与硬约束 17）。

**H4 · 画中画控制栏最多 3 个自定义按钮，就用标准的三个**
- 触发：需求写「播放/暂停、播放顺序、稍后观看队列、全屏」。
- 现实：`PictureInPictureParams.setActions` 在手机上最多显示 3 个；队列属于主界面，
  塞进小窗之后点了还得把 Activity 拉起来。
- 现行做法（用户最终确认）：`后退 10 秒 / 播放暂停 / 前进 10 秒` 三个 `RemoteAction`，
  「全屏」用系统自带的展开按钮；入口在**播放器自己的菜单**（`MediaPlayer(onEnterPip = …)`，
  菜单是原生 `AlertDialog`）。图标必须是资源或 Bitmap：`res/drawable/ic_pip_{rewind,pause,play,forward}.xml`
  （用 Material 标准图形，别自己画）。动作经广播回到 `MainActivity` 注册的接收者，
  动作后记得 `setPictureInPictureParams` 重设一次（播放/暂停图标要换）。

**H5 · 自动化验证的系统边界（本次踩到的两条）**
- 画中画窗口的「关闭 / 展开」是系统覆盖层，**不吃 `adb shell input tap`**（注入触摸被忽略）。
  验证「展开」改用 `am start --activity-reorder-to-front`（等于把任务拉到前台），
「关闭」只能做代码路径确认，直接写进 `docs/CHANGELOG.md`。
- 全屏页面（图文查看器、视频真全屏）上 `uiautomator dump` 经常返回空串
  （`Failed to write while dumping service user: Broken pipe`）。此时改用截图 + 查库取证；
  要确保打开的是**图文**作品，先
  `run-as <pkg> sqlite3 databases/xhs_local.db "select noteId from history where noteType=1"`
  拿 id，再 `am start -a android.intent.action.VIEW -d "xhstp://note/<id>"` 直接打开。

**H6 · 动画"不生效"先查 `animator_duration_scale`**
- 触发：给双击缩放接了 `animateFloatAsState` + `tween(240ms)`，实机上却像瞬变。
- 症状：在渲染值上挂探针，`logcat` 只得到 `1.0` 与 `2.5` 两个值（没有任何中间值）。
- 原因：模拟器/开发者选项把 **动画时长比例设成了 0**（`settings get global animator_duration_scale`
  → `0`），Compose 的动画会遵守这个比例，于是瞬间完成。代码本身没问题。
- 正确做法：`settings put global animator_duration_scale 1` 再测；要抓中间帧而截图太慢
  （`screencap` 单次接近秒级）时，把动画时长临时调到 10s 以上、在渲染值上挂一行 `Log.d`
  数中间值，取证完**必须移除探针并把时长还原**（同时把 `animator_duration_scale` 改回去）。

**H7 · 触感反馈用系统 API，验证靠 `dumpsys vibrator_manager`**
- 触发：要给长按、切换、落位加振动。
- 做法：`ui/components/Haptics.kt` 包一层 `LocalHapticFeedback`，四档语义别用错：
  `LongPress`（长按/开始拖动）、**`ContextClick`（轻点，别用 `TextHandleMove`：那是文本光标移动的）**、
  `SegmentTick`（滑视频/翻图片这类换挡）、`Confirm` / `Reject`（收藏与移除）。
  **不要** `Vibrator`（系统 API 尊重用户的触感开关，也不需要 `VIBRATE` 权限）。
- 验证：`adb shell dumpsys vibrator_manager | grep xhs` 能看到 `opPkg=com.thirdparty.xhs…`
  的记录（长按是 `Prebaked{effect=HEAVY_CLICK}`，轻点是 `TICK`）。模拟器上 `scale: 0.00` 是正常的，
  因为没有可用触感硬件或用户关掉了触感，调用本身已生效。

**H8 · 不要自绘对话框外壳**
- 触发：用户反馈"对话框没有遮罩和动画"，我先做了一版自绘遮罩 + 自绘入场动画的统一外壳。
- 结果：被明确否决："不要自己绘制，用原生的 AlertDialog"。
- 正确做法：一律 `material3.AlertDialog`。实测遮罩本来就在（长按对话框打开时背景亮度
  `239 → 96`，约 60% 压暗）；动画由系统负责，若设备的"动画时长比例"是 0 就瞬间完成，
  那是用户设置。**不要再接管对话框的窗口与遮罩**。

**H9 · 同一角落的浮动按钮要一起排**
- 触发：发现页原本有"刷新"FAB，稍后观看入口也贴在右下角。
- 症状：后画的扩展 FAB 把刷新按钮整个盖住，用户看到"稍后观看把刷新替代了"。
- 正确做法：`ui/components/CornerFabStack.kt` 统一排：小号刷新（次要色）在上、扩展稍后观看在下，
  间距 `Spacing.m`；队列为空时只剩刷新；画中画时整组隐藏。

**H10 · 同一个播放器被两个界面抢：小窗进驻时信息流必须"放过它"**
- 触发：推荐页 → 详情页 → 小窗，三处都在用**同一个** `ExoPlayer` 实例。
- 症状：小窗在前面放着，后面的推荐流也在放 → 两条声音混在一起；于是"进小窗就让信息流
  `pause()`"，但**无差别 pause 会把小窗那一台一起按停**（实测：小窗里视频停住、
  `dumpsys audio` 里那一路 `state:paused`）。
- 正确做法：一律用 `PipController.isHandedOver(player)` 判断归属再决定要不要动它
  详情页销毁时不 release 是这条规则，信息流在小窗期间暂停也是这条规则。
- 判断"现在到底有没有在放"：`adb shell dumpsys audio | grep 'AudioPlaybackConfiguration piid:'`
  看本应用那几路的 `state:started / state:paused`。`logcat` 里的累计事件（`event:started` 计数）
  是历史量，不能判断当前状态。

**H11 · 列表拖动排序要有边缘自动滚动，且位移只用一个坐标系**（队列的拖动排序**已删除**，见硬约束 20 / H2；本节只在其它页面将来真要做拖动排序时才参考）
- 触发：队列排序需要"把第 1 行拖到屏幕外的第 N 行"。
- 症状：只能拖到当前可见区域内的位置；第一版"手指位移累加 + 每过半行换位"更是只能挪一格（见 H2）。
- 正确做法（现行）：
  1. **只用一个坐标系**，内容坐标 = 视口坐标 + `scrollState.value`；手指位置反推为
     `viewIndex * rowHeight + change.position.y − scrollState.value`，落点与行的位移都从内容坐标算。
     这样"列表被滚动"和"手指移动"只是同一个数在变，不需要到处补正。
  2. 边缘自动滚动：一个按帧跑的循环，在上下 110dp 内滚动，步长随接近程度衰减（上限 18dp/帧），
     并把滚动量补回拖动位移（`dragOffset += moved`）。**密度只能在组合里读**，要先把 dp 换算成 px 再进协程。
  3. 第二根手指的滑动天然可用：被拖行的手势只处理自己那个指针，`verticalScroll` 是它的父节点，
     另一个指针的拖动归父节点：前提是手势与 `clickable` 挂在同一个节点上（H2）。
- 参考：本节只在"其它页面将来真要做拖动排序"时才用；队列页 `ui/screens/WatchLaterScreen.kt` 已无拖动实现（硬约束 20）。
**H12 · 画中画"关闭"不保证销毁 Activity：收尾要挂在 `onStop`**
- 触发：只在 `onDestroy()` 里 `PipController.closeAndRelease()`，以为"关掉小窗 = Activity 销毁"。
- 症状：用户没在小窗里按暂停就关掉小窗，声音继续放（用户实测反馈）。
- 原因：关闭小窗时系统**不保证**立刻销毁 Activity（实测只回调 `onStop`），播放器于是留了下来。
- 正确做法：`onStop()` 里补一次收尾，判据三条一起看
  `!isChangingConfigurations && !isInPictureInPictureMode && !PipController.inPip.value && hasSession()`。
  展开回详情页走的是"同一 Activity 回到前台"，不会触发 `onStop`；进入小窗时 `PipController.start()`
  已经把 `inPip` 置 true，所以也不会误杀。
- 顺带：这一路要把 `pendingDetailId` 清掉，否则小窗被关掉后下次回到前台还会把人拽进那个详情页。

**H13 · 推荐页（全屏视频）上 `uiautomator dump` 常返回空串，导航要用固定坐标兜底**
- 症状：`DumpUi` 返回空串 → 脚本里所有"按文本找控件"都失败（`TapText` 返回 False），
  看起来像应用没响应，其实只是取不到 UI 树。
- 兜底：底部三个 tab 的坐标是固定的（本机 1080×2400、手势导航：`y ≈ 2220`，
  推荐/发现/我的三个中心 `x ≈ 180 / 540 / 900`）。先 `input tap 540 1200` 让沉浸式标题栏与底部导航出现
  （有时要连点两次），再按坐标切 tab；切到普通页面后 dump 就正常了。
- 教训：脚本报"控件找不到"时，先确认 dump 是不是空的，再怀疑应用（同 I1）。

**H14 · 画中画"进/出"两个方向都会踩坑：进小窗会被 pause，关小窗可能不释放**
- 进小窗：
  - Activity 会走一次 `ON_STOP`（实测日志 `PauseWhenNotStarted ON_STOP`），
    `PauseWhenNotStarted` 无条件 `pause()` → 小窗里停在暂停。
    → 判据：`if (PipController.isHandedOver(player)) return`：交给小窗的播放器，生命周期事件不许动它。
  - 交接后必须**显式接着** `playIntent`（`PipController.start(..., playIntent)`）。
- **不要**在进小窗时 `onBack()` 退出详情页：那条导航记录一弹掉，用户关掉小窗后详情页也"没了"，
  展开时只能 `navigate` 一条新记录 → 新 ViewModel = **整页重新加载**。
  正确做法：小窗期间让导航内容**不参与组合**（`if (!pipActive) NavHost(...)`），记录与状态都留着。
（曾经为了保滚动位置改成「照常组合 + 不透明黑底盖住」，结果踩出 H17：见下面那条。）
- 出小窗（展开 vs 关闭）：
  - **不要**用 `isInPictureInPictureMode` 当"还在小窗里"的判据：关闭时它可能仍是 true，
    于是 `onStop` 里的释放判据永远不成立 → 后台一直出声（用户报过两次）。
  - 正确判据：**"有会话 + 走到 `onStop`"**；收到退出回调后又走到 `onStop` 用 300ms 确认窗口兜底。
  - 宽限窗口要够长：实测 `onPictureInPictureModeChanged(false)` → `onResume` **1.15 秒**，
    500ms 的窗口会把展开误判成关闭（播放器被释放 → 详情页只能重建）。现在用 **2.5 秒**。
  - 顺序必须是 **先交接再恢复**：`handBackForDetail()` → `PlaybackHandoff.givePlayer()` →
    最后 `inPip = false`。反过来会让详情页提前重组、自建播放器，小窗那个变孤儿（背景音）。
  - `PipController.isHandedOver` **只认小窗会话**（`_session.value?.player === p`）。**不要**把
    `PlaybackHandoff` 的持有并进来：信息流交给详情页的那台会永远留着那个标记，一合并详情页切后台/锁屏
    就永不暂停、返回时也不释放（阶段三十修掉的正是这个）。"展开小窗刚交回详情页"那一瞬间另用
    `PipController.isReturningToDetail`（= `PlaybackHandoff.isHeldForHandBack`）放过那次 `ON_STOP`。
  - 判"是不是已经在详情页"时注意 `destination.route` 是**模式串** `detail/{noteId}`，
    要和 `Routes.detail(id)` 比对必须把 `arguments["noteId"]` 拼回去。
  - 从**推荐页**点进详情再开小窗这条路上，暂停来自信息流自己：`VideoFeedScreen` 的
    `LaunchedEffect(active, player)` 在 `active=false` 时 `pause()`，而这一台正是被交给详情页、
    又交给小窗的同一台（日志：`feed active=false handedOver=false` → 小窗里停住）。
    → 信息流所有"停播"分支（`active=false`、`inPip`、dispose）都必须先问归属，
    判据统一用 `PipController.isHandedOver(p)`（**只认小窗会话**："要不要暂停/释放"用它；
  "刚交回详情页那一瞬间"用 `isReturningToDetail`）。
- **锁屏不是关小窗**：息屏/锁屏时 Activity 也会 `onStop`，但小窗窗口还活着。把它当"小窗没了"会
  收掉会话、`inPip` 置 false，解锁后那个窗口就按导航内容重组 → 用户看到"小窗里是视频外面套着详情页"。
  判据：`!PowerManager.isInteractive || KeyguardManager.isKeyguardLocked` 时不动会话；
  另在 `onResume` 里做幂等兜底：`isInPictureInPictureMode && hasSession()` → `inPip = true`。
- **播放器归属要分成两档，别用同一个判据**（踩过）：
  - `PipController.isHandedOver(p)` = **只算小窗会话**（`_session.value?.player === p`），用于
    "要不要暂停 / 要不要 release"。**不要**把 `PlaybackHandoff` 的持有并进来：那个标记是"曾经
    交给过别的屏幕"，信息流交给详情页的那台会**永远**留着它，一合并详情页切后台/锁屏就永远不暂停
    （用户反馈"详情页切后台还在放"），而且返回时也不会被释放（泄漏）。
  - `PipController.isReturningToDetail(p)` / `PlaybackHandoff.isHeldForHandBack(p)` = **只覆盖
    "展开小窗刚交回详情页"那一瞬间**（`givePlayer` 之后、详情页认领之前），专门用来放过那次 `ON_STOP`。
- **锁屏不是关小窗，但也不该继续播**：息屏/锁屏时 Activity 也会 `onStop`，
  ①把它当"小窗没了"会收掉会话 → 解锁后窗口里变成"视频外面套着详情页 UI"；
  ②完全不动又会让视频在锁屏后继续出声（`PauseWhenNotStarted` 对已交接的播放器是跳过的，
  没人会去暂停它）。正确做法：`screenOff && hasSession()` → **只暂停、保留会话**
  （`PipController.pauseForScreenOff()`），解锁后不自动续播。
- **关掉小窗要交出进度**：`closeAndRelease()` 会销毁那台播放器，详情页会重建一个新的
  不 `PlaybackHandoff.stash(noteId, currentPosition, playWhenReady)` 的话，回到详情页就是 **0:00**
  （用户反馈）。这条与"信息流 → 详情页"用的是同一个单槽通道：feed 的 stash 在详情页 compose 时
  立刻被消费清空，所以小窗收尾再 stash 不会互相覆盖。
- 比例：`videoSize` 要按 `unappliedRotationDegrees` 交换宽高再算比例（手机横拍片常是"横向帧 + 旋转 90°"），
  并且夹到 PiP 允许的 `[1/2.39, 2.39]`；尺寸变化要重设参数；画面按比例信箱式画，别拉满整窗。

**H15 · 小窗/全屏这些"同一播放器的两个布局"要防住实施侧的手"顺手 pause"**
- 表现：点「全屏」或「小窗播放」后画面停住；或播完按钮状态不对。
- 根因模式：布局切换会 dispose 掉上一个组合，而 dispose 里"顺手 pause 一下免得后台出声"就打在**共享**播放器上。
- 规矩：`PauseWhenNotStarted(pauseOnDispose = ownsPlayer)`，并且判据在**事件发生时**求值
  （`if (pauseOnDispose && !PipController.isHandedOver(current))`），不要用构造时捕获的布尔值。

**H16 · 同一页面的"两处视图共享一个页码"会让触感/回调发两次**
- 表现：图文里在**全屏查看器**滑动切图，触感响两次（嵌入画廊 + 查看器各一次）。
- 根因：查看器滑完回调上层把**嵌入画廊程序化**滚到同一页，而两处都用 `settledPage` 做判据；
  程序化滚动同样会改变 `settledPage`。
- 规矩：`PagerPageHaptics` 只对**本分页器自己的 `DragInteraction`** 置位的翻页给反馈
  （程序化滚动不产生拖动事件）。任何"共享页码的两处视图"都要按这个模式区分"用户操作"与"程序化同步"。

**H17 · 小窗期间不要保留整棵导航树：两块视频 surface 会抢同一个播放器**
- 触发：为了保住详情页的 `rememberSaveable`（滚动位置 / 全屏状态 / 图片页码），把小窗期间的
  `if (!pipActive) NavHost(...)` 改成「照常组合 + 盖一层不透明黑底 + 小窗视频」。
- 症状（用户报的回归）：从小窗回到详情页后**播放器没有画面**（声音还在），有时进度还回到 `0:00`。
- 原因：同一个 `ExoPlayer` 被**两块** `VideoSurface` 绑定。绑定是「一个播放器一份」：小窗那块后绑定、
  把画面抢过去；关掉小窗时小窗那块被 dispose，而播放器实例没变，详情页那块**不会重新绑定** → 没画面。
  进度那半是另一条：页面一直挂着，就不会再走「重新 compose → `PlaybackHandoff.take()` → 应用续播进度」那条路。
- 正确做法：小窗期间**摘掉导航内容**（`if (!pipActive)`）。滚动位置确实会丢，但**画面与进度不能丢**。
  真要两者都保住，得先做「surface 世代号」（PiP 退出时强制重建视频视图），或把 `SaveableStateHolder`
  提升到 NavHost 之外，而不是让两块 surface 并存。
- 取证要点：PiP 往返之后**必须看截图确认有画面**，并让控制栏显出来读进度（`1:33 / 3:01` 之类）；
  只量文本坐标（滚动位置）会漏掉这个回归：这次就是只量了坐标才没发现。

**H18 · 剪贴板口令"不响应"：别把"已问过"标记写在这两个地方**
- 症状（用户报的）：复制了分享口令、回到应用，**没有任何反应**；或者偶尔"闪一下就没了"。
- 两个真实根因（都在"什么时候写 `KEY_LAST_CLIP_PROMPTED`"上）：
  1. **自己分享的那条也写了标记**：分享 → 面板里点「复制」→ 回到应用时按"这是我自己发的"静默跳过，
     但顺手把"已问过"写了。`ShareText` 的自分享记录**只在内存**（进程重启即空），于是再次回来时
     内容一样、标记又已存在 → 永远不再提示。修法：自分享的静默分支**不写**任何标记。
  2. **弹窗被"点外部"瞬间关掉，而标记在弹出时就写了**：这一次误关就把这条口令**永久**变成"问过了"。
     实机日志定位：第一次 `lastLen=-1 same=false`（确实弹了），6ms 后 `same=true`（标记已写），
     界面上什么都没留下。修法：标记只在**用户真的作答（打开/取消）**时写；弹窗设
     `dismissOnClickOutside = false`；聚焦后延迟 350ms 再读剪贴板，让开"切回前台那一下的触摸尾巴"。
- 附带规矩：去重按**剪贴板内容**（不是 note id）；换一条口令（内容变了）应该重新问。
- 取证：`logcat -s XhsClip` 会打印 `len/note/lastLen/same/self`；复现路径是
  「详情页分享 → 分享面板 Copy → 强杀进程 → 再进应用」。

**G7 · 海外线路：能"秒失败"就别等超时，网络一恢复就要自动重试**
- 症状：没开线路时 App 一直转圈（原来 connect 30s × 2 次重试 ≈ 1 分钟才有结论）；开了线路回来还得手动点重试，
  "检测更新"更是被失败退避挡住。
- 做法：
  1. `App.hasValidatedNetwork()`（`NET_CAPABILITY_INTERNET` + `VALIDATED`）为假 → `XhsApi` 直接抛
     `NoUsableNetworkException`、更新检查直接返回 `Failed`，**一个字节都不发**；
  2. 超时收紧 + `callTimeout`：主客户端 10/15/15s + callTimeout 20s；更新检查 6/8/12s（它还要 `callTimeout`，
     否则 atom 那步卡住就没人管）；
  3. 自动重试的**唯一触发点**是 `App.watchNetwork()` 的 `onAvailable` / `onCapabilitiesChanged(VALIDATED)`
     → `bump()` → `networkEpoch` +1 → 各 ViewModel 的 `networkEpoch.drop(1).collect { … }` 补载；
  4. `bump()` 里还要**清掉更新检查的失败退避**（`lastUpdateFailed=false; lastUpdateAttemptAt=0`）再查一次，
     否则"进来时没网 → 失败 → 退避 5 分钟"会把开了线路之后的这次机会也吃掉。
- 验证手法：`adb shell svc wifi disable` → 冷启动（看错误态多久出现）→ `svc wifi enable` → 看内容是否自动回来、
  以及 `logcat -s XhsUpdate` 里是否出现一次 `check=UpToDate`。VPN 起来与"Wi-Fi 恢复"走的是**同一条**回调。

**H19 · 全屏「恢复」按钮的位置规则：图文与视频必须一致（贴底，被控制栏顶起来）**
- 两处实现：`ui/components/FullscreenImageViewer.kt`（图文）与 `ui/components/MediaPlayer.kt`（视频全屏）。
- 规则（用户定的）：
  - **底距**：`windowInsetsPadding(WindowInsets.navigationBars)` + `padding(bottom = Spacing.l)`：就是"贴底"，
    两边必须一样；
  - **被控制栏顶起来**：视频那边控制栏可见时要抬到它上面。高度**实测**（`AutoHideController` 里控制栏根节点
    的 `onGloballyPositioned` → `onControlBarHeight` 回调到外层，因为「恢复」按钮在外层那一层，
    它看不见 `AutoHideController` 的内部状态；可见性同样靠 `onControlsVisibility` 报出来）。
- 踩过的坑：①固定 96dp 底距 → 控制栏没出来时按钮飘在半空（"应该像图文一样贴底"）；
  ②在 `AutoHideController` 里读外层状态 → 编译不过（作用域不对），所以状态必须**提到外层**、内部只上报。
- 验证限制：这个按钮只在**缩放后**出现，而 `adb shell input` 注入不了双指捏合：只能真机双指确认
  （想临时看位置可以把条件改成 `if (fullscreen)`，看完**必须还原**）。

**H20 ·「失败提示」漏在哪：按数据条数建页的容器 + 分页失败 + 兜底成空**
- 三种漏法（都真实出现过）：
  1. **按数据条数建页的容器**：发现页的分类内容是 `HorizontalPager(pageCount = { categories.size })`，
     `categories` 为空时**一页都不画** → 内容区整块空白。修法是「数据为空就早退成整块失败/空态」。
  2. **分页失败静默**：底部只有 `if (loadingMore) LoadingIndicator(...)`，下一页失败时什么都不显示，
     用户以为内容就这些。修法是底部 `FooterRetry`（失败判断要优先于转圈判断）。
  3. **兜底成空**：`runCatching { … }.getOrDefault(emptyList())` 把失败变成「空列表」，
     界面于是显示「还没有内容」：把网络错误伪装成空数据（`UserListScreen` 的注释里记过同类问题）。
- 规矩见硬约束 26：网络列表的首屏失败与分页失败都要有提示 + 重试。
- 另外别忘**自动重试的覆盖面**：`App.networkEpoch` 的补载列表要包含「这个页面的每一块网络数据」
  发现页当初只补了网格与粉丝圈、漏了分类，于是网络恢复后页面一直停在「分类加载失败」（实测 8 秒仍未恢复）。
- 验证：断网冷启动进发现页 → 应看到整块「分类加载失败 + 重试」；开回网络 → 应**自动**恢复（实测 10.1 秒，
  含轮询与网络校验延迟），不需要手点。

**H21 · AOSP 的「应用锁」是车机上的特权系统应用，不是第三方 App 能调用的 API**
- 背景：用户看到 <https://source.android.com/docs/automotive/unbundled_apps/app-lock> 讲「应用锁」，
  问能不能用。结论：**不能用**，别再去试。
- 事实（读那页正文得到的，2026-10-09）：
  - 组件是 `com.android.car.sensitiveapplock`，属于 **Android Automotive（AAOS）**，**Android 14 (API 34) 起**；
  - `Android.bp` 里要求 `android_app_import { certificate: "platform", privileged: true }`，
    也就是**平台签名 + 预装进系统镜像**，还要 `privapp-permissions`（`GET_ACCOUNTS_PRIVILEGED`、
    `QUERY_USERS`、`MEDIA_CONTENT_CONTROL`、`android.car.permission.CAR_POWER`）；
  - 要声明平台特性 `com.android.car.sensitive_app_lock`，并在 `preinstalled-packages.xml` 里只给
    **次要用户**装（Guest / HSUM 不支持）；
  - 它自己是个用 PIN 键盘锁**别的应用**的小应用，还有 RRO 换肤与恢复账号那套。
- 所以普通手机第三方应用只有两条官方路：`BiometricPrompt` 的 `DEVICE_CREDENTIAL`
  （弹系统锁屏凭据页，不弹指纹）或 `KeyguardManager.createConfirmDeviceCredentialIntent()`。
  想要真正的"应用级锁定"还有 `DevicePolicyManager` + LockTask，但那需要设备管理员/MDM 托管。
- 本项目的选择：**保持原有的指纹/面容 + 设备密码**（`ui/components/BiometricLock.kt`）。
  2026-10-09 试过改成"只用系统锁屏凭据"，用户看到指纹没了就要求改回来，已完整回滚。

**H22 · 界面文案不是聊天：口语化 = 返工**
- 触发：用户说"文案有 AI 味"，我顺手把界面文案也改口语了。
- 症状：用户直接驳回：「注意这是一个 APP 的文案，不是小红书文案」。
  改坏的样子：「剪贴板里有作品链接，打开看看？」「删掉就找不回来了，一共 3 条。」「网络不通，或线路到不了海外服务器。」
- 正确口径（已固化成硬约束 27）：界面文案要**书面、简洁、中性**。
  - 按钮 / 标签：动词或名词短语。`打开`、`取消`、`重试`、`清空`、`前往发布页`；
  - 说明句：写清会发生什么。`将删除全部 3 条记录，此操作不可撤销。`（不要"删掉就找不回来了"）；
  - 失败提示：`网络连接失败，网络恢复后将自动重试`（写清"发生了什么 + 怎么办"）；
  - 同一个动作在不同页面用同一个词，别一处"重试"一处"再试试"。
- 注意区分范围：**docs/ 下的项目文档可以写得口语一点**（那是给人读的说明），
  但 `ui/` 里的字符串是产品界面，必须按上面的口径写。
- 改动界面文案后，按 `docs/VERIFY.md` 把改到的页面 dump 一遍再交（至少确认字符串真的换上了）。
- **别只改自己碰过的那几处**：第一次返工时只改了手边几个字符串，用户当场指出「检查更新页呢」，一全量扫才发现还有一批。做法是先把 `ui/`、`data/`、`net/` 里所有中文字面量拉一遍（含参数化文案），再决定改哪些。
- **错误原因也是界面文案**：`UpdateDownloader` 的失败原因、`UpdateChecker` 的 `Failed(reason)`、`BackupScreen` 的异常提示都会原样显示给用户，所以里面不能出现 API 路径、`tag_name` 这类字段名、`javaClass.simpleName` 这类内部标识。

**H23 · 网络恢复补载的判据是「失败过」，不是「空」；网络回调只在换网 / 验证翻转时 bump**
- 症状（用户 2026-10-09）：详情页有时候会频繁重建，返回上一页再进来又恢复了。
- 根因一：`DetailViewModel` 的补载条件是 `item == null || comments.isEmpty()`。
  **没有评论的作品永远满足 `comments.isEmpty()`**，于是每一次网络状态变化都会整页重取。
  同类写法还有 `DiscoverViewModel` 的 `feed.items.isEmpty()` / `fanGroup.isEmpty()` 与
  `VideoFeedViewModel` 的 `items.isEmpty()`：某个分类本来没有内容、用户确实还没关注任何人时，
  都会被当成"没加载成功"反复重取。判据要换成错误标记：`feed.error` / `fanGroupError` /
  `commentsError` / `error`。
- 根因二：`App.watchNetwork()` 的 `onCapabilitiesChanged` **不只在换网时触发** ——
  信号强度、带宽估算一变就回调，十几秒一次。以前每次回调都 `bump()`，等于"网络能力微调"就带动
  所有页面重新加载。现在只有**换了网络（networkHandle 变化）**或**验证状态翻转**才算变化，
  并且只在"变成已验证"时 bump（掉成未验证不必再打一轮请求，那必然失败）。
- 验证手法：`logcat -s XhsNetRetry` 会打印详情页每次补载判定的输入
  （`failed / item / comments / commentsError`）。连做 4 次断网-恢复，零评论作品的 8 次判定全是
  `failed=false ... comments=0` → 一次都没重取；而首屏失败后再恢复，判定是 `failed=true` → 补载，
  实测 6.3 秒恢复。改判据时**必须同时验这两头**：不该重取的别重取，该补载的要补上。

**H24 · 「加载中 / 失败 / 真的空」三种状态必须各有分支，判定顺序不能反**
- 症状（用户 2026-10-09）：一进发现页就显示「没有可用的分类」，而且不给 loading。
- 根因：上一轮为了修「发现页没有失败提示」，我加了"分类为空就整块失败态"的早退，但**没区分
  "还没回来"和"回来了但没有"**：`categories` 初始就是空列表、请求还在路上，于是第一帧就下了
  「没有可用的分类」的结论。
- 规矩：任何"数据为空就显示某种状态"的地方，都要按 **loading → error → empty** 的顺序判，
  并且状态里必须有独立的 loading 标记（`DiscoverUiState.categoriesLoading`，初始 true，因为
  进页面时第一次请求就发出去了）。
- 自查清单（本轮同时核对过，均 OK）：`VideoFeedScreen`（先 `firstLoading`）、`AuthorScreen`、
  `SearchScreen`、`UserListScreen`、`DiscoverTabScreen` 的网格与粉丝圈页、`CacheScreen`、
  `ProfileScreen`、`LoadMoreFooter`（只在列表非空时出现）。
- **初值也算**：区分「还没回来」靠的是 loading 标记的**初值**。凡是在 `init` 或进页面时就发请求的页面，
  `xxxLoading` 的初值必须是 `true`，否则第一帧就落到「空」分支。2026-10-09 一次扫出四处：
  `VideoFeedUiState.firstLoading`（推荐页闪「暂无推荐内容」）、`FeedSection.firstLoading`
  （分类网格闪「这个分类还没有内容」）、`DiscoverUiState.fanGroupLoading`（粉丝圈闪「暂无推荐粉丝圈」）、
  `UpdateUiState.checking`（检查更新页闪「尚未检查」）。
- 顺带：`DiscoverViewModel` 的 init 以前是「取分类，取自己的 user id，取粉丝圈」一条顺序链，
  粉丝圈的空态窗口被拉长到整个分类往返之后；改成两个协程并行。
- **改 loading 初值前，先搜一遍有没有人拿这个标记决定「要不要发起加载」**：
  `VideoFeedScreen` 原来是 `LaunchedEffect(Unit) { if (state.items.isEmpty() && !state.firstLoading) viewModel.loadMore() }`，
  把 `firstLoading` 初值改成 true 之后这个条件永远不成立，页面就一直转圈（2026-10-09 又踩一次）。
  规矩：**首屏加载由 ViewModel 的 `init` 拥有**，屏幕只负责画状态；`loading` 系列标记只用于显示，
  不参与"要不要加载"的判断。
- 验证：临时给分类请求加 4 秒延迟，进发现页截图 → 内容是 M3 的 loading 组件、文本 dump 里
  没有「没有可用的分类」；去掉延迟后连拍 5 次 dump，字符串一次都没出现，chip 正常出现。

**H25 · 清单与权限的两条小坑**
- **XML 注释不能放在标签的属性之间**。想给 `<application>` 里的某个属性加说明，注释必须写在标签**外面**，
  否则 `processDebugMainManifest` 直接失败（2026-10-09 踩过：把注释塞在 `android:name` 与 `android:allowBackup` 之间）。
- **备份要两个机制一起留**：`android:allowBackup="false"` 从 Android 12 起**已废弃、未来可能被移除**，
  12+ 实际读的是 `android:dataExtractionRules`（排除全部内容的规则文件）。
  2026-10-10 我先按「allowBackup=false 已经够了，规则文件是死配置」把它删掉，lint 先报「缺 dataExtractionRules」
  （allowBackup 已被废弃），补上之后又报「缺 fullBackupContent」（minSdk 26，规则只管 12+）—— **两轮才纠对**，
  最终三条都留：`allowBackup=false` + `fullBackupContent=false` + `dataExtractionRules`。
  **这条记着：删清单属性前先跑一次 lint，别只按语义推。**
- 审查权限的正确姿势：先用 `aapt2 dump permissions <apk>` 看**合并后**的清单（库会带权限进来），
  再逐个对照代码里是否真的用到。本项目 2026-10-09 的结论是「没有多余权限」：
  `WAKE_LOCK` 看着没人用，其实是 `VideoPlayer` 的 `setWakeMode(C.WAKE_MODE_LOCAL)` 在用。

**H26 · 图标：PNG 必须超采样，能矢量的就矢量（48px 下锯齿很显眼）**
- 症状（用户 2026-10-10）："icon 的锯齿也太严重了吧"。
- 根因：`tools/probes/gen_icon.py` 原来直接在**最终尺寸**上 `ImageDraw.polygon` /
  `rounded_rectangle` / `ellipse`，斜边、圆角、圆形的边缘全是阶梯；48px 的桌面图标就是这个观感。
- 现在：①所有 PNG 在 `SS = 4` 倍画布上画完再 LANCZOS 缩小；②自适应图标的**前景与单色层改成矢量**
  （`drawable/ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml`，几何来自 `polys()` 这一份
  归一化坐标，PNG 与矢量共用），所以 API 26+ 的桌面图标在任何尺寸下都不会有锯齿。PNG 前景层
  （`ic_launcher_fg.png`）已删除，它是锯齿的来源又没有存在必要。
- 顺便记两条实测：**这台模拟器的 launcher 用圆形遮罩**（安全区就是直径 66dp 的圆，横排三字的中文
  字标放在 70% 宽会被切两头 —— 这也是那次中文图标方案最终被否掉的原因之一）；矢量前景的
  `android:width/height` 写 108dp、viewport 108，几何按 0..1 × 108 换算即可。

## I. 验证工具本身的坑

**I1 · `uiautomator dump` 失败时会读到上一次的旧文件**
- 触发：脚本里 `uiautomator dump --compressed /sdcard/d.xml; cat /sdcard/d.xml`。
- 症状（本次实测踩了十几分钟）：dump 失败会打印
  `ERROR: null root node returned by UiTestAutomationBridge` 或
  `Failed to write while dumping service user: Broken pipe`（屏幕转场中、全屏、画中画时更容易），
  紧接着的 `cat` 把**上一次的文件**读出来：界面明明已经切走了，dump 却一直返回同一份内容，
  看起来像"点了完全没反应"。
- 正确做法：`DumpUi` 先 `rm -f /sdcard/d.xml`，再 dump，再 `cat`（`tools/verify.ps1` 已改成这样）。
  界面"卡住不动"时先确认 dump 是不是旧文件，再去怀疑应用。
- 相关：整机焦点丢失时（`mCurrentFocus` / `mResumedActivity` 都为空）`uiautomator` 会一直失败，
  按一次 `KEYCODE_WAKEUP` + `KEYCODE_HOME` 就能恢复，不必重启模拟器。
- 反例：把"记录条数有没有变大"当判据：见 I2。

---

**I2 · 触感取证不能用"累计计数"**
- 触发：用 `dumpsys vibrator_manager | grep opPkg=com.thirdparty.xhs | measure` 的数量判断"这次点击有没有触感"。
- 症状：连着点几下计数**卡住不动**（实测停在 47），看起来像"改的代码没生效"。
- 原因：`vibrator_manager` 的 **`Previous vibrations` 只保留最近 50 条记录**，新记录进来旧记录滚出去，
  所以条数在 50 附近饱和；它本来就不是累计计数器。
- 正确做法：**看时间戳**。把最后几条 `createTime` 打出来，与刚才操作的时刻对齐即可（例如
  `10-05 16:25:15.462 TICK`、`16:25:19.898 TICK` 就是点备份页按钮的那几下）。

**I3 · 触感取证要看时间戳**（与 I2 同源，单独列出以免再踩）
- 判据：`createTime` 落在"我刚才操作的那几秒"内，并且 `opPkg` 是本应用，就算通过。

