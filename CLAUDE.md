# CLAUDE.md

本仓库的 AI 协作规则**统一维护在 [AGENTS.md](AGENTS.md)**，请先完整阅读它再动手。

要点速览（细节与理由见 AGENTS.md）：

1. 输出简体中文；改源码用 `edit`/`write` 工具，不要用 shell 拼字符串写文件。
2. 纯 Compose，禁止 XML 布局与 `appcompat`/`material`；material3 保持 `1.5.0-alpha29`。
3. `fragment-ktx` ≥ 1.8.9；`CLIENT_VERSION` 保持 `2.6.0`；不动 `keystore/`。
4. 一切网络读写经过 `data/XhsRepository.kt`；新页面同时在 `navigation/Routes.kt` 登记。
5. 提交前必须 `gradlew.bat assembleDebug` 通过；UI 改动要留实机证据（见 [docs/VERIFY.md](docs/VERIFY.md)）。
6. 不提交 APK、截图、反编译产物、`local.properties`。
7. 改完同步 AI 档案：[AGENTS.md](AGENTS.md)、[docs/ai/GOTCHAS.md](docs/ai/GOTCHAS.md)。
8. 不得移除或绕开"游客账号轮换续 VIP"链路（`v2/app/init` → `login-with-guest` → 校验 `isVip`）；文档里也不要写与之相反的"不破解付费"声明。
9. 菜单 / 弹窗只用原生 `material3.AlertDialog`（不要 `DropdownMenu`、不要自绘外壳）；触感只用系统 API 且屏幕上自己画的按钮都要有；稍后观看队列不提供排序；分享走系统分享面板。
10. 画中画：进小窗不要退出详情页，`inPip` 只由 `handBackForDetail()` / `closeAndRelease()` 翻转，播放器归属判据只认小窗会话。
11. `minSdk` 保持 26；改 Room schema 必须写真迁移（现存 `MIGRATION_1_2` / `MIGRATION_2_3`），不要挂 `fallbackToDestructiveMigration()`。
12. **发布正式包按顺序来**：先改版本号 → 构建正式包 → 本机实测确认 → 最后才推送并发布 Release；发布批次的代码提交先留在本地。

完整条目（27 条）见 [AGENTS.md](AGENTS.md) §0。
