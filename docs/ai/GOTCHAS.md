# AI · 踩坑规则（GOTCHAS）

> 每条都是"在本项目里真实踩过、且会重复踩"的坑，写成**触发条件 → 正确做法**的形式。
> 命中触发条件时不要重新推理，直接照正确做法执行。

## A. 改代码与验证的流程

**A1 · 改源码**
- 触发：要修改任何 `.kt` / `.gradle` / `.py` / `.ps1` 文件。
- 正确做法：用 `edit` / `write` 工具落盘。**不要**用 PowerShell 拼字符串（`Set-Content`、`-replace` 链）写源码——
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

**C8 · 列表滚动位置：三种成因，一个正确写法**
- 触发：列表/瀑布流在"切走再回来""跳转返回"或"切换分类"后的位置不对。
- 期望一：子 tab / 列表在**切走再回来、跳转详情返回**后要**保留**位置。两个前提：
  1. 分支内容有状态容器：`rememberSaveableStateHolder().SaveableStateProvider(key)`
     （`HomeScreen` 的底部 tab、`DiscoverTabScreen` 的子 tab 都这么做，key 用稳定字符串如 `tab.name`）。
  2. 列表自己的滚动状态可保存，且**不要**再用 `LaunchedEffect(resetKey) { scrollToItem(0) }` 去重置它：
     "跳过第一次运行"的 flag 是错的（被重新激活时 flag 仍为 true，恢复好的位置会被这次运行推回顶部）。
- 正确写法（2026-10-04 第二次修正后的最终形态，`ui/components/XhsWaterfall.kt`，`FanGroupTab` 同理）：
  **把滚动状态按 `resetKey` 分组** —— 换 key 得到新状态（顶部），同 key 恢复（保留位置）：
  ```kotlin
  val gridState = key(resetKey) { rememberLazyStaggeredGridState() }
  ```
- 期望二：`resetKey` 变了必须**真的**回到顶部（刷新、切分类）。注意"变了"要能被 key 区分出来：
  `HorizontalPager` 的**每一页是独立的 saveable 作用域**（lazy 布局按页 key 存取状态），所以
  "推荐 → 最新 → 推荐"回到同一个分类 id 时，旧偏移会被恢复进刚重新拉取的列表 → 用户落在"从没看过顶部"的列表中间。
  **凡"同一个名字可能代表一份新列表"，身份位就必须单调递增**：本项目用 `DiscoverUiState.feedEpoch`
  （只在 `selectCategory` 里 +1），`resetKey = state.refreshTick to state.feedEpoch`。
  另外不要复用 `refreshTick` 做分类切换 —— 它一变，粉丝圈列表的位置也会被重置。
- 症状对照：滚 → 切子 tab / 进详情返回 → 归零 = 缺"期望一"；滚 → 滑到别的分类再滑回来 → 落在列表中间 = 缺"期望二"。
- 验证方式：滚动 → 切子 tab / 进详情或作者页 → 返回，比对**首项文本与 y 坐标**是否一致（`docs/VERIFY.md` §4）；
  分类切换看**首卡是否完整可见**（内容文本会随重新拉取而变，断言不可靠，用截图判定）。

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
- 症状（实测过）：只勾「图片与封面缓存」（63.9 MB）并确认，清完「图片内存缓存」也跟着变成 0 B ——
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

**E5 · 文档声明必须与实现一致**
- 触发：想写"本工具不破解付费 / 不绕过访问控制"这类免责话术。
- 正确做法：本项目的机制就是**自动注册新游客身份、领取服务端发给新游客的 VIP 窗口**，照实描述；
  与之相反的免责声明属于事实错误（AGENTS.md 硬约束 12）。

**E4 · 不要"顺手纠正"账号轮换机制**
- 触发：看到 `app/init` + `login-with-guest` + VIP 校验这套链路，觉得"多此一举"或"看着像作弊"就想删/改。
- 正确做法：那是本项目**唯一的核心功能**（换新游客号续 VIP），删掉它 App 就只会显示付费墙。要动先读
  `AGENTS.md` 第 1 节与 `docs/ARCHITECTURE.md` §3.1。

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
- 正确做法：只用四种合法形式且长度精确 —— `<12hex>889X`、`<15digits>X`、`<16hex>I`、`<30hex>AI`；`AI` 形式 30 字符可建号、32 字符不行（官方先截断再拼后缀）。

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

**G2 · JVM 单元测试里 Android 自带的 `org.json` 是空壳**
- 触发：给解析 JSON 的代码写本地单元测试（`app/src/test`）。
- 症状：`java.lang.RuntimeException: Method optString in org.json.JSONObject not mocked.`
- 正确做法：`testImplementation 'org.json:json:20240303'`（只影响测试 classpath，不改应用的运行期行为）。

**G3 · debug 包的 `VERSION_NAME` 带 `-debug` 后缀**
- 触发：写版本比较（检查更新）或把版本号显示给用户。
- 正确处理：`UpdateChecker.numbers()` 会先截掉 `-`/`+` 之后的部分，所以 `1.2.0-debug` 与 `1.2.0` 相等；
  比较逻辑不要自己去 `split('-')`，复用它，否则 debug 包会永远报"有新版本"。

**G4 · 匿名 GitHub API 只有 60 次/小时/IP，超了是 403**
- 触发：反复冷启动验证「自动检查更新」，或同一出口 IP 下多台机器在测。
- 症状（实测过）：检查更新页显示「检查失败：GitHub 限流（HTTP 403），过一会儿再试」，
  重启多少次都不弹更新弹窗 —— 因为根本没查成功，不是弹窗逻辑坏了。
- 正确做法：先查配额再下结论 —— `Invoke-RestMethod https://api.github.com/rate_limit` 看
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

**H2 · 队列排序：三段折腾的结论是"别用拖动"**
- 走过的三版：①"每过半行换位"（只能挪一格，原因见下）；②内容坐标 + translationY 让位（实机仍不稳）；
  ③再加边缘自动滚动与双指补偿（用户仍然反馈不灵）。
- 根因（第①版）：换位会让这一行的**基准位置**立刻跳一行，而手势位移是在节点局部坐标里累加的，
  基准一跳就抵消掉累加量。
- **最终形态（用户拍板）**：每行左侧序号 + 右侧**竖直排列的小号上移/下移按钮**，点一次与相邻行交换、
  立刻写回数据库（`committed` 本地顺序避免写库往返期间的闪一下）。不再做任何拖动排序。
- 结论：列表排序这类需求，按钮的确定性 > 拖动的"手感"；先把交互做对，再谈手感。

**H3 · 画中画：播放器所有权只有一份，交接必须登记**
- 触发：详情页把 `ExoPlayer` 交给 PiP 小窗（`PipController.start`）后退出详情页。
- 症状：小窗里黑屏（播放器被详情页的 `onDispose` release 了）；或者关掉小窗之后声音还在放。
- 正确做法：`PipController.isHandedOver(player)` 为真时详情页不得 release；
  「展开」= `onPictureInPictureModeChanged(false)` → `pendingDetailId` → `PlaybackHandoff.givePlayer` → 回详情页；
  「关闭」= Activity 销毁 → `MainActivity.onDestroy` 里 `PipController.closeAndRelease()`（见硬约束 17）。

**H4 · 画中画控制栏最多 3 个自定义按钮，就用标准的三个**
- 触发：需求写「播放/暂停、播放顺序、稍后观看队列、全屏」。
- 现实：`PictureInPictureParams.setActions` 在手机上最多显示 3 个；队列属于主界面，
  塞进小窗之后点了还得把 Activity 拉起来。
- 现行做法（用户最终确认）：`后退 10 秒 / 播放暂停 / 前进 10 秒` 三个 `RemoteAction`，
  「全屏」用系统自带的展开按钮；入口在**播放器自己的菜单**（`MediaPlayer(onEnterPip = …)`，
  菜单是原生 `AlertDialog`）。图标必须是资源或 Bitmap：`res/drawable/ic_pip_{rewind,pause,play,forward}.xml`
  （用 Material 标准图形，别自己画）。动作经广播回到 `MainActivity` 注册的接收者，
  动作后记得 `setPictureInPictureParams` 重设一次（播放/暂停图标要换）。

**H8 · 不要自绘对话框外壳**
- 触发：用户反馈"对话框没有遮罩和动画"，我先做了一版自绘遮罩 + 自绘入场动画的统一外壳。
- 结果：被明确否决 —— "不要自己绘制，用原生的 AlertDialog"。
- 正确做法：一律 `material3.AlertDialog`。实测遮罩本来就在（长按对话框打开时背景亮度
  `239 → 96`，约 60% 压暗）；动画由系统负责，若设备的"动画时长比例"是 0 就瞬间完成，
  那是用户设置。**不要再接管对话框的窗口与遮罩**。

**H9 · 同一角落的浮动按钮要一起排**
- 触发：发现页原本有"刷新"FAB，稍后观看入口也贴在右下角。
- 症状：后画的扩展 FAB 把刷新按钮整个盖住，用户看到"稍后观看把刷新替代了"。
- 正确做法：`ui/components/CornerFabStack.kt` 统一排：小号刷新（次要色）在上、扩展稍后观看在下，
  间距 `Spacing.m`；队列为空时只剩刷新；画中画时整组隐藏。

**H7 · 触感反馈用系统 API，验证靠 `dumpsys vibrator_manager`**
- 触发：要给长按、切换、落位加振动。
- 做法：`ui/components/Haptics.kt` 包一层 `LocalHapticFeedback`，四档语义别用错：
  `LongPress`（长按/开始拖动）、**`ContextClick`（轻点，别用 `TextHandleMove` —— 那是文本光标移动的）**、
  `SegmentTick`（滑视频/翻图片这类换挡）、`Confirm` / `Reject`（收藏与移除）。
  **不要** `Vibrator`（系统 API 尊重用户的触感开关，也不需要 `VIBRATE` 权限）。
- 验证：`adb shell dumpsys vibrator_manager | grep xhs` 能看到 `opPkg=com.thirdparty.xhs…`
  的记录（长按是 `Prebaked{effect=HEAVY_CLICK}`，轻点是 `TICK`）。模拟器上 `scale: 0.00` 正常 ——
  没有可用触感硬件/关掉了触感，调用本身已生效。

## I. 验证工具本身的坑

**I2 · 触感取证不能用"累计计数"**
- 触发：用 `dumpsys vibrator_manager | grep opPkg=com.thirdparty.xhs | measure` 的数量判断"这次点击有没有触感"。
- 症状：连着点几下计数**卡住不动**（实测停在 47），看起来像"改的代码没生效"。
- 原因：`vibrator_manager` 的 **`Previous vibrations` 只保留最近 50 条记录**，新记录进来旧记录滚出去，
  所以条数在 50 附近饱和；它本来就不是累计计数器。
- 正确做法：**看时间戳**。把最后几条 `createTime` 打出来，与刚才操作的时刻对齐即可（例如
  `10-05 16:25:15.462 TICK`、`16:25:19.898 TICK` 就是点备份页按钮的那几下）。

**I3 · 触感取证要看时间戳**（与 I2 同源，单独列出以免再踩）
- 判据：`createTime` 落在"我刚才操作的那几秒"内，并且 `opPkg` 是本应用，就算通过。
- 反例：把"记录条数有没有变大"当判据 —— 见 I2。

---

**H12 · 画中画"关闭"不保证销毁 Activity：收尾要挂在 `onStop`**
- 触发：只在 `onDestroy()` 里 `PipController.closeAndRelease()`，以为"关掉小窗 = Activity 销毁"。
- 症状：用户没在小窗里按暂停就关掉小窗，声音继续放（用户实测反馈）。
- 原因：关闭小窗时系统**不保证**立刻销毁 Activity（实测只回调 `onStop`），播放器于是留了下来。
- 正确做法：`onStop()` 里补一次收尾，判据三条一起看 ——
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
    → 判据：`if (PipController.isHandedOver(player)) return` —— 交给小窗的播放器，生命周期事件不许动它。
  - 交接后必须**显式接着** `playIntent`（`PipController.start(..., playIntent)`）。
- **不要**在进小窗时 `onBack()` 退出详情页：那条导航记录一弹掉，用户关掉小窗后详情页也"没了"，
  展开时只能 `navigate` 一条新记录 → 新 ViewModel = **整页重新加载**。
  正确做法：小窗期间让导航内容**不参与组合**（`if (!pipActive) NavHost(...)`），记录与状态都留着。
- 出小窗（展开 vs 关闭）：
  - **不要**用 `isInPictureInPictureMode` 当"还在小窗里"的判据：关闭时它可能仍是 true，
    于是 `onStop` 里的释放判据永远不成立 → 后台一直出声（用户报过两次）。
  - 正确判据：**"有会话 + 走到 `onStop`"**；收到退出回调后又走到 `onStop` 用 300ms 确认窗口兜底。
  - 宽限窗口要够长：实测 `onPictureInPictureModeChanged(false)` → `onResume` **1.15 秒**，
    500ms 的窗口会把展开误判成关闭（播放器被释放 → 详情页只能重建）。现在用 **2.5 秒**。
  - 顺序必须是 **先交接再恢复**：`handBackForDetail()` → `PlaybackHandoff.givePlayer()` →
    最后 `inPip = false`。反过来会让详情页提前重组、自建播放器，小窗那个变孤儿（背景音）。
  - `PipController.isHandedOver` 要**同时**认 `PlaybackHandoff` 的持有：`handBackForDetail()` 会清掉
    session，若只看 session，展开瞬间那次 `ON_STOP` 会把刚交回去的播放器暂停掉。
  - 判"是不是已经在详情页"时注意 `destination.route` 是**模式串** `detail/{noteId}`，
    要和 `Routes.detail(id)` 比对必须把 `arguments["noteId"]` 拼回去。
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


**I1 · `uiautomator dump` 失败时会读到上一次的旧文件**
- 触发：脚本里 `uiautomator dump --compressed /sdcard/d.xml; cat /sdcard/d.xml`。
- 症状（本次实测踩了十几分钟）：dump 失败会打印
  `ERROR: null root node returned by UiTestAutomationBridge` 或
  `Failed to write while dumping service user: Broken pipe`（屏幕转场中、全屏、画中画时更容易），
  紧接着的 `cat` 把**上一次的文件**读出来 —— 界面明明已经切走了，dump 却一直返回同一份内容，
  看起来像"点了完全没反应"。
- 正确做法：`DumpUi` 先 `rm -f /sdcard/d.xml`，再 dump，再 `cat`（`tools/verify.ps1` 已改成这样）。
  界面"卡住不动"时先确认 dump 是不是旧文件，再去怀疑应用。
- 相关：整机焦点丢失时（`mCurrentFocus` / `mResumedActivity` 都为空）`uiautomator` 会一直失败，
  按一次 `KEYCODE_WAKEUP` + `KEYCODE_HOME` 就能恢复，不必重启模拟器。

**H5 · 自动化验证的系统边界（本次踩到的两条）**
- 画中画窗口的「关闭 / 展开」是系统覆盖层，**不吃 `adb shell input tap`**（注入触摸被忽略）。
  验证「展开」改用 `am start --activity-reorder-to-front`（等于把任务拉到前台），
  「关闭」只能做代码路径确认，如实写进 `docs/CHANGELOG.md`。
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

**H10 · 同一个播放器被两个界面抢：小窗进驻时信息流必须"放过它"**
- 触发：推荐页 → 详情页 → 小窗，三处都在用**同一个** `ExoPlayer` 实例。
- 症状：小窗在前面放着，后面的推荐流也在放 → 两条声音混在一起；于是"进小窗就让信息流
  `pause()`"，但**无差别 pause 会把小窗那一台一起按停**（实测：小窗里视频停住、
  `dumpsys audio` 里那一路 `state:paused`）。
- 正确做法：一律用 `PipController.isHandedOver(player)` 判断归属再决定要不要动它 ——
  详情页销毁时不 release 是这条规则，信息流在小窗期间暂停也是这条规则。
- 判断"现在到底有没有在放"：`adb shell dumpsys audio | grep 'AudioPlaybackConfiguration piid:'`
  看本应用那几路的 `state:started / state:paused`。`logcat` 里的累计事件（`event:started` 计数）
  是历史量，不能判断当前状态。

**H11 · 列表拖动排序要有边缘自动滚动，且位移只用一个坐标系**
- 触发：队列排序需要"把第 1 行拖到屏幕外的第 N 行"。
- 症状：只能拖到当前可见区域内的位置；第一版"手指位移累加 + 每过半行换位"更是只能挪一格（见 H2）。
- 正确做法（现行）：
  1. **只用一个坐标系**——内容坐标 = 视口坐标 + `scrollState.value`；手指位置反推为
     `viewIndex * rowHeight + change.position.y − scrollState.value`，落点与行的位移都从内容坐标算。
     这样"列表被滚动"和"手指移动"只是同一个数在变，不需要到处补正。
  2. 边缘自动滚动：一个按帧跑的循环，在上下 110dp 内滚动，步长随接近程度衰减（上限 18dp/帧），
     并把滚动量补回拖动位移（`dragOffset += moved`）。**密度只能在组合里读**，要先把 dp 换算成 px 再进协程。
  3. 第二根手指的滑动天然可用：被拖行的手势只处理自己那个指针，`verticalScroll` 是它的父节点，
     另一个指针的拖动归父节点 —— 前提是手势与 `clickable` 挂在同一个节点上（H2）。
- 参考：`ui/screens/WatchLaterScreen.kt`。
