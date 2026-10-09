# AI · 约定

> 只写"能机械执行"的约定，不写理念。冲突时以 [../../AGENTS.md](../../AGENTS.md) 的硬约束为准。

## 1. 代码

| 主题 | 约定 |
| --- | --- |
| 语言 | 只用 Kotlin；不新增 Java 源码 |
| UI | 只用 Compose 组合函数；**不写 XML 布局**；不引入 appcompat / material / 其它 UI 框架 |
| 文件粒度 | 一个 screen 一个文件（`ui/screens/XxxScreen.kt`）；一个 screen 一个 ViewModel（`ui/viewmodel/XxxViewModel.kt`） |
| 命名（Kotlin） | 类/组合函数 `PascalCase`；函数与属性 `camelCase`；常量 `SCREAMING_SNAKE`；Composable 用名词（`NoteCard`），不用 `renderXxx` |
| 命名（资源） | 只保留图标与主题资源：`ic_launcher*`、`ic_launcher_bg`、**小窗控制栏图标 `ic_pip_rewind/play/pause/forward/replay`**、`colors`/`themes`/`strings` |
| 颜色 | 优先 `MaterialTheme.colorScheme.*`；仅媒体之上用 `ui/theme/Tokens.kt` 的 `Scrim.*`，媒体层允许语义明确的 `Color.White` / `Color.Black`（遮罩、进度条、关闭按钮）；不硬编码十六进制（生成图标的脚本除外） |
| 尺寸 | 用 `Tokens.kt` 的 `Spacing` / `Corners` / `AvatarSize` / `Thumb` / `BottomNavClearance`，不散落 magic number |
| 数据访问 | UI 与组件**不得**直接调 `net/` 或 Room；一律经 `XhsRepository` |
| 新接口 | 加在 `XhsRepository`（含缓存写入与失败语义），再在 ViewModel 暴露状态 |
| 新页面 | 同时登记 `navigation/Routes.kt` 常量与 `AppNavHost` 的 `composable`；属底部页则补 `HomeTab` |
| DB 变更 | 改实体/表结构必须 `@Database(version = n+1)` **并写一个真迁移**（范例：v2→v3 的 `watch_later`）；不要依赖 `fallbackToDestructiveMigration`：那会静默清空用户的收藏/浏览/关注/队列 |
| 错误处理 | 失败时保留已有数据 + 暴露错误态，禁止把失败显示成"空数据" |
| 协程取消 | 包**可能挂起**的调用用 `com.thirdparty.xhs.common.runCatchingCancellable`（`runCatching` 会把 `CancellationException` 也吃掉，页面关了还在改状态）；纯同步计算仍可用 `runCatching` |
| 状态写入 | 改 `MutableStateFlow` 一律 `_ui.update { it.copy(...) }`（原子），不要 `_ui.value = _ui.value.copy(...)`（读改写之间会被别的协程插进来） |
| SharedPreferences | 写 prefs 用 KTX 的 `prefs.edit { putString(...) }`（自动 `apply()`），不要再手写 `edit().putX().apply()` |
| Compose 状态 | 子 tab 内容包在 `rememberSaveableStateHolder().SaveableStateProvider(key)` 里；列表滚动状态按 `resetKey` 分组（`key(resetKey) { rememberLazyStaggeredGridState() }`），换 key = 回顶部、同 key = 保留位置；身份位在同名却是新列表时要单调递增（GOTCHAS C2/C8） |
| 并发 | 协程只在 ViewModel / Repository（例外：`App.appScope` 做应用级后台任务、`MainActivity.lifecycleScope` 做与 Activity 生命周期绑定的收尾）；`Dispatchers.IO` 用于 IO；不在组合函数里做阻塞调用 |
| 注释 | 只解释"为什么"（含约束来源），不复述代码 |

## 2. 提交

- 提交信息用简体中文，格式：`<type>: <简述>`，一次提交只做一件事。
- `type` 取值：`feat` / `fix` / `perf` / `refactor` / `docs` / `chore` / `build` / `revert`。
- 改了构建配置（依赖、签名、SDK 版本）用 `build:`；纯文档（含 AI 档案）用 `docs:`。
- **不提交**：`app/build/`、`.gradle/`、`.kotlin/`、`.idea/`、`local.properties`、`*.apk`、截图、反编译/解包产物、探针输出 `tools/out/`。
- 提交前必须 `gradlew.bat assembleDebug` 通过（证据留在回复里，不是感觉）。

## 3. 文档与 AI 档案

- 项目文档（给人）：`README.md`、`docs/*.md`；AI 档案（给 AI）：`AGENTS.md`、`CLAUDE.md`、`docs/ai/*`。
- 两者**不互相照抄**：项目文档可以讲背景与使用方式；AI 档案只放规则、地图、命令、坑。
- 结构/约束/坑有任何变化，同一次改动内更新：`AGENTS.md`（仓库地图或硬约束）+ `docs/ai/GOTCHAS.md`（新增坑）。
- 新增/移动文档后，更新 `docs/README.md` 索引与 `README.md` 的文档地图。
- 文档里的命令必须**能直接复制执行**（使用仓库相对路径，不写机器专属绝对路径）。

## 4. 语言与用户沟通

- 一律简体中文；代码、命令、库名、包名、日志保持原文。
- 逐条需求逐条汇报：`需求 → 做了什么 → 证据 → 状态`；没做完的显式写「未完成 / 未验证」。
- 结论要有可复现证据（编译输出、`DumpUi` 文本、截图、日志行），不接受"看起来没问题"。
- 迭代过程中不主动产出正式 release 包，除非用户明确要求。

## 5. 可移植性

- 脚本与文档里**不写死机器路径**：PowerShell 用 `$PSScriptRoot`，Python 用 `Path(__file__).resolve().parents[n]`。
  唯一的例外是 [CONTEXT.md](CONTEXT.md) §6「环境事实（本机，构建用）」，那张表**就是**用来记本机路径的，不要照搬进别处，也不要把它当可复制执行的命令。
- 需要外部工具的脚本（adb 等）优先读环境变量（`ADB` / `ANDROID_SDK_ROOT` / `ANDROID_HOME`），再退 PATH，找不到就明确报错。
- Gradle wrapper 的 `distributionUrl` 保持 https（不要改成 `file://`），保证别人克隆即可构建。
