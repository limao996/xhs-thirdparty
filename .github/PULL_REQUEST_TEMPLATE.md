## 这个 PR 做了什么

<!-- 一句话说明。若来自 Issue，请写 Closes #123 -->

## 类型

- [ ] `feat` 新功能
- [ ] `fix` 缺陷修复
- [ ] `perf` 性能优化
- [ ] `refactor` 重构（行为不变）
- [ ] `docs` 文档 / AI 档案
- [ ] `build` 构建、依赖、版本

## 改动范围

<!-- 列出主要文件；若改了 AGENTS.md 的硬约束涉及项（material3 版本、fragment-ktx、CLIENT_VERSION、签名、versionCode），请单独说明为什么必须改 -->

## 证据（必填）

- [ ] `gradlew.bat assembleDebug` 通过 —— 贴输出末行：

```
BUILD SUCCESSFUL in ...
```

- [ ] UI 改动附实机证据（截图路径或 `DumpUi` 片段）：

```
（贴关键片段）
```

- [ ] 若修缺陷：已复现原现象，且修复后同一路径不再出现（说明复现方式）：

```
（复现路径 / 命令）
```

- [ ] 未验证的部分（如无请写"无"）：

```
（写清缺什么证据）
```

## 检查清单

- [ ] 没有提交 APK、截图、反编译产物、`local.properties`、构建缓存
- [ ] 没有引入 XML 布局 / appcompat / material 等被移除的依赖
- [ ] 数据访问经过 `data/XhsRepository.kt`（UI 未直接调 `net/` 或 Room）
- [ ] 新页面已在 `navigation/Routes.kt` 与 `AppNavHost` 登记
- [ ] 若动了结构、约束或新增踩坑点，已同步 `AGENTS.md` 与 `docs/ai/`
- [ ] 提交信息使用中文并带前缀（`feat:` / `fix:` / …）
