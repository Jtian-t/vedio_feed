## ADDED Requirements

### Requirement: BUGFIX.md 记录踩坑排查过程
BUGFIX.md SHALL 记录至少 3 个 Bug 的完整排查过程。

#### Scenario: 排查过程记录
- **WHEN** 一个 Bug 被修复
- **THEN** 系统在 BUGFIX.md 中记录：Bug 描述、复现步骤、排查过程（尝试了哪些方法）、根因分析、修复方案

#### Scenario: 排查过程数量
- **WHEN** 项目交付时
- **THEN** BUGFIX.md 中包含 >=3 个 Bug 排查记录

### Requirement: BUGFIX.md 包含根因分析
每个 Bug 记录 SHALL 包含深入的根因分析。

#### Scenario: 根因分析
- **WHEN** 一个 Bug 被记录
- **THEN** 系统在根因分析部分说明：为什么会出现这个 Bug、是代码逻辑错误还是 API 使用不当、如何避免类似问题

#### Scenario: 根因分析深度
- **WHEN** 根因分析被展示
- **THEN** 系统的分析不是表面描述（如"代码写错了"），而是深入到 API 行为、系统机制、线程安全等层面

### Requirement: BUGFIX.md 格式规范
BUGFIX.md SHALL 使用统一的记录格式。

#### Scenario: 记录格式
- **WHEN** 每个 Bug 被记录
- **THEN** 系统使用以下格式：Bug 编号、日期、标题、现象、复现步骤、排查过程、根因、修复方案、经验教训

#### Scenario: 分类展示
- **WHEN** 用户阅读 BUGFIX.md
- **THEN** 系统按 Bug 类型（MediaCodec、缓存、UI 等）或时间顺序组织记录
