## ADDED Requirements

### Requirement: DECISIONS.md 使用 ADR 格式
DECISIONS.md SHALL 使用标准 ADR（Architecture Decision Record）格式记录架构决策。

#### Scenario: ADR 格式
- **WHEN** 每个架构决策被记录
- **THEN** 系统使用以下 ADR 格式：ADR 编号 + 标题、Status（Accepted/Deprecated/Superseded）、Context（背景）、Decision（决策内容）、Consequences（影响）

#### Scenario: ADR 编号
- **WHEN** 多个 ADR 被记录
- **THEN** 系统使用递增编号（ADR-001、ADR-002...）标识每个决策

### Requirement: DECISIONS.md 记录关键架构决策
DECISIONS.md SHALL 记录项目中至少 5 个关键架构决策。

#### Scenario: 关键决策覆盖
- **WHEN** 项目交付时
- **THEN** DECISIONS.md 中包含以下关键决策：UI 框架选择（Jetpack Compose）、视频解码方案（MediaCodec 异步模式）、缓存架构（三级缓存）、预加载策略、边下边播方案（本地代理）

#### Scenario: 决策理由充分
- **WHEN** 每个决策被记录
- **THEN** 系统在 Context 和 Decision 部分说明：为什么选择此方案、考虑了哪些备选方案、为什么排除备选方案

### Requirement: DECISIONS.md 决策状态可追溯
DECISIONS.md SHALL 支持决策状态的变更和追溯。

#### Scenario: 状态变更
- **WHEN** 一个决策被后续决策取代
- **THEN** 系统将原决策状态更新为 Superseded，并引用替代它的 ADR 编号
