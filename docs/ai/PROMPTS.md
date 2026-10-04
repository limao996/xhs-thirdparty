# AI · 提示词模板（PROMPTS）

> 直接复制给 AI 代理使用。模板已经内置了本项目的约束、证据要求与汇报格式，
> 因此**不需要**在提示词里重复粘贴 `AGENTS.md` 的内容。
> 变量用 `<尖括号>` 标出，替换后使用。

## 0. 通用前缀（加在任何任务前面）

```
先读 AGENTS.md 与 docs/ai/CONTEXT.md，再动手。
约束：输出简体中文；纯 Compose 不改 UI 框架；改文件用 edit/write；不提交 APK/截图/反编译产物。
做完按此格式汇报：需求 → 做了什么 → 证据 → 状态；没做完的显式写「未完成」。
```

## 1. 缺陷修复（最短回路）

```
<通用前缀>

现象：<用户描述的现象>
复现路径：<点击序列>
要求：
1. 先在设备上复现并给出证据（dump 文本或截图）；
2. 定位到唯一职责文件后说明根因（哪一行、为什么）；
3. 只改一个关注点，改完重新复现同一路径，给出前后对比证据；
4. 若根因涉及 GOTCHAS.md 里已有的条目，指出是第几条。
```

## 2. 新增页面 / 新增接口

```
<通用前缀>

需求：<一句话描述>

请按以下顺序做，并在汇报里逐条对应：
1. 接口：在 data/XhsRepository.kt 增加方法（含缓存写入与失败语义），不要绕过仓库层；
2. 状态：新增 ui/viewmodel/XxxViewModel.kt，用 RepoViewModelFactory 注入；
3. 页面：新增 ui/screens/XxxScreen.kt，颜色/间距只用 ui/theme 的 colorScheme 与 Tokens；
4. 路由：在 navigation/Routes.kt 登记常量并在 AppNavHost 注册（属底部页则补 HomeTab）；
5. 编译：gradlew.bat assembleDebug 必须通过，贴出 BUILD SUCCESSFUL 行；
6. 验证：DumpUi 或截图证明页面可达且数据正确；
7. 档案：若引入新约束或新坑，更新 AGENTS.md / docs/ai/GOTCHAS.md。
不要顺手重构无关文件。
```

## 3. UI 改造（视觉）

```
<通用前缀>

改造目标：<按 Material 3 规范描述，不要只说"好看一点">

要求：
- 说明这次改造动了哪些令牌/组件，为什么符合 M3 规范；
- 给改造前后的实机证据（截图或 dump 对比）；
- 如果结论是"只改了主题变量、渲染没变"，必须明说，不许当成已完成；
- 更新 docs/ARCHITECTURE.md 的主题章节（若令牌有增减）。
```

## 4. 依赖或构建变更

```
<通用前缀>

目标：<升级/新增/移除 某依赖>

要求：
1. 先说明它是否触及 AGENTS.md 的硬约束（material3 / fragment-ktx / CLIENT_VERSION / 签名）；
2. 改 app/build.gradle 后编译，并贴出实际解析到的版本证据（编译输出或 dependencies 报告）；
3. 若 APK 体积变化 > 0.5MB，给出前后数字；
4. 如果新版本破坏了某功能，回退并说明，不要留着"已知问题"不提。
```

## 5. 设备验证

```
<通用前缀>

要验证的行为：<一句话>

要求：
1. 使用 tools/verify.ps1 的函数库（EnsureDevice / LaunchApp / DumpUi / TapText / Shot / CrashCount）；
2. 明确写清：装了哪个包（含 md5 比对）、是否 force-stop、测前排除的干扰项（DNS 污染 / 分享口令弹窗 / 锁屏）；
3. 每个断言都要给原始证据，断言失败时先怀疑断言本身；
4. 验证结束后清理探针与设备测试数据（noteId >= 900001）。
```

## 6. 文档与档案维护

```
<通用前缀>

目标：<新增/更新某文档>

要求：
- 人读文档写进 README.md / docs/*.md；给 AI 的规则/地图/坑写进 AGENTS.md / docs/ai/*；
- 不要照搬历史工作区的文档文本，按当前仓库真实内容重写；
- 文档里的命令必须能直接复制执行，且不含机器专属绝对路径；
- 改完更新 docs/README.md 索引与 README.md 的文档地图。
```

## 7. 发布前自检

```
<通用前缀>

在声明"可以发布"之前，逐项给出证据：
1. gradlew.bat assembleDebug 与 assembleRelease 都通过（贴最后几行）；
2. release 包已用 keystore/release.jks 签名（给出 apksigner 或 jarsigner 输出）；
3. versionName / versionCode 符合 docs/BUILD.md 的规则（给出算式与结果）；
4. git status 干净，没有 APK / 截图 / build 产物 / local.properties 入库；
5. docs/ai/CONTEXT.md 的"当前状态"已更新；
6. 无未说明的已知问题。
任一项缺失，就用「未完成」表述，不要写"应该没问题"。
```
