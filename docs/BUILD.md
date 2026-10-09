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

# 发布流程（顺序不能变，硬约束 28）

用户 2026-10-10 明确要求：「以后发布正式包要先改版本号构建正式包，确定没问题了再推送并发布」。
**先推后验**的代价是：一旦正式包有问题，远端已经有坏版本，撤回要动 tag、还得再发一版。所以顺序固定成四步：

```powershell
# ① 先改版本号（Release 资产名与 tag 都由它推导：xhs-thirdparty-<version>-release.apk / v<version>）
#    编辑 app/build.gradle 的 def appVersionName = 'x.y.z'
#    versionCode 不用手写（时间戳表达式推导，天然单调递增）

# ② 构建正式包
$env:JAVA_HOME='D:\Scoop\apps\temurin17-jdk\current'
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.8.0-bin\*\gradle-9.8.0\bin\gradle.bat" :app:assembleRelease

# ③ 本机实测确认没问题（三件事都要做）
#    - 覆盖安装：adb -s emulator-5554 install -r app\build\outputs\apk\release\app-release.apk
#    - 冒烟：冷启动有内容、crash 0、dumpsys package 的 versionName 是新版本号
#    - 验签：apksigner verify --print-certs，证书 SHA-256 必须与上一版一致（否则老用户覆盖不了）
#    另外核对 aapt2 dump badging 的 versionCode 比上一版大

# ④ 全部通过之后，才推送并发布
git push origin main
gh release create v<version> <apk> --title 'v<version>' --notes-file <notes> --latest
# 发布后核验：资产地址 HEAD 200 / Content-Length 与本地一致；releases.atom 首条是新 tag
#（应用内检查更新走 feed，feed 里能看到才算真的发布成功）
```

手机与模拟器同时连着时，`adb` 必须显式 `-s emulator-5554`（或用 `$env:ANDROID_SERIAL`）：
本项目验证一律在模拟器上做，**不要动用户的真机**。

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

`2026-10-07` 实测（`assembleRelease` **1 分 2 秒**（增量）/ **3 分 1 秒**（全量），含 `lintVitalRelease` / `minifyReleaseWithR8` / `optimizeReleaseResources`）：

| 项 | 值 |
| --- | --- |
| 大小 / md5 | `3,334,005 B` / `9bb147ebd31838af3a7066a8278ec7c0` |
| sha256 | `235cf4f306e1dd1823e4bab8a44fdd11a4ed43545ca569c6a5321e2bfd48ffbd` |
| versionName / versionCode | **`1.3.2`**（已发布：[v1.3.2](https://github.com/limao996/xhs-thirdparty/releases/tag/v1.3.2)；上一版 `1.3.1` / `568071`，更早 `1.3.0` / `469201`） |
| minSdk / targetSdk / compileSdk | `26` / `37` / `37` |
| 签名 | v2 scheme（v1/v3 未启用），`CN=ThirdParty XHS Client`，RSA 2048 |
| 权限（合并后） | `INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`REQUEST_INSTALL_PACKAGES`（应用内更新用）+ `USE_BIOMETRIC`、`USE_FINGERPRINT`（biometric 库合入）+ `com.thirdparty.xhs.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（androidx 合入） |
| 权限逐条依据（2026-10-09 审查） | `INTERNET`：所有接口；`ACCESS_NETWORK_STATE`：`hasValidatedNetwork()` 与默认网络回调（快速失败与网络恢复重试）；`WAKE_LOCK`：**播放器在用** —— `VideoPlayer` 调 `setWakeMode(C.WAKE_MODE_LOCAL)`，media3 会拿 `ExoPlayer:WakeLockManager` 的部分唤醒锁；`REQUEST_INSTALL_PACKAGES`：应用内更新要交给系统安装器；`USE_BIOMETRIC` / `USE_FINGERPRINT`：biometric 库合入，指纹应用锁在用；`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`：androidx.core 自带。**结论：没有多余的系统权限** |
| 备份声明 | 三个机制都留，各有覆盖范围：`android:allowBackup="false"`（基础开关，Android 12 起已废弃）、`android:fullBackupContent="false"`（API 26-30）、`android:dataExtractionRules="@xml/data_extraction_rules"`（排除全部内容，API 31+ 实际读它）。**缺任何一个 lint 都会报 `DataExtractionRules`**：2026-10-10 我先按"规则文件是死配置"删过一次，lint 先要 `dataExtractionRules`、补上之后又要 `fullBackupContent`，两轮才把判断纠正过来，最终回到原来的三条 |
| 可调试 | 否（manifest 里没有 `debuggable`） |
| 画中画 / 任务 | `supportsPictureInPicture="true"`、`launchMode="singleTask"`（深链复用同一实例，避免第二个界面/第二个小窗） |
| 明文流量 | `usesCleartextTraffic="true"`：**只为局域网 http WebDAV 放行**；公网地址在应用内会被 `WebDavClient.validate()` 拦掉（Basic 认证会明文过网） |

### 单元测试与 CI

```powershell
gradlew.bat testDebugUnitTest      # 目前只有 net/UpdateCheckerTest（版本比较 + GitHub 应答解析）
```

CI（`.github/workflows/android.yml`）在 push / PR 上跑 `assembleDebug` → `testDebugUnitTest` → **`assembleRelease`**
（release 走 R8 + 资源压缩，才是真正会崩的那条；签名用仓库里的 keystore）。

装到设备后至少确认两件事：冷启动能看到顶栏的 `游客ID：…` 与瀑布流内容（这一步同时证明 R8 压缩没有破坏 AES 包体与 Room 路径），
以及 `adb logcat` 里没有 `FATAL EXCEPTION`。release 与 debug 是两个 applicationId，可以共存；release 首次安装会新建一个游客身份。

## 3. 版本号规则

| 名称 | 规则 | 当前值 |
| --- | --- | --- |
| `appVersionName` | 给人看的版本，按功能批次手动推进 | `1.3.0` |
| `appVersionCode` | `(int)(System.currentTimeMillis()/1000L - versionEpochSeconds)`，`versionEpochSeconds` = 2026-10-01T00:00:00 本地时区的 epoch 秒 | 随时间递增 |
| `CLIENT_VERSION`（buildConfigField） | 与服务端对齐的**协议版本** | `2.6.0` |
| `CLIENT_CHANNEL`（buildConfigField） | 渠道号 | `1333` |

为什么要时间戳：手工维护 `versionCode` 会忘记递增导致无法覆盖安装；时间戳单调递增；减去固定纪元保证
值落在**32 位有符号整数**范围内（Android 要求，超限会安装失败）。

`versionName` 变了就更新 `docs/CHANGELOG.md` 的统计表与 `docs/ai/CONTEXT.md`。

发布留档：`v1.3.2`（`3,372,530 B` / md5 `2a4c92887c25d487ae6191caf3a7ecff` / sha256 `ad21f18d3c45498a7316207f177943c5c886bb45b460f0f0e8d697d1bb80fdba` / versionCode `778804`，2026-10-10；证书 SHA-256 `6cb3580937ffa195ea5ee06df2aca5c56a818e7c39123bcf478e2a93acdb1439…`，与 `v1.3.1` **一致**（老用户可直接覆盖升级）；资产名 `xhs-thirdparty-1.3.2-release.apk`，[下载](https://github.com/limao996/xhs-thirdparty/releases/download/v1.3.2/xhs-thirdparty-1.3.2-release.apk)）；
`v1.3.1`（`3,334,005 B` / md5 `9bb147ebd31838af3a7066a8278ec7c0` / sha256 `235cf4f306e1dd1823e4bab8a44fdd11a4ed43545ca569c6a5321e2bfd48ffbd` / versionCode `568071`，2026-10-07；资产名 `xhs-thirdparty-1.3.1-release.apk`，[下载](https://github.com/limao996/xhs-thirdparty/releases/download/v1.3.1/xhs-thirdparty-1.3.1-release.apk)）；`v1.3.0`（`3,317,161 B` / md5 `0bf90e90feb084d9f7010a490d4b43a5` / sha256 `2a77c42e9a2eca05ae4fa78e15561c27bf0471814aa23744d84bb16df59747f2` / versionCode `469201`，2026-10-06）；`v1.2.1`（`3,306,226 B` / md5 `6d799ca08d1fab281aef854961c70057` / versionCode `326862`）；`v1.2.0`（`3,289,838 B` / md5 `1ea558980a0f9d6085c232d0fc6b0462` / versionCode `321075`）。

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
| okhttp | 5.5.0 | JSON 用内置 `org.json`，无 gson/kotlinx-serialization（测试 classpath 另加 `org.json:json:20260814`） |
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
`tools/out/`。`keystore/` **刻意不入忽略列表**（签名密钥是刻意入库的，见上文签名一节）；
`.gitattributes` 把 `*.jks` / `*.keystore` 标成 binary，避免行尾转换破坏密钥文件。
提交前确认 `git status` 里没有上述内容。
