# 协议说明（互操作性研究）

> ⚠️ **合规声明（先读）**
> 本文记录的是**为互操作性研究**而从官方客户端静态分析得到的通信格式，仅用于让本项目与自己账号的数据互通。
> 仓库不包含官方安装包、反编译产物或任何内容数据。本文描述的是**格式与机制**：客户端不解密内容、不代理流量、
> 不伪造付费凭证；它做的是"注册一次性游客身份去领取新游客体验权限"，不提供内容本身。
> 「小黄书」相关权利归其权利人所有。若权利人反对，请提 Issue，本仓库会立即下架相关实现。

## 1. 传输形态

```
POST https://app.xiaohuangbook.net/v2/{endpoint}
Content-Type: application/octet-stream
Accept: application/octet-stream
Client-Type: 1
Client-Version: 2.6.0
Client-Channel: 1333
User-Id: <当前账号的 user hash>
Body: AES/CBC/PKCS5Padding( JSON 明文 )
```

- 基址：`XhsApi.kt` 顶部注释写明 `POST https://{baseHost}/v2/{path}`；生产基址为 `app.xiaohuangbook.net`。
- 包体不是表单，是**加密后的原始字节**；`Content-Type` / `Accept` 都是 `application/octet-stream`。
- `Client-Version: 2.6.0` 与 `Client-Channel: 1333` 由 `app/build.gradle` 的 `buildConfigField` 提供，
  它们是**协议版本**，与 App 的 `versionName`（1.3.0）无关。

> **唯一的例外**：我的 → 检查更新 走 `https://api.github.com/repos/limao996/xhs-thirdparty/releases/latest`，
> 是**明文 JSON、没有 `User-Id`、没有 AES 包体**的普通 HTTPS 请求（`net/UpdateChecker.kt`，自带独立的 OkHttpClient）。
> 它只读版本号与下载地址，不携带任何账号、身份或设备信息，也不使用本文件描述的任何加密参数。
> 应用启动时也会自动查一次（`App.checkUpdateOnLaunch()`）；GitHub 匿名额度是 60 次/小时/IP，
> 超出会返回 HTTP 403，此时应用静默降级为"未查到新版本"，只在检查更新页显示「检查失败：GitHub 限流」。

## 2. 加解密（`net/XhsCrypto.kt`）

| 用途 | 算法 | 参数 |
| --- | --- | --- |
| 请求/响应包体 | `AES/CBC/PKCS5Padding` | `KEY = 525202f9149e061d`、`IV = 985204f4819ec31c` |
| CDN 图片（`codstatic`） | `AES/ECB/NoPadding` | 同 KEY（对应官方 `AESUtils.zdecrypt`） |

- 密钥与 IV 是 16 字节 ASCII 字符串，直接作为字节使用。
- 图片解密只在 URL 含 `codstatic` 时启用；其余图片走普通加载与磁盘缓存。
- **不要"顺手"改这些常量**：密钥/IV 变了对端直接拒绝，且会掩盖真正的失败原因。

## 3. 包体明文结构（JSON）

```
{
  "s_time": <毫秒时间戳>,          // 参与防重放/时效判断
  "user_token": "<当前会话 token>", // 与请求头 User-Id 对应
  ...接口自身参数...
}
```

- `s_time` 用毫秒；`user_token` 与 `User-Id` 头必须来自同一次登录会话。
- 会话失效的表现是响应无法解密或业务码要求重新认证 → 由 `XhsApi.needsReauth()` 识别，自动重登 guest 后
  重试原请求（`NETWORK_ATTEMPTS = 2`，退避 `RETRY_BACKOFF_MS = 350L`）。

## 4. 账号与设备身份

`net/XhsApi.kt`：

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| `LOGIN_PATH = "v2/user/login-with-guest"` | 登录 | 用它换取 guest 会话；**`v2/app/init` 才是创建/初始化账号的关键一步**，漏掉它会误判"账号不可创建" |
| `APP_INIT_PATH = "v2/app/init"` | 初始化 | 提交设备身份，服务端据此注册/绑定账号 |
| `loginAsGuest()` / `loginAsDevice(identity)` | 两种登录方式 | 前者匿名，后者带设备身份 |
| `currentUserHash()` / `currentDeviceMac()` / `freshRandomMac()` | 身份 | `IdentityGuess` 生成候选设备标识 |

关于"切换账号"的结论（本项目验证过的边界）：guest 会话本身**无法在同一个身份下更换**，能换的是
**设备身份**（MAC / 设备指纹）；因此客户端的"随机账号"实际是"随机设备身份"。

### 4.1 VIP 窗口与账号轮换（客户端的核心机制）

服务端对**每个新注册的身份**发放一段 VIP 体验窗口（`v2/mine/user-info` 返回 `vp_status` / `vp_end` / `svp_end`）。
客户端的做法不是破解校验，而是：**在窗口将尽时换一个全新身份重新建号，领取新号的窗口**。

建号顺序（不可交换，交换即失败）：

1. `POST v2/app/init` —— 用 `User-Id` 头提交设备身份，服务端据此**注册/绑定账号**。
   这是"创建账号"的那一步，容易被误当成启动广告接口而漏掉。
2. `POST v2/user/login-with-guest` —— 用同一身份换取 `user_token` 与 `user_hash`，写入 `CredentialStore`。
3. `POST v2/mine/user-info` —— 读回 `vp_status` / `vp_end`，确认新号**确实带 VIP**（不假设）。

身份形式（`net/IdentityGuess.kt`，随机挑一种；长度是服务端接受的硬约束）：

| 形式 | 构造 | 来源 |
| --- | --- | --- |
| `<12 位 hex>889X` | MAC 去冒号 + `889X` | 设备 MAC |
| `<15 位数字>X` | IMEI（不足补 `M` 到 15 位）+ `X` | IMEI |
| `<16 位 hex>I` | `android_id`（≤30 字符）+ `I` | android_id |
| `<30 位 hex>AI` | `android_id` 截断到 **30** 字符 + `AI` | android_id |

- 实测：**全新随机身份是可以建号的**（先跑 `app/init` 时 8/8 成功；不跑时 0/4），因此不需要任何"预挖账号池"。
- 陷阱：`AI` 形式必须是 30 字符 —— 32 字符建不出账号（官方实现先截断再拼后缀）。
- 节流：一次尝试只建一个新身份；失败按 60s 起指数退避，上限 30 分钟（`XhsRepository` 的 VIP 常量）。

## 5. 已使用的接口清单

来自 `data/XhsRepository.kt`（本文件不重复业务参数，改动请看源码行）：

| 接口 | 用途 |
| --- | --- |
| `home/discover-note` | 推荐流 |
| `home/discover-category` | 发现页分类 |
| `search/note-list` | 搜索作品 |
| `search/user-list` | 搜索作者 |
| `note/view` | 作品详情（批量预取 / 单个获取两条路径） |
| `note-comment/comment-list` | 一级评论 |
| `note-comment/comment-reply-list` | 嵌套回复 |
| `mine/user-info` | 我的资料 |
| `member/user-info` | 某作者资料 |
| `member/follow-list` | 关注列表 |
| `member/fun-list` | 粉丝列表 |
| `member/note-list` | 作者作品 |
| `member/fun-group-list` | 粉丝圈内容（`group_id` 也是"粉丝圈"标识的真实信号） |

## 6. 字段语义（容易踩错的两个）

- **作品类型**：用 `note_type`，取值 `1`/`2` 是图文/视频之外，**`3` 与 `4` 同样是视频**。
  用错字段会让列表页把所有作品判成"图文"（曾经发生过）。
- **粉丝圈标识**：真实信号是 note 的 `group_id`，不是此前臆测的费用字段（那条"VIP 费用标签"实现已被移除）。

## 7. 网络环境的已知干扰

- `app.xiaohuangbook.net` 在部分网络环境会被 **DNS 污染**（解析到 `199.59.148.89`），表现为连接失败或超时；
  排查时先确认解析结果，再怀疑代码。
- 客户端对失败的处理：有界重试 + 网络恢复后自动重试，且**不清空已缓存内容**。

## 8. 可复现性边界

- 本文只描述**格式**（头、算法、参数名），不含任何内容数据、账号或 token。
- 所有请求都由使用者自己的账号发起；客户端不做内容代理、不做带宽中转。
