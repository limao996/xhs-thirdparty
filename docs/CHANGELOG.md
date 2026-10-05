# 更新日志

本项目的 `versionCode` 由时间戳推导（见 [BUILD.md](BUILD.md#版本号规则)），因此这里不按语义化版本发版，
而是按**开发阶段**归类：每个阶段下列出该阶段有实体改变的提交（`feat` / `fix` / `perf` / `refactor` / `build` / `chore` / `docs` / `revert`）。
纯 `docs:` 提交（同步开发日志）只在阶段末尾统一说明，不逐条列出；**阶段五起**，文档与档案的成体系改动单独列出。

条目末尾的反引号内是短 commit hash，可直接 `git show <hash>` 查看。

---

## 阶段一 · 骨架与首轮硬化（2026-10-01，第 1–32 轮）

### 基线

- 项目基线：Compose + MD3 骨架、Room 缓存、加密请求层、推荐/发现/我的三 tab（`d4a3161`）

### 稳定性与正确性

- 图片解码降采样 + 按字节限容 LRU 缓存，消除 OOM 风险；评论支持分页加载更多（`b554052`）
- 加载失败不再静默、不再清空列表，新增错误态与重试（`49a1ed0`）
- 播放器补音频焦点与生命周期感知，退到后台不再继续播放（`08575c6`）
- 搜索失败不再误报"无结果"，补齐搜索初始态与错误重试（`729b634`）
- 评论加载失败不再误显示为"还没有评论"（`9abbd8f`）
- 会话身份失效时自动重登并重试，避免永久空白（`50fe3c1`）
- 修复深色模式启动白屏闪烁，窗口背景与应用主题对齐（`fc5795a`）
- 修复两处 Compose 条件 early-return 导致的状态错位隐患（`c3d91d5`）
- 修复分页重复条目导致瀑布流崩溃（Lazy 重复 key）（`6085e9e`）
- 个人资料加载失败不再静默降级为"游客 0 数据"（`5bc78d3`）
- 修复空态/错误态在 `Column` 中渲染到屏幕外（存在但不可见）的问题（`bddc1af`）
- 最近浏览不再记录未观看的视频（预加载污染）（`e212734`）
- 详情页全屏时返回键先退出全屏；游客切换防并发（`6e4858d`）
- HTTP 错误响应不再被丢进 AES 解密，并纳入网络重试（`edea813`）
- 进度显示不再冻结（`58e3e7f`）

### 功能

- 收藏 / 最近浏览支持一键清空（含二次确认），补齐空态（`a57fb0e`）
- 图片帖支持多图浏览（水平画廊 + n/N 计数）（`07a3249`）
- 我关注的作者支持直接取消关注，补齐空态（`c1ca536`）
- 图片按后端 `image_size` 真实比例渲染；瀑布流按真实封面比例排布替代伪造高度（`165b787`、`ab8b07f`）
- 作者主页补齐错误态与重试（`8c133f6`）；支持下拉刷新（`102112f`）
- 我的页补头像与账号数据（`7feb465`）
- 搜索历史支持一键清空（`5ed5e05`）
- 视频缓冲时显示加载指示器（`9b86b17`）
- 评论支持展示嵌套回复（`f5a2d3a`）
- 瀑布流列表支持下拉刷新（`6cbb4d7`）
- 详情页支持分享（使用后端真实 `share_url`）（`ba077af`）
- 视频播放失败不再静默，新增错误提示与有界自动重试（`4f832a1`）
- 详情页新增全屏图片查看（含双指缩放）（`d4e635e`）
- 我的页新增图片缓存管理（显示占用 + 一键清除）（`8695827`）
- 推荐流新增播放进度条（`58e3e7f`）

### 性能与清理

- 推荐流预加载相邻视频，滑动切换即时起播（`00b8553`）
- 合并 OkHttpClient 并启用图片磁盘缓存（`426a973`）
- 响应系统内存压力，低内存时释放位图缓存（`2b57d6b`）
- 移除推荐流死代码（每次翻页白跑 2 次数据库查询）（`e39dd34`）
- API 层增加有界网络重试（`5856366`）
- 移除四个完全未使用的依赖，APK 减小约 2MB（`6fbbfec`）

### 打磨

- 播放进度条拖动交互（不再被轮询抢值、不在拖动中消失）（`3fecd59`）
- 粉丝圈展示真实内容（此前接口返回的 `note_list` 被整段丢弃）（`97d4390`）
- 关注 tab 空态改用 `EmptyState`，并补底部避让（`02be8fd`）
- 搜索页键盘遮挡内容（edge-to-edge 下 `adjustResize` 失效）（`68dd43d`）

> 同期另有 9 个 `docs:` 提交，记录第 7–32 轮过程与验证方法。

---

## 阶段二 · 账号体系、播放器与备份（2026-10-02，第 33–86 轮）

### 账号与身份（本项目最曲折的一段）

- 移除「粉丝圈」费用标签 —— 经核实该判定是编造的，从未生效（`0fc6058`）
- 停止声称"已切换游客账号" —— 实测该账号根本无法更换（`3271c5c`）
- 修正「粉丝圈」费用标签 —— 真实信号是 note 的 `group_id`（上轮结论有误）（`a8cf5da`）
- 游客账号轮换（从"不可创建"改为在已验证的账号池内轮换）（`cd3f4ba`）
- 改为手动随机切换游客账号 + VIP 状态显示 + VIP 账号扫描（`4201c73`）
- 账号池改为"发现式" + 随机挑选；扫描到 VIP 即停止并登录（`d0693c4`）
- 切换与扫描改为"随机 ID 优先"（不再只依赖固定账号池）（`dac046f`）
- 账号池扩到上万（19,199 个猜测身份）；移除扫描以免消耗 VIP 额度（`75f3f9e`）
- **重大修正 —— 账号是可以创建的**：漏掉了 `v2/app/init`（`1f8db12`）
- 记住当前登录账号 + 支持切换历史账号（`d5f9161`）
- 首次进入自动随机账号 + VIP 到期自动切换开关（`1b459a3`）；该开关默认开启（`41fb386`）
- 评论区"查看更多"判定改用真实总数（`eec70bb`）

### 播放器

- 详情页横屏适配 + 旋转不再重建播放器（`ec3927c`）
- 推荐页透明状态栏 + 修复全屏按钮 + 播放器手势增强（`e638ea7`）
- 详情页内嵌播放器限高 50% + 默认隐藏控制条 + 全屏共用同一播放器（`c9d75a0`）
- 播放器控制条分两行 + 全屏按视频比例定方向 + 分享深链 + 修切换全屏自动暂停（`2ba6dd7`）
- 播放器控制层改为四区布局（顶栏 / 侧边 / 底栏 / 菜单）（`ce199fb`）
- 菜单打开时不再自动隐藏控制层（`a5ee51a`）
- 推荐页状态栏与标题栏连成一体（半透明同色）+ 双击暂停全屏可用（`38edc28`）
- 控制栏精简 / 推荐页续播 / 分享深链 / 收藏多选取消（`1b11931`）

### 交互与页面

- 分享改为剪贴板口令 + 回到应用弹窗询问是否跳转（`ff3213f`）
- 跳转/切页后保留上级界面状态（返回不用重新找作品）（`ba5d847`）
- 详情页作者卡片整卡可点、去掉「主页」按钮、缩小关注按钮（`21e6e8d`）
- 推荐页信息条让位改按真实导航栏 inset 计算（`e89db1b`）
- 详情页二级评论可点击，弹出对话框查看完整回复（`823f698`）
- 推荐页透明导航栏（白图标）+ 修掉付费标签压住搜索图标（`a793d43`）
- 我的页账号卡片的关注/粉丝/作品入口可点击（`2a212fa`）
- 修复「最近浏览」返回后列表被截断（滚动失效）（`d66733d`）
- 发现页 FAB 与底栏间距（`fa5a70c`）
- 关注作者后返回上一页，关注状态不更新（`e062d68`）
- 搜索页空状态指引写反了方位（`890e936`）
- 三处滚动/分页缺陷（我的页无法滚动、加载更多卡死、刷新不回顶）（`4367734`）

### 备份、设置与锁

- 备份与恢复（本地文件 + WebDAV）（`d4a3ffa`）
- WebDAV 增加「测试连接」，并补齐端到端实测（`539d344`）
- 备份页重复按钮 / 状态栏还原 / 切换进历史 / 最近浏览上限可配（`1ee9bbb`）
- 设置界面 / 音量键翻页 / 锁屏闪帧 / 备份压缩 / 缓冲提示 / 确认对话框（`120a7ec`）
- WebDAV 备份固定存放在 `xhs/` 子目录（`563d9f0`）
- 修复设置页三项缺陷 + 备份到文件闪退 + 后台管理仍可见内容（`83d1ac9`）
- 指纹解锁（我的界面开关）+ 推荐页手势改用 `combinedClickable`（`faef1a9`）
- 备份遗漏了四项设置与搜索记录、WebDAV 配置（`a18e42a`）
- VIP 自动切换改为 5 秒轮询（进入软件先判一次）（`55be6aa`）；VIP 轮询改用本地数据判断（`d66733d`）

### 发布与图标

- 正式包（release + R8 优化 + 签名）与 debug 包名区分（`58d072c`）；debug 桌面名称也加 `.debug`（`cb1b373`）
- 更换应用图标（金色开页书）（`56c0618`）
- 图文全屏音量键方向反转、「全屏」查看器其实没全屏、图标背景色（`6831af0`）

> 同期另有 40 余个 `docs:` 提交，逐轮记录需求、验证过程与教训。

---

## 阶段三 · M3 Expressive 迁移与体验打磨（2026-10-03，第 87 轮起）

### 工具链与设计系统

- **破坏性升级**：M3 Expressive + 全量依赖升级到最新，并完成 7 项功能/UI 需求（`ff045ea`）
- Gradle wrapper 改回可移植的 https URL（`8879ec0`）
- 标签配色重做 + 按 M3 Expressive 规范做**可见的** UI 改造（`a90ba25`）
- 结构性重设计（不只是换颜色）+ 恢复 M3 Expressive 动态配色（`82dc564`）
- 按 Material 3 规范优化五个页面（`1987c71`）

### 播放器缓冲与进度

- 播放器优化（弱网/超长/跳转）+ 去掉锁定按钮 + 我的页 UI 收尾（`3f4def7`）
- 缓冲提示覆盖全部播放器并显示缓冲进度 + 缓冲策略分流（`7d08be6`）
- 进度条现在真的显示缓冲进度（`a5faa94`）
- 长视频跳转后一直缓冲 —— 实测定位 + 自愈 + 明确失败（`88afebc`）
- 进度条改用官方 M3E 组件 + 修复缓冲条亮度反了（`9433f39`）
- 缓冲浮层的进度不再是播放进度（`522871c`）
- 进度条改为直接搬官方源码 + 插入缓冲子轨道（`6e50efa`）；随后回归官方样式只给缓冲区间加亮度（`a7643f9`）
- 缓冲浮层进度条对比度 + 控制栏自动隐藏行为（`b60f15b`）
- 粉丝团短片的时长/进度异常 + 内嵌播放器改固定高度（`2486b90`）
- 缓冲浮层显示百分比 + 全屏播放器双指缩放 + 缩放时显示「恢复」（`4b39d67`）
- 内嵌播放器改回 50% 屏高；视频再加 16:9 最小高度（`aa9b711`）
- 消除过期的断点续播位置 + 内嵌播放器最小比例改 16:10（`1adb2a5`）
- 详情页播放器不再循环，播完停止并显示「重播」（`a3e984e`）

### 界面与正确性

- 作品标签在列表页全被判成「图文」—— 用错了字段（`f661bf7`）
- 作者主页标签错误 —— `note_type` 还有 3 和 4，都是视频（`07b2de8`）
- 视频画面不再残留到上级界面 + 发现页 loading 居中（`39757e4`）
- 进入闪烁 / VIP 轮询 5 秒 / 分类居中 / 搜索清除 / 播放交接（`6a22ea4`）
- 推荐页单击同时隐藏标题栏与底栏；详情播放器恢复真实比例；启动不再闪（`c39d4d5`）
- 详情页全屏黑屏 / 播放器默认高度 / 搜索清除重置状态 + 详情页 M3E 打磨（`34d8d7f`）
- 分享文案重做，并讲清「剪贴板口令」机制（`a029b2e`、`84da25b`）

> 同期另有 5 个 `docs:` 提交。

---

## 阶段四 · 播放交接、协议身份与发布准备（2026-10-04，至 `0abfec4`）

### 播放与会话

- 推荐页跳详情页时把 `ExoPlayer` 本身交给详情页（`1a156ab`）
- 网络活动生命周期审计 + 历史账号手动切换不再被 VIP 轮询顶掉（`f7c8f5a`）
- 详情页媒体不再等元数据；嵌入播放器恢复「按片子比例 + 上下限」的尺寸（`caa2236`）
- 手动选的历史账号只在"没有 VIP 窗口"时豁免轮询；播完切全屏不再黑屏（`cda85f9`）
- 详情页播放器进度条补上水平边距（`f74ee80`）
- 推荐页跳详情页偶尔"视频暂停"（`a7f17a4`）
- 图文详情页全屏时状态栏/导航栏保持显示、透明、白色图标（`ee586a6`）
- 图文全屏图片居中 + 嵌入态点图真的全屏；视频全屏顶栏避开摄像头（`6067ec5`）

### 协议与身份收敛

- 去掉历史账号；VIP 改为按请求判断（不再轮询）；顶栏用挖孔 API 避让（`e865d0e`）
- 非挖孔屏不再留状态栏高的黑带；自动切换补上视频/图文的入口（`f99076f`）
- 备份不再包含账号（`a4e32e7`）

### 发现页与刷新

- 发现页分类居中/粉丝圈刷新；分享文案；评论自动加载；图文进度同步（`a758cda`）
- 发现页内容改为按分类左右滑动（分类 pager）（`61b14a0`）
- 点分类标签偶尔切到相邻标签；缓存路径不请求评论 / 请求结果被覆盖（`949aee6`）
- 发现页切换子 tab 不再自动刷新，只保留关注 tab 的重读（`e2c25a6`）
- 网络恢复后自动重试；刷新时清空列表进入 loading（`e25791b`）

### 发布

- 版本名称改为 `1.1.0`（按需推进）；`versionCode` 改为时间戳（自 2026-10-01 起的秒数）（`eb9645f`）
- 取消关注 / 取消收藏都要弹窗确认（详情页、作者页、关注列表两处）（`6f190f5`）
- README 顶部加交接/迁移提示（`0abfec4`）

---

## 阶段五 · 迁移为独立开源仓库（2026-10-04，`bf8489a` 起）

> 前四个阶段的历史来自原工作区（205 个提交，顶点 `0abfec4`）。从本阶段起，仓库作为**独立开源仓库**维护：
> Gradle 工程上提到仓库根；APK、截图、反编译/解包产物、构建缓存、一次性探针脚本一律不入库。

### 仓库结构与文档

- 迁移为独立开源仓库结构：工程上提到根目录、精简非项目产物（`bf8489a`）
- README 改为面向人的项目介绍、删除自绘示意图、统一中文名「小黄书」（`1edf744`）
- README 按 App 介绍重写，并把「游客账号轮换续 VIP」写成正式机制说明（`b9500d7`）
- 档案与文档全面对源码核事实：新增「列表滚动位置」踩坑（GOTCHAS C8）、「内容断言先排除 chrome」（A7）、
  「文档声明必须与实现一致」（E5）；修正 `CONTEXT.md` 的账号门描述与多处错误引用
- 按分类 pager 的实测结论再次修正 GOTCHAS C8 与 `ARCHITECTURE.md` §5：滚动状态按 `resetKey` 分组，
  列表身份位在"同名却是新列表"的场景必须单调递增

### 缺陷修复

- 发现页子 tab（发现/粉丝圈/关注）切换后保留各自的滚动进度；从粉丝圈进作者页再返回不再回到顶部
  （`ui/screens/DiscoverTabScreen.kt` 的子 tab 状态持有器 + `ui/components/XhsWaterfall.kt` 的 `resetKey`）
- 发现页左右滑动切换分类时列表回到顶部；此前滑回已访问过的分类会落回旧偏移，即落在"从没看过顶部的新列表"中间
  ——滚动状态改为按 `resetKey` 分组（`key(resetKey) { rememberLazyStaggeredGridState() }`），
  并新增单调递增的 `DiscoverUiState.feedEpoch` 作为列表身份

---

## 阶段六 · 关于 / 检查更新与开源发布（2026-10-04，`c24836a` 起）

> 从本阶段起仓库有公开远端：<https://github.com/limao996/xhs-thirdparty>（默认分支 `main`）。

### 新功能

- 设置页新增「关于」组：「关于小黄书」（版本 / 包名 / 客户端协议）与「检查更新」（`c24836a`）
- 新增 `ui/screens/AboutScreen.kt` + `ui/viewmodel/AboutViewModel.kt` + `net/UpdateChecker.kt`：
  从 `api.github.com/repos/limao996/xhs-thirdparty/releases/latest` 读最新 tag，与本地版本分段比较（`1.10.0 > 1.9.2` 这类必须正确）；
  有新版给出「打开下载页」与「更新说明」；**没有正式版 / 被限流（403）/ 断网都会如实显示原因**，不假装"已是最新"
- 这一条是全应用**唯一**不经 AES 的请求：明文 JSON、无 `User-Agent` 会被 GitHub 403，
  且必须用独立的 OkHttpClient，否则应答会被图片 CDN 用的 64 MB 磁盘缓存住（GOTCHAS G1）
- `versionName` 1.1.0 → 1.2.0（`CLIENT_VERSION` 2.6.0 / `CLIENT_CHANNEL` 1333 保持不变，它们是协议版本）
- 单元测试：`UpdateCheckerTest` 6 个用例（新版本/同版本/本地更高/无 apk 资产/缺字段/版本比较）；
  JVM 测试里 Android 自带的 `org.json` 是空壳，需 `testImplementation 'org.json:json:20240303'`（GOTCHAS G2）

### 开源发布

- 仓库公开：`gh repo create limao996/xhs-thirdparty --public --source . --remote origin --push`，默认分支 `main`
- 首个正式发布 `v1.2.0`：`xhs-thirdparty-1.2.0-release.apk` = 3,289,838 B，
  md5 `1ea558980a0f9d6085c232d0fc6b0462`，sha256 `4abc616b83faae2b0e41f32b22fd8774ae57f9ee8c9f026636a816c4774bff84`
- GitHub Actions「Android CI」在 push 后通过（5m0s）
- 设备验证两轮（模拟器 emulator-5554）：发布会前 `GitHub 上还没有发布版本`，发布后 `已是最新版本（v1.2.0-debug）`，两次均无崩溃
- 文档同步：README 徽章与安装路径、`docs/BUILD.md` 实测值、`docs/PROTOCOL.md` 明文请求例外、
  `docs/ai/GOTCHAS.md` 新增 G 节、ISSUE 模板占位符改为真实仓库

---

## 阶段七 · 页面拆分与入口调整（2026-10-04，`34e8d3d` 起）

### 修复

- 「关于小黄书」和「检查更新」以前是同一个页面（点哪个都进 `AboutScreen`，区别只是进页面后要不要自动查一次），
  现在拆成两个独立页面：`about`（版本 / 包名 / 客户端协议 / 仓库 / 许可 / 免责声明）与
  `update`（当前版本、进页面自动查一次、重新检查、有新版时的下载页与更新说明）
- 两个入口从「设置 → 关于」组移到「我的 → 其他」；设置页只留偏好项（外观 / 安全 / 内容 / 数据）
- 收敛重复实现：`ui/components/OpenUrl.kt`（打开链接失败必须明确提示，不能静默什么都不发生）、
  `ui/components/ListSection.kt`（关于页与检查更新页共用的分组容器）
- `AboutViewModel` → `UpdateViewModel`：状态只服务检查更新页；文案覆盖 有新版 / 已是最新 / 没有正式版 / 被限流 / 断网
- 设备验证（模拟器 emulator-5554，`adb install -r` + UI 截图 + `uiautomator dump`）：
  我的页出现两个入口、两页标题与内容互不相同、设置页不再有「关于」组，全程无崩溃

---

## 阶段八 · 启动自动检查更新与缓存分类清理（2026-10-04，`cb76ec8` 起）

### 新功能

- **进入软件自动检查更新**：`App.checkUpdateOnLaunch()` 在 `onCreate()` 里后台查一次 GitHub Releases，
  只有真的查到新版本才写 `App.pendingUpdate`；`MainActivity` 收到后弹 `ui/components/UpdateAvailableDialog.kt`
  （标题「发现新版本 vX」，正文是更新说明，按钮「打开下载页」/「以后再说」/「跳过这个版本」）。
  其余情况（已是最新 / 没有正式版 / 限流 / 断网）一律静默，只在「我的 → 检查更新」页如实显示；
  「以后再说」下次启动仍会提醒，「跳过这个版本」持久化到 `settings`（`ignored_update_version`）后不再提醒；
  网络恢复时（`App.bump()`）补查一次。锁屏状态下不弹窗（避免盖在解锁页上）。
- **缓存清理改为按类型多选，并移到设置页**：新增 `data/AppCaches.kt`（`CacheKind`：图片与封面缓存 /
  图片内存缓存 / 其它临时文件，各自显示真实体积）、`ui/viewmodel/CacheViewModel.kt`、
  `ui/screens/CacheScreen.kt`；每项一个勾选框 + 全选 / 全不选 + 底部按钮显示「清除选中（N 项 · X）」，
  确认框列出将清项与合计，清完 Toast 报告实际释放量。入口从「我的」移到「设置 → 数据 → 清除缓存」
  （行内显示当前占用）。

### 修复

- 一项勾选只清一项：原先清「图片与封面缓存」会连带把「图片内存缓存」清掉（`XhsRepository.clearHttpCache()`
  同时做了 `evictAll()` 与 `clearImageMemoryCache()`），确认框列 2 项、实际清 3 项。
  现在 `clearHttpCache()` 只清磁盘，内存位图缓存只在勾选 `IMAGE_MEMORY` 时清
- 删除 `ProfileViewModel` 里已无用的 `cacheBytes` / `clearCache()`，`formatBytes()` 收敛到 `data/AppCaches.kt`

### 设备验证（模拟器 emulator-5554，API 34）

**启动自动检查更新**（临时发一个 `v9.9.9` 正式 release 做验证，测完立刻 `gh release delete --cleanup-tag`，避免污染真实的 latest）：

- 冷启动 dump 到弹窗：`发现新版本 v9.9.9 | 当前版本 v1.2.0-debug | <更新说明> | 只会打开 GitHub 发布页，不会自动下载或安装。 | 打开下载页 | 跳过这个版本 | 以后再说`
- 点「打开下载页」跳出应用（浏览器起来），对话框关闭；下次冷启动仍会弹
- 点「以后再说」对话框关闭，重新冷启动仍会弹（不记住）
- 「我的 → 检查更新」页同版本状态为 `有新版 v9.9.9，当前是 v1.2.0-debug`
- 点「跳过这个版本」后 `shared_prefs/settings.xml` 写入 `ignored_update_version=9.9.9`，再冷启动不再弹（直接回到首页）
- 删掉临时 release 后，同一页面变回 `已是最新版本（v1.2.0-debug）`
- 跳转浏览器单独验证过一次：「关于 → GitHub 仓库」打开 `github.com/limao996/xhs-thirdparty`（同一个 `openUrl`）
- 全程 `crash: 0`

**缓存分类清理**：

- 我的页不再有「清除图片缓存」；设置 → 数据 出现「清除缓存 | 图片 / 临时文件，共 10.1 MB；可逐项勾选」
- 缓存页各项实测（数值随浏览变化，例：2.8 MB / 7.3 MB / 0 B，合计 10.1 MB）；勾 1 项按钮变
  「清除选中（1 项 · 376 KB）」、勾 2 项变「（2 项 · 63.9 MB）」，确认框列出所选项与合计，清完各项归 0 B
- 只勾磁盘缓存后复测：磁盘归 0 B、内存位图缓存仍为 6.9 MB（硬约束 13 生效）
- 「其它临时文件」排除了 `cache/http_cache/` 与 SQLite 锁文件 `xhs_local.db.lck`（设备上它就在 `cache/` 里，0 B）
- 全程 `crash: 0`

---

## 阶段九 · 发布 v1.2.1（2026-10-04）

v1.2.0 的正式包还是旧行为（缓存只有一个「清」按钮、不会自动检查更新），把阶段八的两件事推成正式版本，
让 Release 页与代码一致。

### 变更

- `app/build.gradle:25`：`appVersionName` `1.2.0` → `1.2.1`（`versionCode` 仍由时间戳表达式推导，本次为 `326862`）
- `CLIENT_VERSION`（2.6.0）与 `CLIENT_CHANNEL`（1333）是协议版本，不动

### 产物

| 项 | 值 |
| --- | --- |
| 资产名 | `xhs-thirdparty-1.2.1-release.apk` |
| 大小 / md5 | `3,306,226 B` / `6d799ca08d1fab281aef854961c70057` |
| sha256 | `1c1cbdecf1cfb9bf96b00e3fb992c41c175dafa65ab9d1f69f30e75669e7e860` |
| versionName / versionCode | `1.2.1` / `326862` |
| 签名 | v2 方案，`CN=ThirdParty XHS Client`，与 v1.2.0 同一把密钥（可覆盖安装） |

### 设备验证（模拟器 emulator-5554，API 34，正式包 `com.thirdparty.xhs`，R8 压缩产物）

- 首页 `小黄书 | 游客ID：68994930`，「我的」页 `会员有效期至 10-04 11:50 | VIP用户`
  —— R8 压缩后仍能注册游客身份并拿到体验权限
- 设置页 `数据 | 备份与恢复 | 清除缓存 | 图片 / 临时文件，共 13.3 MB；可逐项勾选`；
  缓存页三项 `4.8 MB` / `7.9 MB` / `612 KB`（合计 `13.3 MB`），说明文案与「清除选中」都在
- 关于页 `v1.2.1（build 326862） | 包名 com.thirdparty.xhs | 客户端协议 Client-Version 2.6.0 · Client-Channel 1333`
- 全程 `crash: 0`

> 检查更新页在这次验证时正好撞上 GitHub 匿名额度用尽（60 次/小时/IP），显示
> `检查失败：GitHub 限流（HTTP 403），过一会儿再试` —— 顺带覆盖了「限流静默降级、不崩、不卡」这条路径。

---

## 阶段十 · 稍后观看队列、长按菜单、画中画与更新节流（2026-10-05，`6b77f16` 起）

### 新功能

- **稍后观看队列**：Room v3 新增 `watch_later` 表（`position` 定序，`2 → 3` 写了真迁移、不清库）；
  队列页支持**长按拖动排序**、移出、点进详情；队列非空且没有小窗时显示入口 —— 推荐页用视频信息栏
  上方的一条**信息条**，发现 / 我的 / 搜索 / 作者页 / 收藏 / 最近浏览 / 详情页用**浮动按钮**
- **长按作品菜单**（所有瀑布流 + 推荐页短视频）：弹出**对话框**，内容是「作品标题 +
  收藏/取消收藏 + 稍后观看/移出稍后观看」，收藏 / 最近浏览另给「多选」入口
- **图文全屏双击缩放**：全屏看图双击放大到 2.5x（落点在手指处），再双击回到 1x
- **画中画小窗**：详情页「更多 → 小窗播放」把 ExoPlayer 交给 PiP 小窗并退出详情页；系统「展开」
  回详情页并**继承同一个播放器**；「关闭」在 `onDestroy` 销毁播放器；控制栏三个 `RemoteAction`
  （播放/暂停、播放顺序、稍后观看队列），「全屏」用系统自带的展开按钮（PiP 最多显示 3 个自定义按钮）
- **启动检查更新改为 12 小时一次**：`settings.update_checked_at` 只记录**成功**检查的时间；未到期直接跳过；
  失败（限流 / 断网）不写时间戳、窗口保持打开，网络恢复后由 `App.bump()` 补查

### 修复

- `FullscreenImageViewer` 原来只处理捏合与拖动，点击被漏掉 → 在同一个 `awaitEachGesture` 里补点击识别
  （时长 + 位移阈值），拖动/捏合会结束双击窗口
- 队列拖动的手势修饰符必须接在行 `clickable` 之后（同一节点）：挂在外层 `Box` 上时行内 `clickable`
  先把事件吃掉，长按永远轮不到拖动

### 设备验证（模拟器 emulator-5554，API 34，debug 包）

- 长按菜单（推荐页 / 发现页）：`小萝莉浴缸嬉戏-会员版 | 收藏 | 稍后观看 | 关闭`
- 队列：`长按可以拖动排序`；用 `input draganddrop` 把第 1 行拖到第 2 行后，顺序由
  `03-26… / 03-13…` 变为 `03-13… / 03-26…`（Room 的 position 同步换了）
- 推荐页信息条：`稍后观看 4 件 · 点这里查看` 在标题栏上方；同屏没有浮动按钮
- 画中画：`dumpsys window` 出现 `pip-dismiss-overlay`，`PictureInPictureParams(aspectRatio=9/16,
  hasSetActions=true)`，截图里视频在小窗中播放、详情页已退出；`am start --activity-reorder-to-front`
  后回到**同一件作品的详情页**
- 双击缩放：图文作品（`noteType=1`）全屏 dump 为 `1/23` 无「恢复」→ 双击后 `恢复 | 1/23` → 再双击回到 `1/23`
- 更新节流：`settings.xml` 的 `update_checked_at` 在一次成功检查后被写入，12 小时内的两次冷启动都没改动它
- 全程 `crash: 0`

> **未设备验证的两条**（如实记）：①系统画中画窗口的「关闭 X / 展开」不吃 `adb shell input tap`
> （系统覆盖层忽略注入触摸），所以「点 X 销毁播放器」只做了代码路径确认，展开路径改用
> `am start --activity-reorder-to-front` 触发并通过；②「12 小时到期后重新检查并刷新时间戳」这一条，
> 当晚 GitHub 匿名额度已用尽（403），只观察到「失败不写时间戳、窗口保持打开」这半边。

### 阶段十的同日反馈修正（6 项，用户实测后提出）

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 推荐页的稍后观看要放在信息栏**外面** | 信息条不再塞进信息栏那个带 `Scrim.strong` 背景、带 `padding(Spacing.l)` 的 Column，而是与信息栏**并列**的另一段（自己的圆角背景 + 之间的间距） | 截图：信息条是独立一条，和信息栏之间有可见缝隙 |
| 2 | 画中画**不用再与观看队列关联** | 删掉第 3 个 `RemoteAction`（队列）、`ACTION_QUEUE`、`pendingOpenQueue`、`EXTRA_OPEN_WATCH_LATER` 与 `ic_pip_queue.xml`；控制栏只剩 播放/暂停 + 播放顺序 | 截图：小窗控制栏只剩 2 个按钮（⏸ / ⇄） |
| 3 | 菜单弹窗要用 AlertDialog（有遮罩、有动画） | 作品菜单本来就是 `AlertDialog`（截图可见遮罩）；**播放器**的「更多」下拉这次也换成 `AlertDialog`（微调 / 倍速 / 小窗播放） | 截图：两个菜单都是带遮罩的对话框 |
| 4 | 小窗模式的入口要放在**视频播放器的菜单**里，且播放器菜单改为对话框 | `MediaPlayer` 新增 `onEnterPip`，详情页顶栏的「更多」整个删掉，入口落到播放器菜单；新增 `PlayerMenuRow` | `DumpUi`：`微调：±1 秒 \| 0.5 x … 1.0 x ✓ … \| 小窗播放 \| 关闭` |
| 5 | 图文全屏双击缩放要**平滑** | `scale`/`offset` 仍是手势的真理源，渲染改用 `animateFloatAsState` / `animateOffsetAsState`：双击与「恢复」走 `tween(240ms)`，捏合与拖动走 `snap()`（必须跟手） | 见下面的测量（把时长临时调到 12s 取证后已还原为 240ms） |
| 6 | 不说就不推送 GitHub | 本阶段只做本地提交，不 `git push` | 见提交记录（本地 `HEAD` 领先远端） |

第 5 项的取证方式（值得记一笔）：模拟器上抓不到动画中间帧（`screencap` 单次耗时接近秒级），
于是临时把 `ZOOM_ANIM_MS` 调到 12000 并在渲染值上挂一行 `Log.d`，`logcat` 收到 **528** 个中间值
（`1.0 → 1.0000061 → … → 2.5`）证明它是真的插值；随后**移除探针、把时长还原成 240ms**。
第一次测量时只看到 `1.0 → 2.5` 两个值 —— 原因是模拟器 `animator_duration_scale = 0`
（系统动画被关掉，Compose 的动画会瞬间完成），把该设置改成 1 后才量到中间值，测完已还原为 0。

### 阶段十一 · 小窗回归系统画中画、补系统触感、修队列拖动（同日第二轮反馈）

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 「小窗就是画中画，不用再搞控制栏，直接实现画中画该有的功能」 | 删掉自己挂的 `RemoteAction`（播放/暂停、播放顺序）与整套广播接收（`registerReceiver` / `ACTION_*` / `EXTRA_OPEN_WATCH_LATER` / `ic_pip_*.xml`）。`PipController` 现在只做两件事：**播放器所有权交接** + **画面比例**；窗口上的关闭 / 展开以及系统愿意给的播放控制都由系统提供 | 截图 `pip2-2-controls.png`：小窗里画面正常、**没有任何应用自绘的控制按钮**；`buildParams` 里不再 `setActions` |
| 2 | 「优化全部交互，该振动的振动（用系统 API）」 | 新增 `ui/components/Haptics.kt`：`LocalHapticFeedback` + `HapticFeedbackType`（**不用 `Vibrator`**，因此尊重系统触感开关、不需要 `VIBRATE` 权限）。接入点：长按弹菜单（瀑布流 / 推荐短视频）、作品对话框三个动作、队列拖动开始 / 落位 / 移出、稍后观看浮动按钮与信息条、收藏与稍后观看切换、进入小窗、图文双击缩放、双击播放暂停、底部三 tab、分类 chip、发现页子 tab、播放器（播放/暂停、全屏、菜单） | `dumpsys vibrator_manager` 出现 `opPkg=com.thirdparty.xhs.debug … effect=HEAVY_CLICK … Usage=TOUCH`（长按那一下，来自系统触感通道）。`scale: 0.00` 是模拟器没有可用触感硬件 / 关掉了触感，调用本身已生效 |
| 3 | 「队列长按拖动没法从 1 到 3，只能到 0 或 2」 | 拖动期间**不再边拖边换位**：只记 `dragFrom`（拖谁）/ `dragOffset`（手指移了多少）/ `dragTarget`（会落在第几行），其余行用 `translationY` 让位，松手才整段写回 | 把第 1 行拖到第 4 行：拖动前首行是 `【新娘】刚结婚的新娘这身材这叫声真刺激`，拖动后首行变成 `抖音风 抖音18+ (125)`，那一行显示 `4. @绅士仓库`；DB 位置重排为 `0 1 2 3` |

第 3 项的根因（值得记一笔）：旧实现"手指每越过半行就和相邻行换位"，换位会让这一行的**基准位置**
立刻跳一行，而手势位移是在节点的局部坐标里累加的 —— 基准一跳，累加量正好被抵消，于是拖多远都卡在
相邻一格。现在拖动中不动布局，位移就不会被抵消，一次能拖到任意位置。

### 阶段十二 · 对话框回归原生、两个浮动按钮共存、小窗补前进后退（同日第三轮反馈）

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 「很多对话框依然没有遮罩和动画」→ 追加「不要自己绘制，用原生的 AlertDialog」 | 先按"自绘遮罩 + 自绘入场动画"做了一版统一外壳，被用户否决；最终做法是**全应用统一用 `material3.AlertDialog`**，不自定义任何对话框外壳（25 处对话框调用全部是原生组件）。顺带实测了遮罩：长按对话框打开时背景亮度 `239 → 96`（约 60% 压暗），遮罩本来就在；观感问题主要是入场动画受系统"动画时长比例"影响 | 亮度实测 `dim-1-nodialog.png` / `dim-2-dialog.png`；`DumpUi`：对话框内容 `表妹约的炮友去打野炮… \| 收藏 \| 稍后观看 \| 关闭` |
| 2 | 「稍后观看浮动按钮替代了刷新浮动按钮，它俩要共存」 | 新增 `ui/components/CornerFabStack.kt`：右下角竖排 —— 上面是**小号刷新按钮**（次要色），下面是**扩展的稍后观看按钮**（带件数），间距 `Spacing.m`；队列为空时只剩刷新，画中画时整组隐藏。发现页改用这个组合（关注子 tab 不给刷新按钮），外壳不再为发现页重复画一个稍后观看按钮 | 截图 `fab-1-discover.png`：两个按钮同屏、上下排列不重叠；`DumpUi` 同时有 `content-desc="刷新"` 与 `text="稍后观看 4"` |
| 3 | 「画中画要有进度前进/后退、播放/暂停」 | 小窗控制栏补回三个标准 `RemoteAction`：**后退 10 秒 / 播放暂停 / 前进 10 秒**（画中画最多显示 3 个，正好放满）；新增 `res/drawable/ic_pip_{rewind,pause,play,forward}.xml`（Material 标准图形），`MainActivity` 重新注册接收者，动作后刷新图标 | `logcat`（WindowManagerShell）：`PictureInPictureParams( aspectRatio=60/107 … hasSetActions=true …)` —— actions 已随窗口生效；截图 `fab-3-pip.png` 小窗里视频正常播放 |

> 说明：系统小窗上那三个按钮的**触摸**在模拟器里时好时坏（系统覆盖层会忽略注入触摸），
> 所以第 3 项验证到"actions 已注册 + 接收者代码路径"为止；此前一版构建里曾观测到按钮点击
> 触发参数刷新（`onTaskInfoChanged`），链路本身是通的。

### 阶段十三 · 小窗播放状态、队列项观感、粉丝圈长按、触感补齐（同日第四轮反馈）

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 小窗没继承播放状态；播完按钮不更新、点了也不重播 | `PipController.start(..., playIntent)`：交出去那一刻"在播/暂停"显式接着；控制栏图标改为按**真实状态**三选一（`STATE_ENDED` → 重播、`playWhenReady` → 暂停、其余 → 播放）；接收者里播完再点走 `seekTo(0) + play()`；挂 `Player.Listener` 在播放状态变化时刷新按钮 | `dumpsys audio`：小窗会话期间本应用 `AudioTrack(USAGE_MEDIA/CONTENT_TYPE_MOVIE)` 有 `event:started`（在播）；图标/重播属代码路径 + 上面这条状态来源 |
| 2 | 队列项作者名前的序号去掉 | `QueueRow` 去掉 `"$position. "`，作者名只留 `@昵称`（序号不再显示，顺序由拖动本身表达） | `DumpUi`：`… \| VIP \| @老司机 \| 调教 \| 免费 \| @欲临君 \| …`；断言 `\d+\. @` 为 **False** |
| 3 | 播放器菜单对话框列表要能滚动 | `MediaPlayer` 菜单正文包 `verticalScroll` + `heightIn(max = 360.dp)`，倍速/微调/小窗播放共 8 行在小屏上也能滚 | 截图 `v3-3-player-menu.png`；`DumpUi`：`微调：±1 秒 \| 0.5 x … 2.0 x \| 小窗播放 \| 关闭` |
| 4 | 粉丝圈的作品也要长按菜单 | `FanGroupNoteCard` 由 `Surface(onClick)` 改为 `combinedClickable`，`FanGroupTab` 里接 `rememberNoteFlags/rememberNoteActions` 并渲染 `NoteActionDialog` | 长按作品缩略图：`想看我露出吗 深田咏美【娱乐篇】 \| 收藏 \| 稍后观看 \| 关闭` |
| 5 | 触感很多该用的没用、有的不合理 | `Haptics.tick()` 从 `TextHandleMove`（文本光标用的）改成 **`ContextClick`**，新增 `segment()`（`SegmentTick`，滑视频/翻图片）；补上：详情页 分享/收藏/全屏、推荐页换视频、图片查看器翻页、我的页每个入口、作品对话框动作、队列行点击、清空队列 | `dumpsys vibrator_manager`：紧随点击出现 `opPkg=com.thirdparty.xhs.debug … -> TICK`（长时间对比前后两次采样） |
| 6 | 我的界面不要稍后观看浮动按钮 | 外壳（`HomeScreen`）不再渲染稍后观看 FAB；入口归属：推荐页 = 信息条、发现页 = `CornerFabStack`，我的页 = 无 | `DumpUi`（我的页）：整页无 `稍后观看` 文本 |
| 7 | 队列拖动松手后会闪 | 落库后先按**刚写回的顺序**渲染（`committed` 本地顺序），等 Room 读回的顺序对上再撤掉；否则会先按旧顺序画一帧、再跳成新顺序 | 代码路径 + 说明；顺序正确性在阶段十一已实测（1 → 4 格） |
| 8 | 队列项要有作品标签 | `QueueRow` 用与瀑布流同一个 `FeeBadge(item, compact = true)`：图文 / 粉丝圈 / VIP / 免费 | `DumpUi`：`骚妈妈 \| 免费 \| @玉凤妈妈`、`抖音风 抖音18+ (125) \| VIP \| @老司机` |

> 顺带修掉一个**验证工具**的坑：`tools/verify.ps1` 的 `DumpUi` 过去是
> `uiautomator dump …; cat d.xml`，dump 偶发失败（`Broken pipe`）时 `cat` 会读回**上一次的旧文件**，
> 于是"界面明明变了、dump 一直返回同一份内容"。现在先 `rm -f` 再 dump（详见 GOTCHAS I1）。

### 阶段十五 · 队列拖动自动滚动、倍速拖动条、小窗播放权、粉丝圈关注按钮等六项

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 队列拖到屏幕外的行时没有自动滚动 | 记录手指在**列表视口**里的纵坐标，靠一个按帧跑的循环在上下 96dp 边缘区内滚动（步长随接近程度衰减，最大 16dp/帧）；滚动量补回 `dragOffset`，否则落点不跟手指走 | 代码 + 机制；一次拖到任意位置的能力在阶段十一已实测 |
| 2 | 播放器倍速改为拖动条 0.25x~3x | 删掉 6 个固定档位，改 `Slider(valueRange = 0.25f..3f, steps = 10)`（步进 0.25），标题行显示当前倍速、点一下回到 1x；底栏倍速角标跟着走 | 菜单 dump：`微调：±1 秒 \| 倍速 1x \| 小窗播放 \| 关闭`（原来的 `0.5 x / 0.75 x / …` 列表已消失） |
| 3 | 切换小窗后视频停住，后台还在出声 | 推荐流的播放器在小窗期间要停 —— 但**必须放过小窗那一台**（从推荐页进详情再进小窗的是同一个播放器实例，无差别 `pause()` 会把小窗一起按停）。判断归属用 `PipController.isHandedOver` | 实测到的症状正是"小窗那一路 `state:paused` 而信息流那一路 `state:started`"（`dumpsys audio`）；本次改动后重新进小窗的复测落在图文作品上，未取到小窗态的新数据（见下） |
| 4 | 粉丝圈作者卡片的「去看看」改成关注按钮；点卡片进作者主页 | 「去看看」→ **`+ 关注` / `已关注`**（本地关注表 + `followVersion` 驱动），整行 `clickable` 进作者主页 | 粉丝圈 dump：`美少女精选 \| 共 642 个作品 \| + 关注 \| …`，且 `去看看` 已消失 |
| 5 | 还有很多交互遗漏触感 | 补：作品卡片点击（瀑布流，覆盖发现/搜索/作者/收藏/最近浏览）、设置页各行（外观 / 历史上限 / 备份 / 清除缓存）、关于页各行、缓存页勾选与「清除选中」、作者页关注按钮、粉丝圈关注按钮、队列行与清空 | 代码 + `dumpsys vibrator_manager` 的 `TICK` 记录（阶段十三已建立该验证手段） |
| 6 | 该有波纹的交互要有波纹 | 推荐页**信息条**恢复波纹（整屏视频那层仍不铺，避免点一下闪整屏）；粉丝圈**作者卡片**（整行 `clickable`，默认波纹）与**作品卡片**（去掉 `indication = null`） | 代码 + 截图 |

> 阶段十五的复测说明（如实记）：第 3 项重新进小窗的复测脚本这次点进的是**图文**作品
> （`从零开始的异世界-蕾姆篇 1`），图文没有播放器菜单，所以没取到"小窗里在播"的新数据；
> 改动本身是播放器归属判断（`isHandedOver`），与详情页销毁时不 release 用的是同一条规则。

---

### 阶段十六 · 信息条波纹、队列拖动坐标系重做、触感逐处补齐

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 推荐页信息条的波纹不明显 | 信息条改用系统的 M3 ripple 并**显式给浅色**：`combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(color = Scrim.onMedia), …)` —— 默认波纹取 `onSurface`，压在暗色信息栏上几乎看不见 | 代码 + 截图（波纹只在按压瞬间可见，静态截图取不到中间帧） |
| 2 | 队列还是没有自动滚动 → 否则改成"一根手指拖、一根手指滑" | 拖动**只用一个坐标系**（内容坐标 = 视口坐标 + 滚动量），手指位置按 `viewIndex*rowH + 行内偏移 − scrollState.value` 反推：①边缘自动滚动照旧（步长随接近程度衰减）；②列表被任何方式滚动时，落点与位移都自动跟着变，不再需要"补正"。另外被拖行的手势只认自己那个指针，`verticalScroll` 是它的父节点，所以**第二根手指的滑动天然就能滚列表** | 代码 + 标题文案改为「长按拖动排序 · 拖到边缘会自动滚动」 |
| 3 | 还有很多交互没有触感（逐条点名） | 补上：详情页**关注**按钮（已关注→reject / 关注→confirm）、详情页**内嵌图集**点开、推荐页**信息条**与整屏点击、发现页**粉丝圈作品卡片**、**标题栏搜索按钮**、**搜索页**全部组件（返回/清除/换类型/历史项/清空历史/用户行/提交搜索）、**我的页切换游客账号**、**稍后观看清空**按钮、播放器**快退/快进/重播/播放暂停/全屏/菜单** | `dumpsys vibrator_manager` 的 `TICK` 计数逐次递增：搜索页切类型 `29→30`、粉丝圈关注 `32→33`、粉丝圈作品卡片 `33→34`（按钮同时变成「已关注」） |

> 阶段十六的说明：波纹只在按压瞬间存在，静态截图抓不到，所以第 1 项只能给代码路径；
> 触感用"记录条数递增"取证（`dumpsys vibrator_manager` 里 `opPkg=com.thirdparty.xhs.debug`
> 的 `effect=CLICK/TICK` 记录），这是目前唯一可靠的旁证 —— 模拟器没有触感硬件，`scale` 恒为 0。

---

### 阶段十七 · 队列排序改成上下按钮、触感全面补齐

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 队列排序依然不对 → 去掉拖动，改成竖直排列的小号上下按钮 | **删除整套拖动排序**（手势、边缘自动滚动、双指滚动补偿、`committed` 之外的拖动状态），改为每行左侧序号 + 右侧**竖直排列的小号上移/下移按钮**（两端自动禁用），点一次与相邻行交换并立刻写库 | 队列页 dump：`用右侧上下按钮调整顺序 \| 1 \| 骚妈妈 \| 免费 \| @玉凤妈妈 \| 2 \| 抖音风 抖音18+ (125) \| …`；点第 1 行「下移」后变为 `1 \| 抖音风… \| 2 \| 骚妈妈`，`TICK` 计数 `34 → 36`（按钮触感） |
| 2 | 播放器进度条与倍速拖动条缺触感 | `BufferedSlider` 增加可选 `haptics` 参数：拖动时按 5% 一档节流给 `segment()`（不做节流会一次拖动连发几十下）；倍速 `Slider` 同样加触感 | 代码；`TICK` 计数只匹配 `effect=CLICK/TICK`，而 `SegmentTick` 是另一种 effect 名，所以这一项没能用计数旁证（如实记） |
| 3 | 详情页作者卡片缺触感 | 作者整行（`ListItem` 的 `clickable`）→ `haptics.tick()`（关注按钮上一轮已补） | 代码 |
| 4 | 详情页二级评论缺触感 | `CommentRow` 内部的 `rememberHaptics()`：评论预览块与「共 N 条回复，点击查看」都补 `tick()`；评论行打开回复、评论加载失败「重试」也补上 | 代码 |
| 5 | 全面审查，所有交互都要有触感 | ①新增 `Haptics.click{} / confirmClick{} / rejectClick{}`（**成员函数**，调用方只要有 `haptics` 就无需 import）与 `Modifier.hapticClickable`；②**在共享组件里一次性覆盖多处的**：`ConfirmActionDialog`（7 个页面的确认框）、`UpdateAvailableDialog`、`CommentRepliesDialog`、`NoteActionDialog`（动作行 + 关闭）、`EmptyState`（重试）、`FollowedAuthorRow`（整行 + 已关注）、`BiometricLock`（解锁）、`ResetZoomButton`、`MediaPlayer`（播放器菜单每行、关闭、快退/快进/重播、进度条）；③各页面的**返回**按钮与其余可点元素逐处补齐 | 代码 + `dumpsys vibrator_manager` 计数（每次补的对象都有记录） |

> 阶段十七如实说明：第 2 项（拖动条触感）在模拟器上只做了代码确认 ——
> `TICks` 计数器匹配的是 `effect=CLICK/TICK`，`SegmentTick` 是另一种 effect 名，计数看不到它；
> 想验证得改计数器或换真机。另外这一轮之后仍有个别页面级对话框按钮（备份 / 更新 / 我的的确认框）
> 是**通过共享组件的调用方**间接覆盖的，若后续新增对话框请直接用 `haptics.click {}` 包装。

---

### 阶段十八 · 取消关注确认、系统分享、关注按钮统一尺寸、返回不加触感

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 粉丝圈的关注按钮取消关注缺弹窗确认 | 粉丝圈作者卡片：点「已关注」先弹 `ConfirmActionDialog`（「取消关注？」/「将不再关注「作者」。」），确认后才真取消；关注仍然直接生效 | 实机 dump：`取消关注？ \| 将不再关注「成人漫画」。 \| 取消关注 \| 取消`（`HAS_CONFIRM: True`），截图 `v7-1-unfollow-confirm` |
| 2 | 详情页分享改为系统 API 分享文本 | `shareNote()` 从"写剪贴板 + Toast"改为 `Intent.ACTION_SEND` + `text/plain` + `createChooser(…, "分享到")`（带 `FLAG_ACTIVITY_NEW_TASK`；无目标时 Toast「没有可用的分享目标」）。分享文本仍是 `ShareText` 的完整文案，口令仍在其中（对方粘贴回应用照样能被识别） | 实机：点详情页「分享」后 `mCurrentFocus=com.android.intentresolver/.ChooserActivity`（系统分享面板），`crash: 0` |
| 3 | 详情页 / 关注页 / 关注 tab 的关注按钮太大 | 新增全局组件 `ui/components/FollowPill.kt`（对齐粉丝圈那个的尺寸与配色：`labelLarge` + `Spacing.m/s` 内边距 + `shapes.small`），详情页作者行、`FollowedAuthorRow`（关注页与关注 tab）、粉丝圈作者卡片四处统一改用同一实现 | 粉丝圈关注按钮实测 `110x53`；四处同组件，尺寸随之统一 |
| 4 | 详情页图文全屏返回不该有触感 | 去掉 `FullscreenImageViewer` 关闭按钮的触感（返回类操作系统本身有反馈），其余"动作类"按钮的触感保留 | 代码 |

---

### 阶段十九 · 队列不再排序、拖动条按下即触感、信息条单击立刻跳转

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 队列去掉序号与上下按钮，不提供排序 | 队列页彻底去掉排序：删掉序号、上移/下移按钮、`committed` 本地顺序与本轮残留的拖动相关导入；列表按加入时间渲染，标题改为「按加入时间排列 · 在瀑布流或推荐页长按作品可加入」 | 实机队列 dump：`按加入时间排列 · 在瀑布流或推荐页长按作品可加入 \| 抖音风 抖音18+ (125) \| VIP \| @老司机 \| 骚妈妈 \| …`；`HAS_MOVE_BUTTONS: False`、`HAS_INDEX_DIGITS: False`，截图 `v8-1-queue-final` |
| 2 | 进度条触感改为按下触发 | `BufferedSlider` 去掉"拖动中每 5% 一档 segment"，改用新增的 `Modifier.pressHaptic(haptics)`（在 `PointerEventPass.Initial` 观察 `Press` 触发一次 `tick()`，不消费事件，因此不影响控件自身手势） | 代码 + 机制；模拟器上"按下瞬间"的计数未单独取证（如实记） |
| 3 | 倍速拖动条缺少触感 | 倍速 `Slider` 同样接上 `pressHaptic(haptics)` | 代码 + 机制 |
| 4 | 信息栏单击要等波纹/判定结束才跳转 | 信息条改为**普通 `clickable`**（保留 M3 ripple 与 `Scrim.onMedia` 配色），把 `onDoubleClick` 从信息条上移除 —— `combinedClickable` 带双击时单击必须等双击判定窗口（约 300ms）才触发，这就是"点了半天才跳"的来源；双击暂停仍由上面那层视频画面负责 | 实机：点信息条后 **0.9 秒**再次 dump 已在详情页（`明星淫梦：技师王鸥 \| VIP \| 490 \| 221 \| 4`） |
| 5 | 推荐页标题栏搜索按钮缺触感 | 推荐页的标题栏是**另写**的（不是 `HomeHeader`，所以上一轮补漏了），给它补上 `haptics.tick()` | 代码；该按钮在 `HomeScreen` 推荐页分支内（`immersive && feedInfoVisible` 那一段） |
| 6 | 设置页缺触感 | 补齐：指纹解锁开关行与 `Switch`、VIP 自动切换行与 `Switch`、外观主题对话框的 `RadioButton` 与每一行、历史上限对话框的 `RadioButton` 与每一行、两个对话框的「关闭」；禁用的开关不给触感（与"不能兑现就别反馈"一致） | 实机：设置页点「最近浏览上限」TICK `40 → 41` |

---

### 阶段二十 · 分享不再被自己的剪贴板口令触发、分享标题改为面向用户的文案

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 分享不要触发自己刚分享出去的剪贴板口令 | `ShareText` 增加 `markSelfShared(noteId, text)` / `isSelfShared(noteId, clipboard)`（进程内记录一对）；`shareNote()` 在拉起分享面板**之前**登记；`MainActivity.checkClipboardForShareLink()` 读到与登记一致的 (id, 文案) 时直接标记成「已看过」并返回，不再弹"打开这条笔记"。另外用 `Intent.EXTRA_EXCLUDE_COMPONENTS` 把**自己**从分享面板里排除 | 实机：在系统分享面板点「Copy to clipboard」后再回到应用，**没有**出现打开笔记的提示（`HAS_PROMPT: False`）；面板目标列表里不再出现 `小黄书.debug`（`TARGETS_APP_DEBUG_PRESENT: False`），截图 `v9-2-after-copy-return` |
| 2 | 分享标题应起一个合适的、给用户看的 | `EXTRA_TITLE` 从"作品标题"改为固定文案 **「来自「小黄书」的分享」** —— 该字段只在系统分享面板的预览里显示、**不会发给目标应用**，放作品标题（常常不体面）没有意义；真正分享出去的文本仍是 `ShareText` 的完整文案（含口令） | 实机分享面板 dump：`Sharing text \| 来自「小黄书」的分享 \| 【图文】【图文CG】… \| No recommended people to share with`，截图 `v9-1-chooser` |

---

### 阶段二十一 · 备份页触感、清缓存改「反选」、图文画廊触感

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 备份与恢复界面缺少触感 | 该页全部按钮接上触感：本地「备份到文件 / 从文件恢复」、WebDAV「保存配置 / 测试连接 / 上传备份 / 从云端恢复」、恢复方式切换按钮，以及两个确认框的「上传 / 恢复 / 取消」（确认用 `confirmClick`、取消用 `click`）；返回按钮按规则不给触感 | 实机时间戳取证：`10-05 16:25:02 / 16:25:06 / 16:25:11 / 16:25:15 / 16:25:19` 各有一条本应用 `TICK` 记录，与备份页那几次点击对齐（计数法不可用，见 GOTCHAS I2） |
| 2 | 清缓存页全选/全不选改为反选（并补触感） | `CacheViewModel` 新增 `invertSelection()`（已勾的取消、未勾的勾上）；页面把两个按钮换成一个「反选」，并带 `tick()` | 实机：页面文本里只有 `反选`、没有 `全不选`（`HAS_INVERT: True` / `HAS_SELECTALL: False`）；点一下 `已选 0 项 → 已选 3 项`，且触感记录时间戳落在该次点击 |
| 3 | 详情页图文的触感并没有改善 | 触感挪到 **`ImageGallery` 组件内部**（任何用到它的地方都一致）：①点某张图 → `tick()`；②横向翻页 → `segment()`（首帧是恢复现场，不算翻页，不触发）；同时删掉 `DetailScreen` 里重复的那一次 `tick()`，避免一次点击响两下 | 实机：详情页点图后本应用多出一条 `TICK`（计数 `42 → 43`，全屏查看器随之打开）；翻页的 `SegmentTick` 未单独取到时间戳（如实记） |

---

### 阶段二十二 · 检查更新按钮触感、播放器双击触感、小窗关闭后不再后台出声

| # | 反馈 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 检查更新界面的「检查更新」按钮缺少触感 | `UpdateScreen` 接上触感：底部检查按钮（`检查更新` / `重新检查`）、`NewerBanner` 的「打开下载页 / 更新说明」、更新说明对话框的「打开下载页 / 关闭」 | 实机：点「重新检查」→ 触感记录 `16:44:19.273 TICK`（点击时刻 16:44:19，同一秒） |
| 2 | 详情页视频播放器双击暂停缺少触感 | `MediaPlayer` 视频画面的 `onDoubleTap` 分支补 `haptics.tick()`（原来只切播放状态） | 实机（视频作品 2010）：进详情 `dumpsys audio` 为 `started=1 paused=0`，双击后变 `started=0 paused=1`，同时新增触感记录 `16:41:30.117 TICK` |
| 3 | 小窗没有暂停时，关闭后仍在后台播放 | 之前只在 `onDestroy` 收尾，但**关闭小窗时系统不保证销毁 Activity**（实测只走 `onStop`），看不见的 ExoPlayer 于是继续出声。现在 `onStop` 也收一次尾：`!isChangingConfigurations && !isInPictureInPictureMode && !PipController.inPip.value && hasSession()` → 清掉「待打开的详情」并 `closeAndRelease()`；展开回详情页不走 `onStop`，不会误杀 | 代码路径 + 三条守卫；「关闭小窗」是系统覆盖层、不吃注入点击（GOTCHAS H5），**未做实机取证**（如实记） |

---

### 阶段二十三 · 审查 P0 修复批次 + 小窗四个新问题

> 按用户对 `docs/REVIEW.md` 的取舍执行：P0 第 1 项（仓库里的 token）**不修**，
> 第 2~7 项全修（改 minSdk 获授权）；另修 4 个小窗/图文问题；触感常量按版本门控。

| # | 来源 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 触感版本门控 | `Haptics` 的 `segment/confirm/reject` 用的常量是 API 30/34 才有的，低版本会被系统**静默忽略**。现在按 `SDK_INT` 降级：`segment`→低版本用 `ContextClick`，`confirm`→`LongPress`，`reject`→`ContextClick` | 代码；真机各档待实测 |
| 2 | 审查 P0-2（可用性） | ①`RepoViewModelFactory` 改用 `parameterTypes.size`（`getParameterCount` 是 API 26 才有的，会在 7.x 抛 `NoSuchMethodError`）；②**`minSdk` 24 → 26**（用户授权），README / BUILD / CONTEXT 三处同步为「Android 8.0 及以上」 | `assembleDebug` 通过；AGENTS 新增硬约束 5b |
| 3 | 审查 P0-3（核心机制） | ①`switchToVipAccount` 加 `Mutex` 单飞 + 成功/进行中都设 10s 短冷却（原来设成"当前秒"，判据恒 false）+ 失败计数改 `AtomicInteger`；②`XhsApi` 的身份闸门从"全局普通布尔"改为 **Mutex 串行 + 协程上下文标记重入**，并发请求会**排队**而不是各自冲或整体绕过；③闸门异常不再静默吞（记录 + DEBUG 日志）；④自愈重登只在**登录成功且凭证确实变了**才算成功，并回调 `onIdentityChanged` → 上层 bump epoch 重载 | 编译通过；`XhsApi` 新增 `onIdentityChanged` / `gateFailure` |
| 4 | 审查 P0-4（数据安全） | ①恢复不再写 `settings.vipEnd`（导出从不写，写入可让换号永久停摆）；②`autoVip` 恢复改为"键存在才写"（否则缺键会静默关掉核心开关）；③清空 + 写入放进 **`withTransaction`**；④恢复的每条记录校验 `noteId > 0` 且 `rawJson` 可解析，非法跳过并计数；⑤`SavedNote/History/WatchLater → NoteItem` 三处读取点改成 `mapNotNull + runCatching`（坏行不再崩进程） | 编译通过；`docs/REVIEW.md` 附录A-P0-7/8、C-P0-3、C-P1-9 |
| 5 | 审查 P0-5（安全） | 取消系统文件选择器时 `systemPickerActive` 现在**一定复位**（`try/finally` 覆盖取消路径），否则应用锁会永久失效 | 代码 + `docs/REVIEW.md` 附录B-P0-5 |
| 6 | 审查 P0-6（交付） | ①`MainActivity` 加 `android:launchMode="singleTask"`（默认 standard 会让深链开第二个实例、`onNewIntent` 永不触发）；②`versionName` 1.2.1 → **1.3.0**（v1.2.1 之后已落地十几批功能），BUILD/CONTEXT 同步 | `processDebugMainManifest` 通过（首次插入注释时 XML 未闭合，已修） |
| 7 | 审查 P0-7（规范） | ①「关于」页删掉「也不破解付费校验」这类与实现相反的措辞，改为照实描述（硬约束 12）；②作者页第 5 份自绘关注按钮改用 `FollowPill`（硬约束 21）；③**返回/关闭/取消这类屏幕上的按钮一律补回触感**（硬约束 19 重写：不加触感的只有系统返回手势本身） | 代码 |
| 8 | 用户新问题 1：小窗关闭后仍后台播放 | 收尾判据改为 **"有会话 + 走到 `onStop`"**（小窗里的 Activity 可见、不会 stop），不再看 `isInPictureInPictureMode`（关闭时它可能仍是 true → 旧判据永不成立）；另加 500ms 宽限任务区分「展开 vs 关闭」、进小窗 2s 内不误判 | 日志实证：进小窗时确实会走 `ON_STOP`（`PauseWhenNotStarted ON_STOP handedOver=true`）；展开路径实测不误杀（展开后回到详情页） |
| 9 | 用户新问题 2：全屏右上角关闭按钮缺触感 | 补 `haptics.click(onDismiss)` | 实机：点该按钮 → 触感记录 `17:14:40.868 TICK` |
| 10 | 用户新问题 3：全屏与嵌入翻页手感不同 | 抽出**唯一实现** `PagerPageHaptics(pagerState)`（按 `settledPage`，落定才响），嵌入画廊与全屏查看器都改用它 | 实机：全屏内左滑后新增触感记录（`17:14:53`）；两处现在是同一段代码 |
| 11 | 用户新问题 4：横屏视频切小窗变竖屏/拉伸 | ①比例按**旋转修正**后算（`unappliedRotationDegrees` 90/270 交换宽高）并夹到 PiP 允许的 `[1/2.39, 2.39]`；②`onVideoSizeChanged` 也 bump `paramsVersion`，尺寸是进小窗后才探到时自动重设；③小窗画面按视频比例**信箱式**绘制，不再拉满整窗 | 实机（横屏片 2010）：`进小窗后 started=1 paused=0`（在播）、隔 1.3s 两次截图 **md5 不同**（画面在动），截图 `p0-pip-landscape-final` / `p0-pip-final-ratio` |
| 12 | 顺带修 | 进小窗时 `PauseWhenNotStarted` 的 `ON_STOP` 分支会给已交接给 PiP 的播放器 `pause()`（这就是"切到小窗里变暂停"的另一半原因），现在遇到已交接直接跳过；`PipController.start` 若已有别的会话先收掉旧的 | 日志：`ON_STOP handedOver=true`（跳过）；实测进小窗后 `started=1` |

---

### 阶段二十四 · 审查 P1/P2 修复批次（全面审查 → 全面修复）

| # | 类别 | 改动 | 证据 |
| --- | --- | --- | --- |
| 1 | 用户可见 bug | ①关注/粉丝列表**失败态**不再伪装成"还没有关注任何人"，带「重试」（`UserListScreen` + `load(reset=true)`）；②搜索结果处理的**早退分支不再吞掉 `loading`**（改 `try/finally`）——原来会让该次搜索之后再也加载不出下一页；③双击缩放 **Y 轴**也用动画值（原来只有 X 轴平滑） | 编译通过；`docs/REVIEW.md` 附录B-P1-7/8/9 |
| 2 | 生命周期与死代码 | `currentThemeMode()` 改 `collectAsStateWithLifecycle`；删掉 `WatchLaterViewModel.setOrder` + `XhsRepository.setWatchLaterOrder` + 队列页 KDoc 里仍在讲拖动的段落；删掉 `GuestViewModel` 的 `checkVipExpiry/rotate/currentDeviceMac`（无人调用）；删掉未被引用的 `Modifier.hapticClickable`；`composable("followed")` 改回 `Routes.FOLLOWED` | 编译通过 |
| 3 | 网络硬化 | ①**4xx 不再重试**（新增 `HttpStatusException`：4xx 直接抛，5xx/网络中断才重试）；②`UpdateChecker` 改用可取消的 `Call.await()`（原来阻塞 `execute()`，取消不生效）；③更新页只接受 **GitHub 域名的 https 下载地址**（`isTrustedDownloadUrl`），否则回落发布页 | 编译通过；附录A-P1-12/17/18 |
| 4 | 凭据一致性 | `CredentialStore` 新增 `clearSession()` / `clearCredentials()`（token+hash+VIP 缓存**一次提交**清掉）；`loginAsDevice` / 换号路径改用它，消除"新身份 + 旧 token"中间态；`deviceId` 的"读-生成-写"加 `synchronized` | 编译通过；附录A-P1-9/19 |
| 5 | 备份硬化 | ①**不再挂 `fallbackToDestructiveMigration()`**（漏写迁移时不再静默清库）；②备份文件与解压结果都有 64 MB 上限（防解压炸弹）；③空/残缺备份在清库前就被拒；④**队列进备份**（导出 + 恢复 + 覆盖时一并清空）；⑤WebDAV 地址校验：公网 `http://` 直接拒绝（Basic 认证会明文过网），私网 http 允许 | 编译通过；附录A-P0-7/8、C-P0-2/3、C-P1-4/10、C-P2-32 |
| 6 | 平台配置 | `allowBackup="false"`（访客 token 与 WebDAV 口令不再进系统云备份）；`videoSize` 相关 | 编译通过 |
| 7 | lint | `VideoPlayer` 用 **`androidx.annotation.OptIn`** 声明 media3 的 `@UnstableApi`（Java 注解用 Kotlin 的 `@OptIn` 无效）；主题里的 `windowLightNavigationBar` 加 `tools:targetApi="27"`；`aspectRatio` 的 Range 误报用 `coerceAtLeast` 消除 | **lint 错误 40 → 1**（仅剩 `local.properties` 的 PropertyEscape，那是本机忽略文件）；警告 53 项保留（`UseKtx`/`GradleDependency`/`ModifierParameter` 等） |
| 8 | 文档一致性 | ①`CONTEXT` 数据库改 v3 + 四张表、迁移策略改为"必须真迁移"、去掉不存在的 PROPFIND、备份清单补队列与"不再恢复 vipEnd"；②`CONVENTIONS` 的 DB 变更行改为"必须写真迁移"；③`README` 备份内容补队列与 WebDAV 凭据；④`docs/README` 的 GOTCHAS 索引补 H/I 两节；⑤`BUILD` 的 `.gitignore` 说法按实际（规则在 `.gitattributes`）改写 | 逐条对照附录C「文档与代码不一致清单」 |
| 9 | CI / 构建 | CI 增加 `assembleRelease`（R8 + 资源压缩才是真会崩的那条）；`gradle.properties` 里过时的 AGP 注释改写 | 工作流文本 |

**仍未修（如实列出，等你决定优先级）**：
- 审查 P0-1：`tools/fixtures/prefs_*.xml` 里的真实 `user_token` / 设备身份（你说"不用管"）。
- 附录B-P1-11 评论区改 `LazyColumn`（动布局，风险高于收益，未动）。
- 附录B-P1-12 图片按用途尺寸解码（要在 `XhsAsyncImage` 加尺寸参数并改所有调用点）。
- 附录C 的 version catalog、Room schema 导出 + 迁移测试、wrapper checksum、WebDAV 文件名带时间戳（会改变既有备份路径语义）。
- lint 剩余 53 项警告（`UseKtx` ×20、`GradleDependency` ×7、`IconLauncherShape` ×5、`ModifierParameter` ×5 等）与依赖升级。

---

### 阶段二十五 · 图文翻页触感响两次、小窗退出把小窗/详情页搞乱

| # | 现象 | 根因 | 改动 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | 图文**全屏**切换图片，触感触发两次 | 嵌入画廊与全屏查看器是**同一个页码的两处视图**：查看器滑完回调上层，把画廊**程序化**滚到同一页；两处都用 `settledPage` 判定翻页，于是各响一次 | `PagerPageHaptics` 只对**本分页器自己的 `DragInteraction`** 置位的翻页给反馈（程序化滚动不产生拖动事件） | 实机：一次滑动后仅新增 1 条触感记录（`createTime 17:56:46.947`，此前是两条） |
| 2 | 关闭小窗后**详情页也没了**；按全屏回到详情页，**详情页重新加载** | 进小窗时调了 `onBack()`，把详情页那条导航记录弹掉了；展开时只能 `navigate` 一条**新**记录 → 新 ViewModel = 整页重新加载 | 进小窗**不再退出详情页**：小窗期间让导航内容整体不参与组合（`if (!pipActive) AppNavHost(...)`），返回栈记录与 ViewModel 原样保留 | 代码 + 实机（展开后页面仍是原详情页、内容直接可见） |
| 3 | （上条的第二个坑）展开时播放器被释放、详情页自建新播放器 | ①退出回调后 **500ms** 的判定窗口太短：实测 `pipModeChanged(false)` → `onResume` 要 **1.15s**，于是展开被误判成"关闭"→ 释放；②`handBackForDetail()` 清掉 session 后 `isHandedOver` 立刻返回 false，展开瞬间的 `ON_STOP` 又把刚交回去的播放器暂停/释放；③展开时比对的是路由**模式串** `detail/{noteId}`，与 `detail/2010` 永不相等 → 仍然新建记录 | ①判定窗口 500ms → **2500ms**（收到退出回调又走 `onStop` 时用 300ms 确认窗口兜底）；②`PipController.isHandedOver` **同时**认 `PlaybackHandoff` 的持有；③路由比对前把 `arguments["noteId"]` 拼回具体路由；④交回顺序固定为 `handBackForDetail()` → `PlaybackHandoff.givePlayer()` → **最后**才 `inPip=false` | 实机日志：`returnFromPipToDetail route=detail/2010 note=2010`（**未导航**）；展开后只有 1 条在播音轨、`DetailScreen dispose SKIP (handedOver)`、无 `RELEASE`；`ON_STOP handedOver=false` 的误暂停消失 |



### 阶段二十六 · 从推荐页进详情再开小窗，小窗自动暂停

| # | 现象 | 根因 | 改动 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | **从推荐页**进详情页、打开小窗后，小窗里的视频自动暂停（深链路径已正常） | 信息流自己把这一台播放器按停了：`VideoFeedScreen` 的 `LaunchedEffect(active, player)` 在 `active=false` 时无条件 `pause()`，而这一台正是"信息流 → 详情页 → 小窗"交接的同一台（日志 `feed active=false handedOver=false`）。同一族问题还有进小窗时组合销毁触发的 `ON_STOP` 暂停 | 信息流所有"停播"分支先问归属：`LaunchedEffect(active, player)` 的 `pause()` 与 `inPip` 分支都改判 `PipController.isHandedOver(p)`（同时认 `PlaybackHandoff` 的持有），dispose 分支本来就有 | 实机（推荐页 → 详情 → 小窗）：`进小窗 started=1 paused=0`，日志 `ON_STOP handedOver=true`（跳过暂停）；展开后 `route=detail/35535` 未导航、仅 1 条在播音轨 |



### 阶段十四 · 详情页去掉队列入口

- 详情页不再渲染稍后观看浮动按钮（`DetailScreen` 的 `floatingActionButton` 清空，
  `onOpenWatchLater` 参数一并删除，`AppNavHost` 不再传）。入口只留：推荐页信息条、发现页 `CornerFabStack`、
  搜索 / 作者页 / 收藏 / 最近浏览的浮动按钮。
- 证据：详情页 dump 里没有 `稍后观看 N` 文本（断言 `text="稍后观看 \d+"` = False）；
  返回推荐页后信息条 `稍后观看 4 件 · 点这里查看` 仍在。

---

## 统计

| 项目 | 值 |
| --- | --- |
| 提交总数 | 迁移前 205 个（顶点 `0abfec4`）+ 本仓库后续提交 |
| 起止日期 | 2026-10-01 → 2026-10-04（迁移前）／2026-10-04 起为独立仓库 |
| 阶段一（骨架与硬化） | 约 70 个提交（含 9 个 docs） |
| 阶段二（账号/播放/备份） | 约 100 个提交（含 40+ 个 docs） |
| 阶段三（M3 Expressive） | 约 25 个提交（含 5 个 docs） |
| 阶段四（发布准备） | 约 24 个提交 |
| 阶段五（开源化迁移） | 6 个提交（结构 / 文档 / 修复 ×2） |
| 阶段六（关于 / 检查更新与开源发布） | 2 个提交（feat + docs） |
| 阶段七（页面拆分与入口调整） | 2 个提交（fix + docs 同步） |
| 阶段八（启动自动检查更新与缓存分类清理） | 3 个提交（2 feat + docs 同步） |
| 阶段九（发布 v1.2.1） | 1 个提交（版本号 + 发布文档） |
| 阶段十（稍后观看队列 / 长按菜单 / 画中画 / 更新节流） | 2 个提交（feat + docs 同步） |
| 当前版本 | `versionName 1.2.1`，`versionCode = 当前秒数 − 2026-10-01T00:00:00 的秒数` |
| 公开发布 | `v1.2.1`（2026-10-04），<https://github.com/limao996/xhs-thirdparty/releases/tag/v1.2.1>（上一版 `v1.2.0`） |

> 提交信息中的"第 N 轮"指开发轮次（需求批次），与 commit 序号无关。
> 本文件由仓库的 git 历史整理而成；新增提交请按同样格式追加到对应阶段。
