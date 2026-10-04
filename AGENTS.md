# AGENTS.md — AI 协作入口

> 本文件是**给 AI 编码代理看的**，不是给人读的项目介绍（那在 [README.md](README.md)）。
> 目标：任何 AI 在 30 秒内知道「这是什么、能改什么、不能动什么、改完怎么证明」。
> 与本文件冲突的仓库外习惯一律以本文件为准。

## 0. 硬约束（违反即视为改动失败）

| # | 约束 | 原因 / 症状 |
| --- | --- | --- |
| 1 | **输出简体中文**（代码、命令、标识符保持原文） | 用户偏好 |
| 2 | 纯 Compose，**不引入 XML 布局**，不新增 `appcompat` / `com.google.android.material` | 已刻意移除，UI 全在 `ui/` 下的组合函数里 |
| 3 | material3 **不得降到 BOM 版本**（保持 `1.5.0-alpha29`） | `MaterialExpressiveTheme` / `MotionScheme` 只在 alpha 线公开 |
| 4 | `androidx.fragment:fragment-ktx` **不得低于 1.8.9** | 1.2.5 会让 biometric 抛 `Can only use lower 16 bits for requestCode` |
| 5 | `CLIENT_VERSION` buildConfigField **保持 `2.6.0`** | 它与服务端协议对齐，不是 App 版本 |
| 6 | 不修改 `keystore/release.jks`；不改 `signingConfigs.release` 口令 | 换密钥会导致已装用户无法覆盖升级 |
| 7 | `versionCode` 用 `appVersionCode` 表达式（时间戳推导），不手写常数 | 必须 32 位内且单调递增 |
| 8 | **不提交** APK / 截图 / 反编译产物 / `local.properties` | 见 `.gitignore`；仓库只放源码与文档 |
| 9 | 改源码一律用 `edit` / `write` 工具，**不要用 PowerShell 拼字符串写文件** | 拼串会静默产生乱码或丢内容 |
| 10 | 声明"已修复/已验证"之前必须有可复现证据（编译输出、UI dump、截图） | 见 `docs/VERIFY.md` |
| 11 | **不得移除或绕开游客账号轮换链路**（`v2/app/init` → `login-with-guest` → 校验 VIP → 退避） | 这是本项目唯一的核心功能；少了它页面只剩付费墙（见第 1 节与 `docs/ARCHITECTURE.md` §3.1） |
| 12 | 文档里**不要写"不破解付费 / 不绕过付费限制"这类与事实相反的声明** | 本 App 的机制就是自动化领取新游客 VIP 福利，描述必须与实现一致 |

## 1. 项目一句话

小黄书（老司机软件）第三方 Android 客户端，桌面名「小黄书」（debug 安装为「小黄书.debug」）：Kotlin + Jetpack Compose（M3 Expressive），Room 本地缓存，OkHttp + AES 全量加密包体，media3/HLS 播放器，WebDAV 备份，指纹应用锁。包名 `com.thirdparty.xhs`（debug 加 `.debug`）。

**核心机制（动账号相关代码前必读）**：VIP 不是"破解校验"骗出来的，是**不停换新游客号**换出来的 ——
服务端会给每一个**新注册**的设备身份发一段 VIP 体验窗口，客户端在窗口快用完时换一个新身份，就又能接着看。
链路（顺序不能变）：`IdentityGuess.randomFresh()` 生成随机身份 → `v2/app/init` 注册身份（**这一步才建号**）→
`v2/user/login-with-guest` 拿 `user_token`/`user_hash` → `v2/mine/user-info` 确认新号真的带 VIP
（`XhsRepository.switchToVipAccount()`）。触发点是 `XhsRepository.ensureAccountForRequest()`，由
`XhsApi.beforeAccountRequest` 在每个"需要账号的请求"前调用；不足 `VIP_MIN_REMAINING_S = 60L` 秒就换。
身份必须持久化（`CredentialStore` 的 `device_identity`，换号时清 `user_hash` 与 VIP 缓存），
一次尝试只建**一个**新身份，失败按 `VIP_SWITCH_BACKOFF_BASE_S = 60L` 翻倍退避、上限 `VIP_SWITCH_BACKOFF_MAX_S = 30 分钟`。

## 2. 仓库地图

| 路径 | 职责 | 改动注意 |
| --- | --- | --- |
| `app/build.gradle` | 版本号、签名、`buildConfigField`、依赖矩阵 | 版本号规则见 `docs/BUILD.md` |
| `app/src/main/AndroidManifest.xml` | 权限、Activity、深链 `xhstp://note` | 新增权限要在 `README.md` 的隐私/权限说明与 `docs/PROTOCOL.md` 里同步（当前只有 INTERNET / ACCESS_NETWORK_STATE / WAKE_LOCK） |
| `data/XhsRepository.kt` | **单一数据源**，所有网络读写都在这里 | 新接口先加在这里，不要在 UI 直接调 `XhsApi` |
| `data/XhsDatabase.kt` | Room 数据库 `xhs_local.db`（v2） | 改 schema 必须 bump version；当前是 destructive migration |
| `data/BackupManager.kt` | 备份/恢复内容与格式 | 改字段要同步 `docs/ai/CONTEXT.md` 的备份清单 |
| `net/XhsApi.kt` | 请求封装、重试、会话自愈 | 重试次数/退避在这里（`NETWORK_ATTEMPTS` / `RETRY_BACKOFF_MS`） |
| `net/XhsCrypto.kt` | AES/CBC 加解密 + CDN 图片 AES/ECB | 参数见 `docs/PROTOCOL.md`，不要"顺手"改 |
| `net/WebDavClient.kt` | WebDAV 客户端 | 备份固定写 `xhs/` 子目录 |
| `navigation/AppNavHost.kt` + `Routes.kt` | 唯一路由注册处 | 新页面必须同时登记 `Routes` 常量与 `HomeTab`（如属底部页） |
| `ui/screens/*.kt` | 页面级组合函数 | 每个 screen 对应一个 `ui/viewmodel/`；子 tab 内容要包 `rememberSaveableStateHolder()`，列表滚动状态要按 `resetKey` 分组（`key(resetKey) { … }`），身份位同名却是新列表时必须单调递增（GOTCHAS C2/C8） |
| `ui/components/*.kt` | 可复用组件（瀑布流/播放器/画廊/对话框/水印状态…） | 组件不要直接访问 Room |
| `ui/viewmodel/*.kt` | 状态与业务编排 | 用 `RepoViewModelFactory` 注入仓库 |
| `ui/theme/Theme.kt`, `ui/theme/Tokens.kt` | M3 Expressive 主题、间距/圆角/`Scrim` 令牌 | 新颜色优先用 `MaterialTheme.colorScheme`，scrim 只用于媒体之上 |
| `tools/verify.ps1` | 设备验证函数库（解锁、启动、dump、点击、截图、崩溃计数） | 是 PowerShell，改动后要真跑一次 |
| `docs/` | 项目文档 | 结构变更同步 `docs/README.md` |
| `docs/ai/` | **AI 档案本体** | 见下节 |

上表中未写目录前缀的源码路径，均相对于 `app/src/main/java/com/thirdparty/xhs/`（例：`data/XhsRepository.kt` 即 `app/src/main/java/com/thirdparty/xhs/data/XhsRepository.kt`）。

## 3. 常用命令

```powershell
# 构建（Windows；已解压的 Gradle 直调更快，省掉 wrapper 校验的 ~120s）
gradlew.bat assembleDebug                        # 标准路径
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.8.0-bin\*\gradle-9.8.0\bin\gradle.bat" assembleDebug

# 安装 + 冷启动（必须 force-stop，否则测的是旧进程）
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.thirdparty.xhs.debug
adb shell am start -n com.thirdparty.xhs.debug/com.thirdparty.xhs.MainActivity

# 设备验证函数库
. .\tools\verify.ps1 ; EnsureDevice ; LaunchApp ; $d = DumpUi ; TapText '设置' ; Shot 'step1'
```

验证回路与取证细节：`docs/VERIFY.md`。

## 4. 变更流程

1. **定位**：先 `grep` 到唯一职责文件（上表），不要在 UI 里打补丁绕过仓库层。
2. **复现**：出现缺陷时先复现（真实设备/模拟器），再改。没有复现的"修复"不许提交。
3. **编辑**：用 `edit`；一次只改一个关注点。
4. **编译**：编辑落盘后再启动构建（顺序反了会测到旧代码）。
5. **验证**：`docs/VERIFY.md` 的最小回路；UI 改动给截图或 `DumpUi` 文本。
6. **同步档案**：动了结构/约束/坑，就更新 `AGENTS.md` 与 `docs/ai/GOTCHAS.md`。
7. **提交**：中文提交信息 + 前缀；一次提交一个关注点。

## 5. 汇报格式（用户要求的）

- 逐条需求 → 逐条汇报：`需求 → 做了什么 → 证据 → 状态`。
- 没做完的必须显式写「未完成 / 未验证」，不许用"基本完成"含糊过去。
- 不给结论性形容词（"更流畅了"），给数字或可复现的观测。

## 6. 相关文件

- 项目介绍与快速开始：[README.md](README.md)
- 一页上下文：[docs/ai/CONTEXT.md](docs/ai/CONTEXT.md)
- 约定：[docs/ai/CONVENTIONS.md](docs/ai/CONVENTIONS.md)
- 踩坑规则：[docs/ai/GOTCHAS.md](docs/ai/GOTCHAS.md)
- 提示词模板：[docs/ai/PROMPTS.md](docs/ai/PROMPTS.md)
- 架构 / 协议 / 构建 / 验证：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)、[docs/PROTOCOL.md](docs/PROTOCOL.md)、[docs/BUILD.md](docs/BUILD.md)、[docs/VERIFY.md](docs/VERIFY.md)
