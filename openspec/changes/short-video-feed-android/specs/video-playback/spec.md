## ADDED Requirements

### Requirement: MediaCodec 视频解码与渲染
系统 SHALL 使用 Android MediaCodec API 进行视频解码，通过 SurfaceView 渲染画面，禁止使用 ExoPlayer/IjkPlayer 等高层播放器封装。

#### Scenario: 视频解码启动
- **WHEN** 用户滑动到一个新视频
- **THEN** 系统创建 MediaCodec 实例，配置为 video/avc 或 video/hevc 解码器，连接 Surface 进行渲染

#### Scenario: 视频解码播放
- **WHEN** MediaCodec 解码完成一帧
- **THEN** 系统通过 Surface 将帧渲染到 SurfaceView 上，用户可见连续画面

#### Scenario: 视频解码结束
- **WHEN** 视频播放完毕或用户切换到其他视频
- **THEN** 系统停止 MediaCodec，释放 Codec 资源，断开 Surface 连接

### Requirement: 视频播放控制
系统 SHALL 提供视频播放/暂停控制、进度显示和倍速播放功能。

#### Scenario: 点击暂停播放
- **WHEN** 用户点击正在播放的视频画面
- **THEN** 系统暂停 MediaCodec 解码和音频播放，画面定格在当前帧

#### Scenario: 再次点击恢复播放
- **WHEN** 用户点击已暂停的视频画面
- **THEN** 系统恢复 MediaCodec 解码和音频播放，视频继续播放

#### Scenario: 长按倍速播放
- **WHEN** 用户长按视频画面
- **THEN** 系统以 2 倍速播放视频（通过调整 MediaCodec 的 buffer 送入速率实现）

#### Scenario: 进度条显示
- **WHEN** 视频正在播放
- **THEN** 系统显示当前播放进度（当前时间/总时长），每秒更新一次

### Requirement: 首帧渲染时间
系统 SHALL 在中端机（骁龙 778G 级）上实现首帧渲染时间 < 800ms。

#### Scenario: 快速首帧
- **WHEN** 用户滑动到一个新视频
- **THEN** 系统在 800ms 内完成 MediaCodec 初始化、解码第一帧并通过 Surface 渲染

### Requirement: 音视频同步
系统 SHALL 实现音视频同步播放，音频使用 AudioTrack 播放。

#### Scenario: 音视频同步
- **WHEN** 视频和音频同时播放
- **THEN** 系统确保音频和视频帧的时间戳对齐，用户感知无明显音画不同步
