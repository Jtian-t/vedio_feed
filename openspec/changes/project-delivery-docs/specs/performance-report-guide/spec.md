## ADDED Requirements

### Requirement: Profiler 性能报告指引
系统 SHALL 提供使用 Android Studio Profiler 生成性能报告的详细指引。

#### Scenario: CPU Profiler 指引
- **WHEN** 用户需要采集 CPU 性能数据
- **THEN** 系统指引：打开 Android Studio → Profiler → CPU → Record → 操作应用 → Stop → 导出 Trace 文件或截图

#### Scenario: Memory Profiler 指引
- **WHEN** 用户需要采集内存性能数据
- **THEN** 系统指引：打开 Android Studio → Profiler → Memory → 观察实时内存曲线 → 截图峰值内存（需 < 200MB）

#### Scenario: Network Profiler 指引
- **WHEN** 用户需要采集网络性能数据
- **THEN** 系统指引：打开 Android Studio → Profiler → Network → 观察请求时序和带宽 → 截图

### Requirement: Perfetto/Systrace 性能报告指引
系统 SHALL 提供使用 Perfetto/Systrace 生成帧率报告的详细指引。

#### Scenario: Perfetto 采集指引
- **WHEN** 用户需要采集帧率数据
- **THEN** 系统指引：通过 adb shell perfetto 启动追踪 → 操作应用 → 停止追踪 → 导出 .pftrace 文件 → 在 ui.perfetto.dev 打开分析

#### Scenario: Systrace 采集指引
- **WHEN** 用户需要采集 Systrace 数据
- **THEN** 系统指引：使用 systrace.py 或 Perfetto → 采集 gfx 和 view 频率 → 分析帧耗时是否 < 16.67ms（60fps）

#### Scenario: 帧率数据展示
- **WHEN** 生成帧率报告
- **THEN** 系统指引用户导出：帧率分布图、掉帧统计、95% 帧率 >= 55fps 的截图

### Requirement: Demo 视频录制指引
系统 SHALL 提供录制 Demo 视频的指引。

#### Scenario: 录制工具指引
- **WHEN** 用户需要录制 Demo 视频
- **THEN** 系统指引使用以下工具之一：Android Studio 内置录屏、adb shell screenrecord、scrcpy

#### Scenario: Demo 内容要求
- **WHEN** 录制 Demo 视频
- **THEN** 系统指引录制以下内容：应用启动、上下滑切换视频（5-10 次）、下拉刷新、上拉加载更多、点击暂停/播放、点赞操作、后台恢复播放

#### Scenario: 视频格式要求
- **WHEN** Demo 视频录制完成
- **THEN** 系统指引导出 MP4 格式，时长 1-3 分钟，包含字幕或旁白说明
