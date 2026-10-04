# GitHub Copilot 指令

本仓库的完整 AI 协作规则见根目录 [`AGENTS.md`](../AGENTS.md) 与 [`docs/ai/`](../docs/ai/)，以下是必须遵守的要点。

## 技术栈事实（不要按"常见 Android 项目"猜）

- 纯 Kotlin + Jetpack Compose，**没有 XML 布局**；只有一个 `AndroidManifest.xml` 与图标资源在 `res/`。
- 不引入 `appcompat`、`com.google.android.material` 或其它 UI 框架。
- `material3` 必须是 `1.5.0-alpha29`（`MaterialExpressiveTheme` / `MotionScheme` 只在 alpha 线公开）。
- `androidx.fragment:fragment-ktx` 必须 ≥ `1.8.9`（更低版本会让 biometric 抛 `Can only use lower 16 bits for requestCode`）。
- JSON 用内置 `org.json`，不引入 gson / kotlinx-serialization。
- 插件只 apply `com.android.application` + `org.jetbrains.kotlin.plugin.compose` + `com.google.devtools.ksp`；
  AGP 9 自带 Kotlin 扩展，再 apply `kotlin-android` 会报 `Cannot add extension with name 'kotlin'`。
- **核心机制是"游客账号轮换"**：服务端给每个新注册的设备身份发一段 VIP 体验窗口，客户端在窗口将尽时换新身份续期
  （`IdentityGuess.randomFresh()` → `v2/app/init` → `login-with-guest` → 校验 `isVip`，由 `XhsRepository.ensureAccountForRequest()` 在请求前门控）。
  这是本项目"VIP 一直有"的真正原因，改账号相关代码前先读 [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) §3.1 与
  [`docs/ai/GOTCHAS.md`](../docs/ai/GOTCHAS.md) F 节。

## 写代码时

- 数据访问只能经 `data/XhsRepository.kt`；UI 与组件不得直接调 `net/` 或 Room。
- 新接口：先在 `XhsRepository` 实现（含缓存写入与失败语义），再在 ViewModel 暴露状态。
- 新页面：`ui/screens/XxxScreen.kt` + `ui/viewmodel/XxxViewModel.kt`，并在 `navigation/Routes.kt` 与
  `AppNavHost` 登记；属底部页时补 `HomeTab`。
- 颜色用 `MaterialTheme.colorScheme.*`；媒体之上的叠加层用 `ui/theme/Tokens.kt` 的 `Scrim.*`；
  尺寸用 `Tokens.kt` 的 `Spacing` / `Corners` / `AvatarSize` / `Thumb` / `BottomNavClearance`，不要硬编码。
- 失败时**保留已有数据**并暴露错误态；不要把失败渲染成"空数据"。
- 子 tab 内容与列表滚动位置：分支包在 `rememberSaveableStateHolder().SaveableStateProvider(key)` 里，列表的
  `LaunchedEffect(resetKey)` 守卫要记住"位置属于哪个 key"（不要用"跳过第一次运行"的 flag）——见 `docs/ai/GOTCHAS.md` C2/C8。
- 注释只解释"为什么"（尤其要写出约束来源）。

## 不要做的事

- 不提交 APK、截图、反编译/解包产物、`local.properties`、构建缓存。
- 不修改 `keystore/release.jks` 与 `signingConfigs.release` 的口令。
- 不手写 `versionCode` 常数（用 `appVersionCode` 表达式）。
- 不改 `CLIENT_VERSION`（协议版本，与服务端对齐，与 App 版本无关）。
- **不得移除或绕开游客账号轮换链路**（`v2/app/init` → `login-with-guest` → 校验 VIP → 退避），也不要"顺手"把它改成定时轮询。
- 文档/注释里**不要写"不破解付费 / 不绕过付费限制"这类与实现相反的声明**：本 App 的机制就是自动注册新游客身份、领取新游客体验窗口。
- 不实现发评论、点赞等写操作（当前产品范围如此）。

## 提交与验证

- 提交信息用简体中文，`<type>: <简述>`，一次提交一件事。
- 提交前必须本机 `gradlew.bat assembleDebug` 通过。
- 声称"已修复"必须附证据（编译输出行、`uiautomator dump` 文本、截图），见 [`docs/VERIFY.md`](../docs/VERIFY.md)。

## 更多上下文

- 一页速览：[`docs/ai/CONTEXT.md`](../docs/ai/CONTEXT.md)
- 踩坑规则（含 `LaunchedEffect`、修饰符顺序、`uiautomator dump` 陷阱等）：[`docs/ai/GOTCHAS.md`](../docs/ai/GOTCHAS.md)
- 架构与协议：[`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)、[`docs/PROTOCOL.md`](../docs/PROTOCOL.md)
