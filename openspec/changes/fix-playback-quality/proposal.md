## Why

短视频 Feed 应用的核心播放功能已基本实现（MediaCodec 解码 + SurfaceView 渲染 + AudioTrack 音频），但存在多个质量问题阻碍交付验收：音频卡顿爆音、音画不同步、切换视频偶发黑屏、播放过程中偶发崩溃。这些问题直接影响用户体验，需要在交付前修复。

## What Changes

- 重写音视频同步逻辑：以音频为主时钟（AudioTrack.getPlaybackHeadPosition），视频向音频对齐
- 优化 AudioTrack 缓冲区策略：增大缓冲区、处理 float/16-bit PCM 格式兼容
- 稳定播放器生命周期：sequence-based 取消机制，确保资源无泄漏
- 视频切换流畅性：优化 prepareAndPlay 中的资源释放时序
- 异常处理加固：decode loop 中 MediaCodec 异常不会导致崩溃

## Capabilities

### New Capabilities

- `av-sync`: 音视频同步引擎，以音频为主时钟，无音频时回退墙钟同步
- `audio-pipeline`: 音频解码管线优化，支持 float PCM、大缓冲区、背压控制
- `player-lifecycle`: 播放器生命周期管理，sequence-based 取消、资源释放、异常防护

### Modified Capabilities

（无，此为质量修复，不改变功能规格）

## Impact

- **改动文件**: `VideoPlayer.kt`（核心重写）、`VideoPlayerView.kt`（生命周期简化）
- **不改动**: FeedScreen、FeedViewModel、缓存系统、预加载系统、网络层
- **验收标准**:
  - 音频无卡顿、无爆音
  - 音画同步偏差 < 80ms
  - 切换视频 0 黑屏
  - 200 次滑动 0 崩溃
