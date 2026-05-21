## ADDED Requirements

### Requirement: 弱网降级
系统 SHALL 检测网络带宽并在弱网条件下自动切换到低码率视频。

#### Scenario: 带宽检测
- **WHEN** 系统通过 OkHttp Interceptor 下载视频数据
- **THEN** 系统计算实时下载速度（滑动平均）

#### Scenario: 弱网切换
- **WHEN** 检测到下载速度持续低于阈值（如 500KB/s）
- **THEN** 系统自动切换到低码率的视频 URL（如果 mock 数据中提供多码率）

#### Scenario: 网络恢复
- **WHEN** 检测到下载速度恢复到正常水平
- **THEN** 系统自动切回高码率视频 URL

### Requirement: 后台资源释放
系统 SHALL 在应用进入后台时释放视频相关资源。

#### Scenario: 进入后台释放 Codec
- **WHEN** 应用进入后台（onPause）
- **THEN** 系统停止 MediaCodec 解码，释放 Codec 实例，保留播放进度

#### Scenario: 回到前台恢复
- **WHEN** 应用回到前台（onResume）
- **THEN** 系统重新创建 MediaCodec 实例，从上次进度位置快速恢复播放

#### Scenario: 内存压力释放
- **WHEN** 系统内存不足
- **THEN** 系统释放非当前视频的缓存和预加载资源

### Requirement: 性能监控
系统 SHALL 集成性能监控工具，可输出 Systrace/Perfetto 报告。

#### Scenario: 帧率监控
- **WHEN** 开发调试模式下
- **THEN** 系统可输出帧率数据（通过 Choreographer 或 Perfetto），验证 95% 帧 >= 55fps

#### Scenario: 内存监控
- **WHEN** 单视频播放期间
- **THEN** 系统内存峰值 < 200MB，可通过 Android Studio Profiler 验证

#### Scenario: 崩溃率
- **WHEN** 执行 200 次自动滑动测试
- **THEN** 系统 0 崩溃

### Requirement: 内存泄漏检测
系统 SHALL 使用 LeakCanary 检测内存泄漏（可选）。

#### Scenario: 泄漏检测
- **WHEN** 开发调试模式下运行
- **THEN** LeakCanary 自动检测 MediaCodec/Surface 等资源的泄漏并报告
