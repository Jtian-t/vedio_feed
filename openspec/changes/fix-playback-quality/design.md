## Context

当前 VideoPlayer.kt 已经过多次迭代重写（Bug #1-#7），最新版本使用 sequence-based 生命周期管理。核心问题是音视频同步质量差、音频卡顿、切换视频不稳定。根因包括 AudioTrack 缓冲区不足、视频同步未使用音频时钟、资源释放时序不确定。

## Goals / Non-Goals

**Goals:**
- 音频播放流畅，无卡顿/爆音
- 音画同步偏差 < 80ms
- 切换视频零黑屏
- App 不会因播放器问题崩溃

**Non-Goals:**
- 不引入 ExoPlayer 或任何第三方播放器
- 不改动 Feed 列表 UI 架构（LazyColumn + AndroidView）
- 不改动缓存/预加载/网络层
- 不实现 seekTo 精确定位（简化实现）

## Decisions

### D1: 音频为主时钟（Audio Master Clock）

**选择**: 有音频时用 `AudioTrack.getPlaybackHeadPosition()` 作为 A/V sync 主时钟
**原因**: 音频播放速率由硬件固定（44100Hz），不受解码速度波动影响，是最稳定的时钟源。ExoPlayer、IJKPlayer 均采用此方案。
**备选方案**:
- 墙钟同步（当前方案）：受 CPU 调度波动影响，音画偏差大
- 视频同步：视频帧率不稳定，不适合做主时钟

### D2: AudioTrack 缓冲区 minBuf * 4

**选择**: 缓冲区大小 = `getMinBufferSize() * 4`
**原因**: MODE_STREAM 下 `write()` 在缓冲区满时阻塞。小缓冲区导致频繁阻塞→释放→阻塞，产生可闻间隙。4 倍缓冲提供足够的吸收空间。
**备选方案**:
- minBuf * 2（当前）：不够，音频卡顿
- minBuf * 8：延迟太高，不适合短视频场景
- MODE_STATIC：需要预知音频总大小，不适合流式

### D3: Float PCM 自动适配

**选择**: 首次音频输出时检测 `KEY_PCM_ENCODING`，如果是 float 则重建 AudioTrack
**原因**: 某些 AAC 编码器输出 float PCM，直接写入 16-bit AudioTrack 会截断产生噪音。自动检测并适配是最稳妥的方案。

### D4: Sequence-based 生命周期（保持不变）

**选择**: 每次 `prepareAndPlay` 递增 `currentSeq`，decode loop 通过 `seq == currentSeq` 判断继续
**原因**: 比 AtomicBoolean 更可靠，支持多个任务的安全切换。已在当前版本验证可行。

## Risks / Trade-offs

- [AudioTrack.getPlaybackHeadPosition() 溢出] → 已做 uint32 屏蔽高位处理
- [Float PCM 重建 AudioTrack 时短暂静音] → 可接受，仅首次检测时发生
- [大缓冲区增加音频延迟] → minBuf*4 在 44100Hz 下约增加 ~100ms，对短视频无感知
- [decode loop 异常吞掉可能导致静默失败] → 已加 Log.e 输出异常信息便于调试
