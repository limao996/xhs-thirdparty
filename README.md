# 小黄书 第三方客户端

「小黄书」（老司机软件）的第三方 Android 客户端，Kotlin + Jetpack Compose 编写。装好打开就能看，不需要注册、不需要登录：客户端在本地生成一个游客身份去读接口，推荐视频流、发现的图文瀑布流、搜索、作者主页都能用，收藏和浏览记录存在手机本地，也可以备份出去。

这个仓库只有客户端源码和文档，不含任何内容数据，也不提供任何服务器。

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF?logo=kotlin&logoColor=white)
![Material 3](https://img.shields.io/badge/Material%203-Expressive-6750A4)
![License](https://img.shields.io/badge/License-MIT-FFC73D)
[![Release](https://img.shields.io/github/v/release/limao996/xhs-thirdparty?label=release&color=success)](https://github.com/limao996/xhs-thirdparty/releases/latest)

## 功能

底部三个标签页：推荐、发现、我的。

**推荐** —— 全屏视频流，上下滑动切换，单击显隐信息栏、双击播放 / 暂停。视频在列表与详情页之间交接播放，进详情不会从头开始。点信息栏进作品详情；左下角有一条「稍后观看」信息条（显示队列条数，点开队列）。

**发现** —— 顶部三个标签：**发现**（横滑切分类的图文瀑布流，封面按原始比例排，不裁剪不拉伸）、**粉丝圈**、**关注**（本地关注的作者）。点封面进详情，长按卡片弹作品菜单（收藏 / 稍后观看）；右下角两枚浮动按钮：刷新在上、稍后观看在下。

**我的** —— 游客账号信息与 VIP 状态，关注 / 粉丝 / 作品三个数字都可以点进去；我的收藏、最近浏览、我关注的作者、切换游客账号、设置，以及「关于小黄书」和「检查更新」两个入口。

页面还有：

- **作品详情**：图文作品支持多图查看与全屏放大（看图时音量键翻页）；视频作品内嵌播放器，可全屏。右上角「分享」走系统分享面板，分享文本里带着这个作品的口令（`xhstp://note/<id>`）；从别处复制到同款口令回到应用会问你要不要打开。
- **播放器**：双击播放 / 暂停；拖动进度条松手才跳（避免和 250ms 的进度轮询打架）；倍速在播放器「更多」菜单里，0.25x ~ 3x 步进 0.25。
- **小窗（画中画）**：播放器菜单里点「小窗播放」就退出到桌面小窗继续看，控制栏三个按钮：后退 10 秒 / 播放暂停 / 前进 10 秒。小窗按视频自己的比例显示；点系统自带的展开按钮回到详情页并接着播（不重新加载），关掉小窗后回到详情页从原进度继续。小窗里同样遵守「切后台/锁屏暂停」，回前台按原状态续播；锁屏解锁后**不**自动续播（锁屏就是要它停下）。
- **稍后观看**：推荐页左下角的信息条、发现页右下角的浮动按钮、搜索页 / 作者页 / 收藏 / 最近浏览页的右下角按钮，以及列表卡片长按菜单都能进；按加入时间排列，**不提供排序**。
- **评论区**：一级评论列表，嵌套回复用对话框查看。
- **作者页**：头像、简介与关注按钮，下面是这位作者的作品瀑布流（无限翻页）。关注与粉丝列表在「我的」里点数字进去。
- **搜索**：作品和作者两个结果通道，带搜索历史。
- **备份与恢复**：导出成本地文件，或备份到自己的 WebDAV（服务器上固定放在 `xhs/` 子目录），恢复时可选择合并或覆盖。备份包含收藏、最近浏览、关注的作者、稍后观看队列、设置项（主题、最近浏览上限、指纹解锁开关、VIP 到期自动切换）、搜索记录，以及 WebDAV 配置（**含账号与密码**，请注意备份文件的存放位置）；**不含账号凭据**。
- **设置**：外观主题、指纹解锁、最近浏览上限、VIP 到期自动切换、备份与恢复、清除缓存。
- **关于 / 检查更新**：两个各自独立的页面，入口都在「我的」。关于页给版本号与 build 号、包名、客户端协议版本、GitHub 仓库、许可与免责声明；检查更新页进去就自动查一次 GitHub 上的最新发布版本，有新版给出下载页与更新说明，没有正式版 / 被限流 / 断网都会如实写出来。**每次进入应用都会自动查一次**（走 GitHub 的 releases feed，不占 API 额度），只有真的查到新版本才会弹窗：可以直接在应用内下载并安装，也可以去浏览器打开发布页，还能选「以后再说」或「跳过这个版本」。

其他：收藏与浏览记录可以多选批量移除，也可以一键清空（都会先弹确认）；图片缓存、图片内存缓存与其它临时文件在「设置 → 清除缓存」里按类型逐项勾选清理，清理前会列出将清什么、能释放多大 —— 收藏、浏览记录、关注列表是你的数据不是缓存，不会被清掉；最近浏览默认保留 2000 条，可在设置里改成 500 / 1000 / 2000 / 5000 / 10000 条，超出后自动清理最旧的；已看过并缓存下来的内容断网也能翻；深链 `xhstp://note/<id>` 能直接打开对应作品。

只做「看」和「存」，不做发评论、点赞、批量下载，也不读写你自己的账号。

## 已知限制

- **视频不落盘**：只有图片与封面有磁盘缓存（上限 64 MB）；视频是边看边下，退出即释放，所以断网看不了视频，只有看过的图文还能翻。
- **不做评论 / 点赞 / 下载**：只读不写，产品范围如此。
- **检查更新**：查询走 `releases.atom`（网页 feed），**不占 GitHub API 那 60 次/小时/IP 的额度**，所以每次进应用都查也没事；只有 feed 不可用时才回落到 API，那时才可能看到「GitHub 限流」。
- **需要能访问海外的线路**：接口与 GitHub 都在海外，所以没开线路时首屏会**很快**给出「加载失败」而不是一直转圈（应用不会干等超时），并且**线路一连上就自动重试**（不需要手动点重试），检查更新同理。
- **网络环境**：`app.xiaohuangbook.net` 在部分网络下会被 DNS 污染，表现为请求超时；换网络即可，与本应用无关。
- **数据库不静默兜底**：本地库更改结构时必须带真迁移；漏写迁移会直接启动报错（这是刻意选择 —— 宁可报错，也不悄悄清空你的收藏 / 浏览 / 关注 / 队列）。

## 关于游客身份

接口需要 `User-Id` 请求头，所以客户端会自己在本地生成一个随机身份（设备 MAC / IMEI / android_id 四种形式里随机一种），先请求 `v2/app/init` 注册，再用 `v2/user/login-with-guest` 换成 `user_token`。注册这一步才会真正建号，少了它新身份是无效的。

服务端会给新注册的游客账号发一段 VIP 体验时间。客户端在当前账号快到期时换一个新的随机身份继续，所以 VIP 内容可以一直看下去；「我的」页面里的「切换游客账号」就是手动立即换一个。自动切换默认开启，可以在设置里关掉。换号只换本地那串随机身份，失败会退避重试（60 秒起翻倍，上限 30 分钟）。

接口清单、加密参数与身份格式见 [docs/PROTOCOL.md](docs/PROTOCOL.md)，实现细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 安装

要求 Android 8.0（API 26）及以上。

在 [Releases](https://github.com/limao996/xhs-thirdparty/releases/latest) 下载最新的 `xhs-thirdparty-x.y.z-release.apk`（约 3.3 MB），传到手机安装，首次需要允许「安装未知来源应用」。发布页里写了每个包的 md5 / sha256，可以核对。

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

技术栈：Kotlin 2.1.21、Jetpack Compose（BOM 2026.02.01，无 XML 布局）、Material 3 Expressive（material3 固定在 `1.5.0-alpha29`）、Room 2.8.5、OkHttp 5.5.0（请求体 AES/CBC 加密）、media3 ExoPlayer + HLS、androidx.biometric。构建：AGP 9.4.1 / Gradle 9.8.0 / KSP。

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
- 它不提供任何内容。它的机制是自动注册一次性游客身份，去领取服务端发给新游客的体验权限。使用者自行承担使用后果。
- 「小黄书」及相关名称、图标与内容版权归各自权利人所有；权利人如认为不妥，提 Issue，仓库会立即下架。
- 代码以 [MIT](LICENSE) 许可发布，第三方依赖各自遵循其原始许可。
