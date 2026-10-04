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
