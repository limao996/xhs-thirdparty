# 小黄书 第三方客户端

「小黄书」（老司机软件）的第三方 Android 客户端，Kotlin + Jetpack Compose 编写。装好打开就能看，不需要注册、不需要登录：客户端在本地生成一个游客身份去读接口，推荐视频流、发现的图文瀑布流、搜索、作者主页都能用，收藏和浏览记录存在手机本地，也可以备份出去。

这个仓库只有客户端源码和文档，不含任何内容数据，也不提供任何服务器。

![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF?logo=kotlin&logoColor=white)
![Material 3](https://img.shields.io/badge/Material%203-Expressive-6750A4)
![License](https://img.shields.io/badge/License-MIT-FFC73D)
[![Release](https://img.shields.io/github/v/release/limao996/xhs-thirdparty?label=release&color=success)](https://github.com/limao996/xhs-thirdparty/releases/latest)

## 功能

底部三个标签页：推荐、发现、我的。

**推荐** —— 全屏视频流，上下滑动切换，单击显隐信息栏、双击播放 / 暂停。视频在列表与详情页之间交接播放，进详情不会从头开始。点信息栏进作品详情。

**发现** —— 顶部横滑切换分类，下面是瀑布流，封面按原始比例排，不裁剪不拉伸。点封面进详情，点作者进作者页。

**我的** —— 游客账号信息与 VIP 状态，关注 / 粉丝 / 作品三个数字都可以点进去；我的收藏、最近浏览、我关注的作者、切换游客账号、清除图片缓存、设置。

页面还有：

- **作品详情**：图文作品支持多图查看与全屏放大（看图时音量键翻页）；视频作品内嵌播放器，可全屏。
- **评论区**：一级评论列表，嵌套回复用对话框查看。
- **作者页**：作品 / 关注 / 粉丝三个 tab，可关注与取消关注。
- **搜索**：作品和作者两个结果通道，带搜索历史。
- **备份与恢复**：导出成本地文件，或备份到自己的 WebDAV（服务器上固定放在 `xhs/` 子目录），恢复时可选择合并或覆盖。备份内容包含收藏、浏览记录、关注列表，不包含账号。
- **设置**：外观主题、指纹解锁、最近浏览上限、VIP 到期自动切换、备份入口、关于。
- **关于**：版本号与 build 号、包名、客户端协议版本、GitHub 仓库与许可；「检查更新」会查 GitHub 上的最新发布版本，有新版给出下载页与更新说明。

其他：收藏与浏览记录可以一键清空（会先弹确认）；最近浏览默认保留 2000 条，可在设置里改成 500 / 1000 / 2000 / 5000 / 10000 条，超出后自动清理最旧的；已看过并缓存下来的内容断网也能翻；深链 `xhstp://note/<id>` 能直接打开对应作品。

只做「看」和「存」，不做发评论、点赞、批量下载，也不读写你自己的账号。

## 关于游客身份

接口需要 `User-Id` 请求头，所以客户端会自己在本地生成一个随机身份（设备 MAC / IMEI / android_id 四种形式里随机一种），先请求 `v2/app/init` 注册，再用 `v2/user/login-with-guest` 换成 `user_token`。注册这一步才会真正建号，少了它新身份是无效的。

服务端会给新注册的游客账号发一段 VIP 体验时间。客户端在当前账号快到期时换一个新的随机身份继续，所以 VIP 内容可以一直看下去；「我的」页面里的「切换游客账号」就是手动立即换一个。自动切换默认开启，可以在设置里关掉。换号只换本地那串随机身份，失败会退避重试（60 秒起翻倍，上限 30 分钟）。

接口清单、加密参数与身份格式见 [docs/PROTOCOL.md](docs/PROTOCOL.md)，实现细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 安装

要求 Android 7.0（API 24）及以上。

在 [Releases](https://github.com/limao996/xhs-thirdparty/releases/latest) 下载最新的 `xhs-thirdparty-x.y.z-release.apk`（约 3.1 MB），传到手机安装，首次需要允许「安装未知来源应用」。发布页里写了每个包的 md5 / sha256，可以核对。

安装包用的是仓库里的自签名密钥（[keystore/release.jks](keystore/)，口令写在 [docs/BUILD.md](docs/BUILD.md)），所以只能这样侧载，不能上架应用商店。

调试包和正式包可以同时安装，桌面上分别叫「小黄书.debug」和「小黄书」，包名分别是 `com.thirdparty.xhs.debug` 和 `com.thirdparty.xhs`，数据互不影响。

## 自己编译

需要 JDK 17 和 Android SDK（`compileSdk 37`）。

```bash
git clone https://github.com/limao996/xhs-thirdparty.git
cd xhs-thirdparty
cp local.properties.example local.properties   # 把 sdk.dir 改成你的 Android SDK 路径
./gradlew assembleDebug                        # Windows 用 gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` 会产出签名好的正式包；签名配置、依赖版本与常见构建问题在 [docs/BUILD.md](docs/BUILD.md)。

第一次启动需要联网，客户端要在那时建游客账号。

## 项目结构

```
xhs-thirdparty/
├── app/          唯一的 Gradle 模块，源码在 app/src/main/java/com/thirdparty/xhs/
├── docs/         架构、协议、构建、验证、更新日志
├── keystore/     自签名发布密钥
├── tools/        本机用的设备验证与图标脚本
└── .github/      CI 与 Issue / PR 模板
```

技术栈：Kotlin 2.1.21、Jetpack Compose（BOM 2026.02.01，无 XML 布局）、Material 3 Expressive（material3 固定在 `1.5.0-alpha29`）、Room 2.8.5、OkHttp 5.1.0（请求体 AES/CBC 加密）、media3 ExoPlayer + HLS、androidx.biometric。构建：AGP 9.4.1 / Gradle 9.8.0 / KSP。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、代码地图、数据流、导航与主题 |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | 请求格式、加密参数、接口清单、游客身份 |
| [docs/BUILD.md](docs/BUILD.md) | 环境要求、构建与签名、依赖矩阵、排查 |
| [docs/VERIFY.md](docs/VERIFY.md) | 在真机 / 模拟器上验证改动的方法 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 变更记录 |
| [AGENTS.md](AGENTS.md) | 给 AI 编码工具的规则、仓库地图与踩坑清单 |

## 说明

- 本项目是第三方客户端，与「小黄书」运营方没有关联，也未获其授权或认可。
- 它不提供内容。它的机制是自动注册一次性游客身份，去领取服务端发给新游客的体验权限，而不是破解付费校验。使用者自行承担使用后果。
- 「小黄书」及相关名称、图标与内容版权归各自权利人所有；权利人如认为不妥，提 Issue，仓库会立即下架。
- 代码以 [MIT](LICENSE) 许可发布，第三方依赖各自遵循其原始许可。
