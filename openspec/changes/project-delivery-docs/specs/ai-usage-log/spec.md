## ADDED Requirements

### Requirement: AI_USAGE.md 记录采纳案例
AI_USAGE.md SHALL 记录至少 5 个 AI 提出的方案被采纳的案例。

#### Scenario: 采纳案例记录
- **WHEN** AI 提出一个方案并被开发者采纳
- **THEN** 系统在 AI_USAGE.md 中记录：场景描述、AI 建议内容、采纳理由、最终效果

#### Scenario: 采纳案例数量
- **WHEN** 项目交付时
- **THEN** AI_USAGE.md 中包含 >=5 个采纳案例

### Requirement: AI_USAGE.md 记录未采纳案例
AI_USAGE.md SHALL 记录至少 3 个 AI 提出的方案未被采纳的案例。

#### Scenario: 未采纳案例记录
- **WHEN** AI 提出一个方案但未被采纳
- **THEN** 系统在 AI_USAGE.md 中记录：场景描述、AI 建议内容、未采纳理由、实际采用的替代方案

### Requirement: AI_USAGE.md 记录格式规范
AI_USAGE.md SHALL 使用统一的记录格式。

#### Scenario: 记录格式
- **WHEN** 每个案例被记录
- **THEN** 系统使用以下字段：案例编号、日期、场景、AI 方案、采纳/未采纳、理由、效果评估

#### Scenario: 分类展示
- **WHEN** 用户阅读 AI_USAGE.md
- **THEN** 系统将案例分为"采纳"和"未采纳"两个部分，便于查阅
