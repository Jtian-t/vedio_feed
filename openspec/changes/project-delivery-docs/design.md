## Context

项目交付要求中包含 5 类文档：README（含架构图/运行指南/功能勾选表）、AI_USAGE.md（AI 方案记录）、DECISIONS.md（架构决策记录）、BUGFIX.md（踩坑记录）、性能报告指引。这些文档需要在功能开发过程中逐步积累，不能一次性事后补写。

技术约束：
- 文档使用 Markdown 格式
- 架构图使用 Mermaid 或 ASCII 格式（可嵌入 Markdown）
- ADR 使用标准 ADR 格式（Title、Status、Context、Decision、Consequences）
- 性能报告需要真实的 Profiler/Perfetto 截图指导

## Goals / Non-Goals

**Goals:**
- 满足项目交付的全部文档要求
- 提供清晰的文档模板和填写指引
- 在功能开发过程中同步记录 AI 使用、架构决策和 Bug 修复
- 指导用户生成合格的性能报告和 Demo 视频

**Non-Goals:**
- 不自动生成性能报告（需要用户在实际运行时截图）
- 不自动生成 Demo 视频（需要用户录制）
- 不涉及项目外的文档规范

## Decisions

### D1: 架构图格式 — Mermaid

**选择**: Mermaid 代码块嵌入 Markdown

**理由**:
1. 纯文本格式，可版本控制
2. GitHub/GitLab 原生渲染支持
3. 易于维护和修改

**备选**: ASCII 架构图 — 更简单但视觉效果差；PlantUML — 功能强大但需要额外工具

### D2: ADR 格式 — 标准 ADR 模板

**选择**: 使用 Michael Nygard 的 ADR 格式

**模板**:
```
# ADR-XXX: 决策标题
## Status: Accepted/Deprecated/Superseded
## Context: 背景
## Decision: 决策内容
## Consequences: 影响
```

**理由**: 行业标准格式，简洁明了

### D3: 性能报告指导方式 — 步骤式指引 + 示例模板

**选择**: 提供详细的步骤指引文档，包含截图位置说明和输出格式模板

**理由**: 性能数据必须在真实设备上运行时采集，无法自动生成

### D4: BUGFIX 记录时机 — 开发过程中实时记录

**选择**: 在每个 Bug 修复后立即记录到 BUGFIX.md

**理由**: 避免事后遗忘细节，确保排查过程的准确性

## Risks / Trade-offs

### R1: 文档完整性风险
**风险**: 开发过程中忘记记录，导致文档不完整
**缓解**: 在 tasks 中明确每个文档的填写时机，作为开发流程的一部分

### R2: 性能数据真实性风险
**风险**: 用户可能不熟悉 Profiler/Perfetto 工具
**缓解**: 提供详细的步骤指引和截图示例说明

## Migration Plan

（文档类变更，无需迁移）
