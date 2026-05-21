## Why

项目要求中明确列出多项必交文档（README、AI_USAGE.md 等），且验收标准包含 Perfetto 报告和 Demo 视频。当前项目仅有功能代码，缺少交付所需的文档和指导，需要在实现过程中同步补全这些交付物。

## What Changes

- 完善 README.md：补充项目架构图、运行指南、功能勾选表
- 新增 AI_USAGE.md：记录 >=5 个 AI 方案的采纳/未采纳案例及理由
- 新增 DECISIONS.md：用 ADR（Architecture Decision Record）格式记录关键架构决策
- 新增 BUGFIX.md：记录 >=3 个踩坑排查过程及根因分析
- 新增性能报告指引：指导生成 Profiler/Perfetto 截图和 Demo 视频

## Capabilities

### New Capabilities

- `readme-delivery`: 完善 README.md，包含架构图、运行指南、功能勾选表
- `ai-usage-log`: 新增 AI_USAGE.md，记录 AI 辅助方案的采纳/未采纳案例
- `architecture-decisions`: 新增 DECISIONS.md，用 ADR 格式记录架构决策
- `bugfix-log`: 新增 BUGFIX.md，记录踩坑排查过程和根因
- `performance-report-guide`: 新增性能报告和 Demo 视频生成指引

### Modified Capabilities

（无）

## Impact

- **新增文件**: README.md（完善）、AI_USAGE.md、DECISIONS.md、BUGFIX.md
- **文档范围**: 覆盖项目交付的全部文档要求
- **与代码开发同步**: 文档需要在功能实现过程中逐步填写，非一次性完成
