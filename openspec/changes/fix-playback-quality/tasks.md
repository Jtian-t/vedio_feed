## 1. 音视频同步修复

- [x] 1.1 实现基于 wall clock 的视频帧同步（delay 方式：videoElapsed - wallElapsed）
- [x] 1.2 支持倍速播放同步（videoElapsed 除以 speed 系数）
- [ ] 1.3 验证音画同步偏差 < 80ms（手动测试 + Logcat 观察）

## 2. 音频管线优化

- [x] 2.1 AudioTrack 缓冲区增大至 minBufSize * 4
- [x] 2.2 暂停时同时暂停 AudioTrack，恢复时同时恢复
- [ ] 2.3 验证音频无卡顿/爆音

## 3. 播放器生命周期管理

- [x] 3.1 AtomicInteger 序列号管理 prepareAndPlay/decodeLoop 生命周期
- [x] 3.2 双 MediaExtractor（videoExtractor + audioExtractor）独立读取，避免交叉 advance 数据错乱
- [x] 3.3 finally 块中安全释放 codec/extractor/audiotrack
- [x] 3.4 VideoPlayerView 使用 key(videoUrl) + DisposableEffect(isCurrentVideo) 管理释放
- [ ] 3.5 验证切换视频 0 黑屏、0 声音残留

## 4. 异常处理加固

- [x] 4.1 CancellationException 正常抛出不设 ERROR 状态
- [x] 4.2 decode loop 中 MediaCodec 操作在 try/catch 保护下
- [ ] 4.3 验证 200 次滑动 0 崩溃

## 5. 验收测试

- [ ] 5.1 手动测试：播放视频检查音频流畅度和音画同步
- [ ] 5.2 手动测试：快速切换 10 个视频检查黑屏/声音残留
- [ ] 5.3 自动化：200 次滑动测试验证稳定性
