## ADDED Requirements

### Requirement: Mock JSON 数据源
系统 SHALL 提供 mock JSON 数据源，包含 100+ HLS/MP4 视频 URL。

#### Scenario: 数据加载
- **WHEN** 应用启动或触发刷新/加载更多
- **THEN** 系统从 mock JSON 文件中读取视频数据列表

#### Scenario: 数据格式
- **WHEN** 读取 mock 数据
- **THEN** 每条数据包含视频 URL、标题、作者、点赞数等基本字段

#### Scenario: 分页加载
- **WHEN** 用户触发加载更多
- **THEN** 系统从 mock 数据中按页（每页 10-20 条）返回下一批数据

### Requirement: 视频 URL 多样性
系统 SHALL 在 mock 数据中提供多种视频格式和码率的 URL。

#### Scenario: 多格式支持
- **WHEN** 读取 mock 数据
- **THEN** 数据中包含 MP4 和 HLS 格式的视频 URL

#### Scenario: 多码率支持
- **WHEN** 读取 mock 数据
- **THEN** 部分视频提供不同码率的 URL，用于弱网降级场景
