# 小黄书 · 第三方客户端

![Android](https://img.shields.io/badge/Android-24%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-2026.02.01-4285F4?logo=jetpackcompose&logoColor=white)
![Material 3](https://img.shields.io/badge/Material%203-Expressive-6750A4)
![License](https://img.shields.io/badge/License-MIT-FFC73D)

老司机软件「小黄书」的第三方 Android 客户端，用 Kotlin + Jetpack Compose 从零写的，界面全程没有一行 XML 布局。

装上以后，用你自己的账号浏览推荐流、刷视频、看图、搜作品和作者、看评论、关注作者，把这些内容和观看记录留一份在自己手机上——断网也能翻已缓存的收藏。

> **说明白一点**：这是个学习性质的自用客户端。它只是把你手机上本来就能看的页面换了个壳，不提供内容、不破解付费、也没有任何"破解版"功能；所有请求都用你自己的账号发出去。源码公开，是为了给做 Android 的人当参考。若权利人认为不妥，提 Issue 说明，仓库会立即下架。

## 目录

- [界面长什么样](#界面长什么样)
- [能做的事](#能做的事)
- [安装](#安装)
- [从源码构建](#从源码构建)
- [使用提示](#使用提示)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [版本号怎么定的](#版本号怎么定的)
- [常见问题](#常见问题)
- [参与贡献](#参与贡献)
- [免责声明与许可](#免责声明与许可)

## 界面长什么样

底部三个标签页：**推荐**、**发现**、**我的**。

- **推荐**是全屏短视频流，上下滑切换，视频跟着预加载，滑到哪就播到哪；点标题进详情，单击屏幕收起标题栏和底栏，双击暂停。
- **发现**是分类横滑 + 瀑布流，封面按原图比例排，不会拉长压扁；顶上的分类标签可以直接点左右切换。
- **我的**是账号卡片（关注 / 粉丝 / 作品三个入口都能点）、收藏、最近浏览、备份和设置。

往下点还有：**作品详情页**（图文多图可全屏放大，视频内嵌播放器可全屏、可续播）、**评论区**（一级评论 + 点开对话框看完整嵌套回复）、**作者主页**（作品 / 关注 / 粉丝三个 tab）、**搜索**（作品和作者两个通道，带历史记录）。

界面用的是 Material 3 Expressive：深紫 + 金色的品牌配色，跟着系统深浅色走，Android 12 以上还能从壁纸取色。

## 能做的事

**浏览**

- 推荐流全屏短视频，预加载相邻作品，切过去就起播，不黑屏
- 发现页分类横滑切换，瀑布流按封面真实比例排布
- 搜索作品 / 作者，搜索历史可一键清空
- 详情页：多图画廊 + 全屏查看 + 双指缩放；视频内嵌播放（缓冲进度、拖拽跳转、全屏横竖自适应）
- 评论区一手评论与嵌套回复，按真实总数判断"查看更多"
- 作者主页三个 tab，关注状态在各页面之间同步
- 分享：把剪贴板里的分享口令发给朋友，对方回到应用会问是否跳转

**本地（这部分是作者自己最常用的）**

- 收藏、最近浏览（条数上限可调），都支持一键清空并二次确认
- 断网时已缓存的内容照常翻
- 备份 / 恢复：导出成本地文件，或者备份到自己的 WebDAV（服务器上会固定放在 `xhs/` 子目录，方便找回）
- 图片缓存占用可见，也能一键清掉

**应用本身**

- 应用锁：指纹 / 人脸解锁，防止别人拿你手机乱翻
- 设置：音量键翻页、后台是否继续播、缓冲提示、最近浏览上限
- 深链：`xhstp://note/<id>` 直接打开某个作品

**不做的事**：不写评论、不点赞、不批量下载、不绕过任何付费限制。

## 安装

**要求**：Android 7.0（API 24）及以上。

自己构建出 APK 后安装即可（见下一节），装好后桌面上叫「小黄书」。

- 调试包的名字是「小黄书.debug」，包名也带 `.debug` 后缀，**可以和正式包同时装着**，两边数据互不影响——这是故意的，方便一边用一边改。
- 首次启动要联网，客户端会用一个访客身份登录，你不需要填账号密码。

## 从源码构建

准备两样东西：**JDK 17** 和 **Android SDK**（需要 API 37 平台与对应 build-tools）。

```bash
# 1) 告诉 Gradle 你的 SDK 在哪（这个文件只在本机存在，不会提交）
cp local.properties.example local.properties
#    编辑 local.properties： sdk.dir=D\:\\Android\\Sdk

# 2) 打调试包
./gradlew assembleDebug          # Windows 上用 gradlew.bat assembleDebug

# 3) 装到手机或模拟器
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.thirdparty.xhs.debug
adb shell am start -n com.thirdparty.xhs.debug/com.thirdparty.xhs.MainActivity
```

想要正式包：`./gradlew assembleRelease`（开着 R8 混淆和资源压缩，用仓库里的 `keystore/release.jks` 自签名）。

几个可能踩到的点：

- 仓库路径里有中文时，靠 `gradle.properties` 里的 `android.overridePathCheck=true` 才能构建，别删。
- 第一次构建要下 Gradle 9.8.0 和一堆依赖，一两分钟很正常。
- Windows 上如果你已经装过解压版 Gradle，直接调它会快很多（省掉 wrapper 每次约两分钟的校验）。
- 更细的构建说明、依赖清单和故障排查写在 [docs/BUILD.md](docs/BUILD.md)。

## 使用提示

- **加载不出来先看网络**。某些网络环境下 `app.xiaohuangbook.net` 的域名解析会被污染（解析到 `199.59.148.89`），表现是一直转圈或报错，换个网络通常就好了。
- **剪贴板里留着分享口令时**，启动应用会弹出「检测到分享内容」挡住界面，点「取消」即可，这是应用在问你要不要跳转。
- **数据都在你手机里**。收藏、浏览记录、关注列表存在应用私有目录的 `xhs_local.db` 里；备份文件是你自己指定的位置，不上传任何服务器（用 WebDAV 时传的是你自己的服务器）。
- **换手机/重装**之前记得先做一次备份，恢复入口在同一页。

## 项目结构

```
xhs-thirdparty/
├── app/                      唯一模块
│   └── src/main/
│       ├── java/com/thirdparty/xhs/
│       │   ├── data/         仓库层 + Room 缓存 + 备份
│       │   ├── net/          网络请求、加解密、WebDAV、账号与设备身份
│       │   ├── navigation/   路由
│       │   ├── ui/screens/   各个页面（全是 Compose）
│       │   ├── ui/components/复用组件：瀑布流、播放器、画廊、对话框…
│       │   ├── ui/viewmodel/ 各页状态
│       │   └── ui/theme/     M3 Expressive 主题与设计令牌
│       └── res/              只有图标与基础资源，没有布局 XML
├── docs/                     文档
├── keystore/                 自签名发布密钥
├── tools/                    本机用的验证与资源脚本
└── .github/ · .cursor/       CI、Issue/PR 模板、编辑器规则
```

## 技术栈

| 用在哪 | 用什么 | 版本 |
| --- | --- | --- |
| 语言 | Kotlin（没有 Java 源码） | 2.1.21 |
| 界面 | Jetpack Compose | BOM 2026.02.01 |
| 设计系统 | Material 3 Expressive | material3 1.5.0-alpha29 |
| 构建 | AGP / Gradle / KSP | 9.4.1 / 9.8.0 / 2.1.21-2.0.2 |
| 导航 | navigation-compose | 2.9.5 |
| 本地存储 | Room | 2.8.5 |
| 网络 | OkHttp（JSON 用内置 `org.json`） | 5.1.0 |
| 视频 | media3 ExoPlayer + HLS | 1.11.1 |
| 加密 | JDK 自带的 `javax.crypto`，AES/CBC | JDK 17 |
| 指纹解锁 | androidx.biometric | 1.1.0 |
| SDK | minSdk 24 / targetSdk 37 / compileSdk 37 | — |

material3 故意用 alpha 版（比 Compose BOM 指定的版本高一个台阶）：Expressive 那套主题 API 目前只在 alpha 线上公开，用别的版本编译不过。

## 版本号怎么定的

这套规则吃过亏，所以写得细：

| 名字 | 现在是多少 | 说明 |
| --- | --- | --- |
| `versionName` | `1.1.0` | 给人看的版本，攒够一批功能手动往上走 |
| `versionCode` | 当前秒数 − 2026-10-01 00:00:00 的秒数 | 交给时间戳生成，不用手抄，保证单调递增又不会溢出 32 位 |
| 请求头 `Client-Version` | `2.6.0` | 这是跟服务端对齐的**协议版本**，和 App 版本是两件事，不要跟着一起改 |

## 常见问题

**装了两个图标？**
一个正式包一个调试包，名字差了 `.debug`，包名也不同，可以同时装。不需要就在设置里卸载其中一个。

**一直转圈 / 什么都刷不出来？**
先换网络再怀疑程序：这个域名在部分网络会被 DNS 污染。也可以在设置里看看是不是开了应用锁没解开。

**为什么没有发评论、点赞？**
出于克制：只做"看"和"存"，不碰任何写操作，也不想因为自动化操作给你账号带来麻烦。

**收藏会不会丢？**
数据在本机数据库里，应用升级不会动它；但卸载重装会清空，先备份。

**能拿这个包上架吗？**
不能。仓库里的密钥是自签名的，任何人都能伪造同样签名的包；这个工程是给你自己构建、自己用的。

## 参与贡献

1. 动手前先看 [AGENTS.md](AGENTS.md)——里面写了不能碰的几样东西（依赖版本、签名密钥、协议版本等）。
2. 一次提交只做一件事，提交信息用中文，前缀 `feat:` / `fix:` / `perf:` / `refactor:` / `docs:` / `chore:` / `build:`。
3. 提交前请本地构建通过：`./gradlew assembleDebug`。
4. 改界面的话，请附一张实机截图或 UI 文本，别只说"更好看了"。
5. 不要提交 APK、截图、反编译产物和 `local.properties`（`.gitignore` 已经挡住）。

## 免责声明与许可

- 本项目是**第三方**客户端，与「小黄书」的运营方没有任何关系，也未获其授权或认可。
- 仓库里只有源码和文档：没有内容数据，没有官方安装包，没有反编译产物。
- 不提供内容、不破解付费、不绕过任何访问控制；所有请求都由使用者自己的账号发起。
- 「小黄书」及相关名称、图标风格与内容版权归其各自权利人所有。权利人如认为不妥，请提 Issue，仓库会立即下架。
- 代码以 [MIT](LICENSE) 许可发布；第三方依赖各自遵循其原始许可。

## 更多文档

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、代码地图、数据流、主题与设计令牌、设计取舍 |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | 通信格式、加密参数、接口清单与合规声明 |
| [docs/BUILD.md](docs/BUILD.md) | 环境要求、构建命令、签名、依赖矩阵、排查 |
| [docs/VERIFY.md](docs/VERIFY.md) | 怎么在真机上证明改动真的生效 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 按开发阶段整理的变更记录 |
| [AGENTS.md](AGENTS.md) | 给 AI 编码工具（Copilot / Cursor / Claude Code 等）的规则与仓库地图 |
