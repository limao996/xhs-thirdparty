# 构建

## 1. 环境要求

| 项 | 要求 | 本机参考值 |
| --- | --- | --- |
| JDK | **17**（`app/build.gradle` 的 `sourceCompatibility`/`targetCompatibility` 都是 17） | `D:\Scoop\apps\temurin17-jdk\current` |
| Android SDK | 需含 **API 37** 平台与 build-tools | `D:\Scoop\apps\android-clt\current` |
| Gradle | 9.8.0（wrapper 自动下载） | 解压版：`%USERPROFILE%\.gradle\wrapper\dists\gradle-9.8.0-bin\*\gradle-9.8.0\bin\gradle.bat` |
| 磁盘 | 首次构建约 1–2 GB（Gradle 缓存 + 依赖 + 中间产物） | — |

指向 SDK：

```bash
cp local.properties.example local.properties
# 编辑 local.properties：sdk.dir=D\:\\path\\to\\android-sdk
```

`local.properties` 是**本机文件，不入库**（`.gitignore` 已覆盖）。

## 2. 命令

```bash
# Debug（包名 com.thirdparty.xhs.debug）
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug

# Release（R8 + 资源压缩 + 自签名）
./gradlew assembleRelease

# 只要编译检查，不打包
./gradlew compileDebugKotlin

# 清理
./gradlew clean
```

产物：

| 变体 | 路径 | 说明 |
| --- | --- | --- |
| debug | `app/build/outputs/apk/debug/app-debug.apk` | 包名带 `.debug`，可与 release 并存 |
| release | `app/build/outputs/apk/release/app-release.apk` | 已用 `keystore/release.jks` 签名 |

参考耗时：本机 `assembleDebug` 首次约 **1 分 11 秒**（39 tasks executed，Gradle 9.8.0 / AGP 9.4.1 / JDK 17，
2026-10-04 实测）。

**用解压版 Gradle 直调会明显更快**：走 `gradlew` 每次都要校验 wrapper 分发（本机约 +120 秒）。

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.8.0-bin\*\gradle-9.8.0\bin\gradle.bat" assembleDebug
```

### 2.1 校验正式包（可复现）

```powershell
$apk = 'app\build\outputs\apk\release\app-release.apk'
$bt  = "$env:ANDROID_SDK_ROOT\build-tools\37.0.0"        # 换成本机 build-tools 版本目录

Get-FileHash $apk -Algorithm MD5                          # 分发时贴这个值
& "$bt\aapt2.exe" dump badging $apk | Select-String 'package:|sdkVersion|uses-permission|application-label:|launchable-activity'
& "$bt\apksigner.bat" verify --print-certs --verbose $apk # 必须 Verifies + v2 scheme true
& "$bt\aapt2.exe" dump xmltree $apk --file AndroidManifest.xml | Select-String debuggable  # 应无输出
```

`2026-10-04` 实测（`assembleRelease` **1 分 56 秒**，50 tasks executed，含 `lintVitalRelease` / `minifyReleaseWithR8` / `optimizeReleaseResources`）：

| 项 | 值 |
| --- | --- |
| 大小 / md5 | `3,257,070 B` / `30e08640177aae2709af25018bb65c7a` |
| versionName / versionCode | `1.1.0` / `319715`（时间戳表达式，每次构建递增） |
| minSdk / targetSdk / compileSdk | `24` / `37` / `37` |
| 签名 | v2 scheme（v1/v3 未启用），`CN=ThirdParty XHS Client`，RSA 2048 |
| 权限（合并后） | `INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK` + `USE_BIOMETRIC`、`USE_FINGERPRINT`（biometric 库合入）+ `com.thirdparty.xhs.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（androidx 合入） |
| 可调试 | 否（manifest 里没有 `debuggable`） |

装到设备后至少确认两件事：冷启动能看到顶栏的 `游客ID：…` 与瀑布流内容（这一步同时证明 R8 压缩没有破坏 AES 包体与 Room 路径），
以及 `adb logcat` 里没有 `FATAL EXCEPTION`。release 与 debug 是两个 applicationId，可以共存；release 首次安装会新建一个游客身份。

## 3. 版本号规则

| 名称 | 规则 | 当前值 |
| --- | --- | --- |
| `appVersionName` | 给人看的版本，按功能批次手动推进 | `1.1.0` |
| `appVersionCode` | `(int)(System.currentTimeMillis()/1000L - versionEpochSeconds)`，`versionEpochSeconds` = 2026-10-01T00:00:00 本地时区的 epoch 秒 | 随时间递增 |
| `CLIENT_VERSION`（buildConfigField） | 与服务端对齐的**协议版本** | `2.6.0` |
| `CLIENT_CHANNEL`（buildConfigField） | 渠道号 | `1333` |

为什么要时间戳：手工维护 `versionCode` 会忘记递增导致无法覆盖安装；时间戳单调递增；减去固定纪元保证
值落在**32 位有符号整数**范围内（Android 要求，超限会安装失败）。

`versionName` 变了就更新 README 的版本表格与 `docs/ai/CONTEXT.md`。

## 4. 签名

`app/build.gradle` 的 `signingConfigs.release`：

| 项 | 值 |
| --- | --- |
| storeFile | `../keystore/release.jks`（相对 `app/`） |
| storePassword / keyPassword | `xhs-thirdparty` |
| keyAlias | `xhs` |

- 自签名密钥**刻意入库**：让任何人都能构建出可覆盖安装的 release 包（学习/自用场景）。
- 因此**不能**用它上架任何应用商店；公开分发也意味着任何人可伪造同签名包。
- 不要更换密钥：已装用户会因签名不一致而无法升级（只能卸载重装，数据丢失）。

## 5. 依赖矩阵

| 库 | 版本 | 备注 |
| --- | --- | --- |
| AGP / Kotlin / KSP | 9.4.1 / 2.1.21 / 2.1.21-2.0.2 | AGP 9 自带 Kotlin 扩展，**不要再 apply `kotlin-android`**（会报 `Cannot add extension with name 'kotlin'`） |
| Compose BOM | 2026.02.01 | 编译器由 `org.jetbrains.kotlin.plugin.compose` 提供 |
| material3 | **1.5.0-alpha29** | 刻意高于 BOM：M3 Expressive API 只在 alpha 线公开 |
| core-ktx / lifecycle | 1.17.0 / 2.9.4 | — |
| navigation-compose | 2.9.5 | — |
| activity-compose | 1.11.0 | — |
| biometric / fragment-ktx | 1.1.0 / **1.8.9** | fragment-ktx < 1.8.9 会导致 `Can only use lower 16 bits for requestCode` |
| okhttp | 5.1.0 | JSON 用内置 `org.json`，无 gson/kotlinx-serialization |
| room（runtime/ktx/ksp） | 2.8.5 | KSP 生成，不用 kapt |
| media3（exoplayer/hls/ui） | 1.11.1 | HLS 播放 |
| kotlinx-coroutines-android | 1.11.0 | — |
| junit | 4.13.2 | 仅单元测试用 |

升级依赖前先读 [ai/GOTCHAS.md](ai/GOTCHAS.md) 的 B1/B2：有几个版本是**钉死的**。

## 6. `gradle.properties` 中不要删的开关

| 键 | 值 | 原因 |
| --- | --- | --- |
| `android.overridePathCheck` | `true` | 本仓库路径含中文，关掉路径检查才能构建 |
| `android.disallowKotlinSourceSets` | `false` | AGP 9 的兼容逃生开关 |
| `org.gradle.jvmargs` | `-Xmx2048m -Dfile.encoding=UTF-8` | 编码固定 UTF-8，避免中文资源乱码 |
| `android.useAndroidX` / `android.nonTransitiveRClass` | `true` | 标准配置 |

## 7. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `Cannot add extension with name 'kotlin'` | 重复 apply Kotlin 插件；AGP 9 已内置，删掉多余的 `id 'org.jetbrains.kotlin.android'` |
| 中文路径导致的路径检查报错 | 确认 `android.overridePathCheck=true` 仍在 |
| 编译通过但行为像旧代码 | 构建启动早于编辑落盘；重新构建（见 [ai/GOTCHAS.md](ai/GOTCHAS.md) A3） |
| `Can only use lower 16 bits for requestCode` | `fragment-ktx` 被降级，恢复到 ≥ 1.8.9 |
| 依赖看起来解析到了别的版本 | 不信缓存目录名；看编译输出或 `./gradlew :app:dependencies` |
| 首次构建很慢 | 正常：下载 Gradle 9.8.0 与依赖；之后用解压版 Gradle 直调 |

## 8. 构建产物不入库

`.gitignore` 覆盖 `app/build/`、`build/`、`.gradle/`、`.kotlin/`、`.idea/`、`local.properties`、`*.apk`、
`*.keystore`（`keystore/release.jks` 通过例外规则保留）、`tools/out/`。
提交前确认 `git status` 里没有上述内容。
