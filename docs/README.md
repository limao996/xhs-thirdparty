# 文档索引

本仓库文档分两层：**给人读的**（本目录）与**给 AI 代理读的**（[`ai/`](ai/) + 根目录的
[`AGENTS.md`](../AGENTS.md)、[`CLAUDE.md`](../CLAUDE.md)）。两层不互相照抄。

## 项目文档（给人）

| 文档 | 内容 | 什么时候看 |
| --- | --- | --- |
| [../README.md](../README.md) | 项目介绍、功能、快速开始、技术栈、版本号 | 第一次接触项目 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 分层、代码地图、数据流、Room、导航、设计令牌、设计取舍 | 要改代码结构时 |
| [PROTOCOL.md](PROTOCOL.md) | 传输形态、加解密参数、包体结构、接口清单、字段语义、合规声明 | 要动网络层时 |
| [BUILD.md](BUILD.md) | 环境要求、命令、版本号规则、签名、依赖矩阵、常见构建问题 | 构建失败或要升级依赖时 |
| [VERIFY.md](VERIFY.md) | 设备验证回路、安装校验、UI 取证、测试数据注入、干扰项排查 | 要证明"真的生效了"时 |
| [CHANGELOG.md](CHANGELOG.md) | 按四个开发阶段整理的 205 个提交 | 想了解历史脉络时 |

## AI 档案（给 AI 代理）

| 文档 | 内容 |
| --- | --- |
| [../AGENTS.md](../AGENTS.md) | **入口**：10 条硬约束、仓库地图、常用命令、变更流程、汇报格式 |
| [../CLAUDE.md](../CLAUDE.md) | Claude Code 等工具的极简指针 |
| [ai/CONTEXT.md](ai/CONTEXT.md) | 一页上下文：技术栈表、代码地图、数据流、协议摘要、环境事实、当前状态 |
| [ai/CONVENTIONS.md](ai/CONVENTIONS.md) | 代码 / 提交 / 文档 / 沟通 / 可移植性约定 |
| [ai/GOTCHAS.md](ai/GOTCHAS.md) | 踩坑规则：触发条件 → 正确做法（A 流程 / B 构建 / C Compose / D 数据与播放器 / E 仓库） |
| [ai/PROMPTS.md](ai/PROMPTS.md) | 可直接复用的任务提示词模板（修复 / 新页面 / UI 改造 / 依赖 / 验证 / 文档 / 发布自检） |
| [.github/copilot-instructions.md](../.github/copilot-instructions.md) | GitHub Copilot 指令 |
| [.cursor/rules/project.mdc](../.cursor/rules/project.mdc) | Cursor 项目规则 |

## 实机截图

仓库**不存放应用截图**（`.gitignore` 已忽略 `docs/images/screenshots/`）。
需要截图做说明时，用 `tools/verify.ps1` 的 `Shot` 函数在本机生成，文件落在 `docs/images/screenshots/`。

## 维护约定

1. 结构、约束、踩坑发生变化时，同一次改动内更新对应文档（AI 档案优先）。
2. 新增/重命名文档后，更新本索引与 [../README.md](../README.md) 的文档地图。
3. 文档里的命令必须可以直接复制执行，且不含机器专属绝对路径。
