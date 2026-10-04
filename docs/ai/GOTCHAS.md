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
