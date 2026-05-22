# BUGFIX.md

## 目录

- [Bug #1: 视频无法加载播放 — prepare/play 流程断裂](#bug-1-视频无法加载播放--mediacodec-初始化与播放流程断裂)
- [Bug #2: 视频加载极慢 — 双 MediaExtractor 重复请求](#bug-2-视频加载极慢--双-mediaextractor-重复请求同一-url)
- [Bug #3: Google Storage URL 返回 403 Forbidden](#bug-3-google-storage-视频-url-全部返回-403-forbidden)
- [Bug #4: 单 Extractor 空 buffer 干扰视频解码](#bug-4-单-extractor-空-buffer-干扰视频解码)
- [Bug #5: 无声音 + 画面卡顿 + 切换视频黑屏](#bug-5-无声音--画面卡顿--切换视频黑屏)
- [Bug #6: 视频画面黑屏 + 切换视频无法加载（release/prepare 时序竞争）](#bug-6-视频画面黑屏--切换视频无法加载releaseprepare-时序竞争)
- [Bug #7: 音频卡顿 + 音画不同步](#bug-7-音频卡顿--音画不同步)
- [Bug #8: Android 9+ 禁止明文 HTTP 导致视频无法播放](#bug-8-android-9-禁止明文-http-导致视频无法播放)
- [Bug #9: release() 与 decode loop 竞争 MediaCodec 导致 IllegalStateException](#bug-9-release-与-decode-loop-竞争-mediacodec-导致-illegalstateexception)
- [Bug #10: Asset 双 Extractor 共享 FD + 缺少防御性检查导致播放失败](#bug-10-asset-双-extractor-共享-fd--缺少防御性检查导致播放失败)
- [Bug #11: 代理网络抖动后断开连接导致播放永久停止](#bug-11-代理网络抖动后断开连接导致播放永久停止)

---

## Bug #1: 视频无法加载播放 — MediaCodec 初始化与播放流程断裂

- **日期**: 2026-05-21
- **严重程度**: 高
- **状态**: 已修复

### 现象

应用 UI 正常显示（标题、作者、点赞按钮等），但视频区域始终为黑色，无任何视频画面。

### 复现步骤

1. 运行应用
2. 首个视频显示黑色画面
3. 上下滑动切换视频，所有视频均为黑屏
4. Logcat 无明显报错

### 排查过程

1. ~~检查 SurfaceView 是否创建~~ — AndroidView 正常渲染，SurfaceView 已添加到布局
2. ~~检查 SurfaceHolder.Callback~~ — `surfaceCreated()` 回调正常触发
3. ~~检查 prepare() 调用~~ — `LaunchedEffect` 中调用了 `player.prepare(videoUrl, surface)`
4. ~~发现断裂点~~ — `prepare()` 执行完后将 state 设为 `PAUSED`，但从未调用 `play()`！
5. 进一步检查 — `VideoPlayerView` 中的 `LaunchedEffect` 只调用了 `prepare()`，没有后续的 `play()` 调用
6. 用户点击画面触发 `togglePlayPause()` — 理论上能恢复，但初始状态为 PAUSED 而非 IDLE，`togglePlayPause()` 调用 `play()` 后 MediaCodec 才开始解码

### 根因

**prepare() 和 play() 流程断裂**。`VideoPlayer.prepare()` 方法在异步协程中完成 MediaCodec 初始化后，将状态设为 `PAUSED` 等待外部调用 `play()`，但 `VideoPlayerView` 从未自动调用 `play()`。结果是 MediaCodec 配置好了但从未开始解码输出，Surface 上没有任何帧被渲染。

### 修复方案

1. 新增 `prepareAndPlay(url, surface)` 方法 — prepare 完成后立即自动进入 PLAYING 状态
2. `VideoPlayerView` 改为调用 `prepareAndPlay()` 替代 `prepare()`
3. 简化 `VideoPlayerView` 逻辑：Surface 就绪后直接触发 `prepareAndPlay`

```kotlin
// 修复前
fun prepare(url: String, surface: Surface) {
    // ... setup ...
    _state.value = State.PAUSED // 等待 play()，但没人调用
}

// 修复后
fun prepareAndPlay(url: String, surface: Surface) {
    // ... setup ...
    isPaused.set(false)
    _state.value = State.PLAYING // 准备好就播
}
```

### 经验教训

- `prepare()` 和 `play()` 分离是播放器设计的常见模式（类似 Android MediaPlayer），但在 Compose 的声明式 UI 中容易遗漏后续调用
- 应该提供一个 `prepareAndPlay()` 便捷方法，覆盖"准备好就自动播放"的常见场景
- 状态机设计需要考虑初始状态的触发条件，不能假设外部一定会调用 `play()`

---

## Bug #2: 视频加载极慢 — 双 MediaExtractor 重复请求同一 URL

- **日期**: 2026-05-21
- **严重程度**: 中
- **状态**: 已修复

### 现象

视频能播放但加载非常慢，从滑动到新视频到出现首帧需要 3-5 秒。

### 复现步骤

1. 滑动到一个新视频
2. 观察从黑屏到出现画面的时间
3. Logcat 中可见两次 `setDataSource` 日志

### 排查过程

1. ~~检查 Logcat~~ — 发现 `setupExtractors()` 创建了两个 MediaExtractor，都对同一 URL 调用 `setDataSource(url)`
2. ~~分析代码~~ — `videoExtractor` 和 `audioExtractor` 各自独立连接同一视频 URL
3. ~~网络层面~~ — 每次播放需要建立两次 HTTP 连接 + TLS 握手 + 请求重定向
4. ~~进一步分析~~ — 异步回调模式下，音视频 track 的读取需要在不同 extractor 间交替，逻辑复杂且容易出错

### 根因

**双 MediaExtractor 重复请求**。原始设计为视频和音频各创建一个 MediaExtractor，各自独立 `setDataSource(url)`，导致：
1. 每次播放同一视频发起两次 HTTP 连接（网络开销翻倍）
2. 异步回调模式下两个 codec 交替读取数据，逻辑复杂
3. 没有预缓冲机制，首次读取就要等待网络响应

### 修复方案

1. 改为**单 MediaExtractor** 同时选中音视频 track（`selectTrack` 两次），只请求一次 URL
2. 从异步回调模式改为**同步 dequeue 循环**，逻辑更清晰可控
3. `readSampleData()` 按 PTS 顺序自动返回下一个 sample（不管是音频还是视频），通过 `sampleTrackIndex` 区分

```kotlin
// 修复前：双 extractor
val videoExtractor = MediaExtractor().apply { setDataSource(url) }
val audioExtractor = MediaExtractor().apply { setDataSource(url) } // 重复请求！

// 修复后：单 extractor
val ext = MediaExtractor().apply { setDataSource(url) } // 只请求一次
ext.selectTrack(videoTrackIndex)  // 选中视频
ext.selectTrack(audioTrackIndex)  // 选中音频
```

### 经验教训

- MediaExtractor 支持同时 select 多个 track，`readSampleData()` 会按时间顺序返回各 track 的 sample
- 双 extractor 是常见的反模式，除非需要随机 seek 不同 track 的数据
- 网络性能优化首先要减少请求数量

---

## Bug #3: Google Storage 视频 URL 全部返回 403 Forbidden

- **日期**: 2026-05-21
- **严重程度**: 高
- **状态**: 已修复

### 现象

即使修复了播放流程，视频仍然无法播放。Logcat 中 MediaExtractor 报错：`DataSource error` 或 `Failed to open`。

### 复现步骤

1. 用 curl 测试 MockDataSource 中的 URL：
   ```bash
   curl -I https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4
   ```
2. 返回 `HTTP/1.1 403 Forbidden`

### 排查过程

1. ~~检查网络权限~~ — `AndroidManifest.xml` 中 `INTERNET` 权限已配置
2. ~~检查 OkHttp 请求~~ — OkHttp 正常，但视频播放走的是 MediaExtractor 而非 OkHttp
3. ~~直接测试 URL~~ — curl 请求 Google Storage URL 返回 403，确认 URL 已失效
4. ~~批量测试~~ — 13 个 Google Storage URL 全部返回 403

### 根因

**视频源 URL 已失效**。项目中使用的 `commondatastorage.googleapis.com/gtv-videos-bucket/sample/` 路径下的视频已被 Google 移除或限制访问，所有 URL 返回 403 Forbidden。这不是代码问题，而是外部依赖失效。

### 修复方案

替换为公开可访问的视频源：

```kotlin
// 修复前：全部 403
"https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

// 修复后：验证可用的公开源
"https://media.w3.org/2010/05/sintel/trailer.mp4"
"https://media.w3.org/2010/05/bunny/trailer.mp4"
"https://vjs.zencdn.net/v/oceans.mp4"
"https://www.w3schools.com/html/mov_bbb.mp4"
"https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4"
```

### 经验教训

- 外部视频 URL 是项目的脆弱依赖，需要定期验证可用性
- 应该在启动时或后台线程预检测 URL 可达性，而非等到用户滑动到才报错
- 国内网络环境下，Google Storage 本身就可能不可达，选源应考虑 CDN 覆盖

---

> **说明**: 以上 3 个 Bug 均为实际开发中遇到的真实问题，包含完整的排查过程和根因分析。后续开发中如遇到新问题将持续补充。

---

## Bug #4: 单 Extractor 空 buffer 干扰视频解码

- **日期**: 2026-05-21
- **严重程度**: 高
- **状态**: 已修复

### 现象

修复 Bug #2（双 Extractor → 单 Extractor）后，视频反而更卡了。第一个视频勉强能播放但掉帧严重，滑动到第二个视频后完全无法播放。

### 复现步骤

1. 运行应用，第一个视频画面断断续续
2. 滑动到第二个视频，画面完全卡住
3. Logcat 中 MediaCodec 无报错，但无输出帧

### 排查过程

1. ~~检查解码循环~~ — `decodeLoop()` 中发现以下逻辑：
   ```kotlin
   val trackIndex = ext.sampleTrackIndex
   if (trackIndex == videoTrackIndex) {
       vc.queueInputBuffer(inIdx, 0, size, pts, flags) // 正常
   } else {
       vc.queueInputBuffer(inIdx, 0, 0, 0, 0) // ← BUG!
   }
   ```
2. 单 Extractor 同时选中了音视频两个 track，`readSampleData()` 按 PTS 顺序返回下一个 sample
3. MP4 文件中音视频 sample 是交错排列的（音频-视频-音频-视频...）
4. 读到音频 sample 时，代码往**视频 Codec** 送了一个空 buffer（size=0, pts=0）
5. 视频 Codec 将空 buffer 视为有效输入，浪费了一次解码周期
6. 更严重的是：空 buffer 的 pts=0 会干扰 Codec 的帧序依赖判断（I/P/B 帧）

### 根因

**单 Extractor 选中双 track 时，非目标 track 的 sample 会被错误地送入目标 Codec**。视频 Codec 收到空 buffer 后：
1. 浪费解码周期处理无效数据
2. 时间戳错乱（pts=0 破坏帧序）
3. I/P/B 帧依赖关系被打乱，首帧迟迟无法输出

### 修复方案

改为**双 Extractor 各管各 track**：
- `videoExtractor`：仅选中视频 track → 数据送入 `videoCodec`
- `audioExtractor`：仅选中音频 track → 数据写入 `AudioTrack`
- 两个解码循环独立并行，互不干扰

```kotlin
// 修复前：单 extractor，音频 sample 干扰视频 codec
val ext = MediaExtractor().apply { setDataSource(url) }
ext.selectTrack(videoTrackIndex)
ext.selectTrack(audioTrackIndex) // 同时选中，readSampleData 交替返回
// 读到音频 sample 时 → vc.queueInputBuffer(0, 0, 0, 0) ← 空 buffer!

// 修复后：双 extractor 各自独立
val videoExt = MediaExtractor().apply {
    setDataSource(url)
    selectTrack(videoTrackIndex) // 只选视频
}
val audioExt = MediaExtractor().apply {
    setDataSource(url)
    selectTrack(audioTrackIndex) // 只选音频
}
// videoExt.readSampleData() → 只返回视频 sample → 送入 videoCodec ✓
// audioExt.readSampleData() → 只返回音频 sample → 写入 AudioTrack ✓
```

### 经验教训

- MediaExtractor 选中多个 track 后，`readSampleData()` 按文件中 sample 的 PTS 顺序返回，不区分 track
- 不能把非目标 track 的 sample（即使是空 buffer）送入 Codec，会严重干扰解码
- "单 Extractor 省一次连接"的优化思路在有音频 track 时行不通，必须双 Extractor
- 性能优化要先确保正确性，再考虑减少请求次数

---

## Bug #5: 无声音 + 画面卡顿 + 切换视频黑屏

- **日期**: 2026-05-21
- **严重程度**: 高
- **状态**: 已修复

### 现象

1. 视频有画面但无声音
2. 画面显示后卡住不动，需要双击屏幕才跳到下一帧
3. 滑动到第二个视频后完全黑屏，只有第一个视频有画面

### 排查过程

1. ~~检查音频 track~~ — MediaExtractor 确实选中了音频 track，`audioFormat` 不为 null
2. ~~检查 AudioTrack~~ — AudioTrack 已创建并 play()，但 `audioDecodeLoop()` 直接将压缩的 AAC 数据写入 AudioTrack，AudioTrack 需要的是 PCM 数据
3. ~~检查首帧渲染~~ — `syncFrame()` 中 `wallStartTimeUs` 初始为 -1，首帧时设为 `nowUs`，但 `nowUs` 是微秒级的 `System.nanoTime()/1000`，首帧后 `wallElapsed` 几乎为 0，导致后续帧 `mediaElapsed > wallElapsed` 全部跳过渲染
4. ~~检查视频切换~~ — `DisposableEffect` 中 `onDispose` 释放了 player，但重新 prepare 时 `isReleased` 标志可能未正确重置

### 根因

**三个独立问题叠加：**

1. **无声音**：音频数据是 AAC 编码格式，直接写入 AudioTrack 无效。AudioTrack 只接受 PCM 原始数据，需要经过 MediaCodec 解码（AAC → PCM）
2. **画面卡住**：`syncFrame()` 中首帧时间基准设置有问题。首帧立即设了 `wallStartTimeUs = nowUs`，之后 `wallElapsed` 接近 0，而 `mediaElapsed` 为帧间隔（~33ms），条件 `mediaElapsed <= wallElapsed + 30_000` 永远不满足，所有后续帧都被跳过
3. **切换视频黑屏**：`LaunchedEffect(isCurrentVideo)` 中释放 player 后，重新 prepare 时 surface 可能已失效但 `surfaceReady` 状态未重置

### 修复方案

1. **音频解码管线**：新增 `MediaCodec` 解码器（AAC → PCM），音频数据经 MediaCodec 解码后输出 PCM，再写入 AudioTrack
2. **首帧立即渲染**：新增 `firstFrameRendered` 标志，首帧不做同步直接渲染，渲染后再初始化 `mediaStartTimeUs` 和 `wallStartTimeUs`
3. **生命周期修复**：`DisposableEffect` 改为正确的 `onDispose` 模式，确保资源释放和重新初始化的时序正确

```kotlin
// 修复前：音频直接写入（AAC 压缩数据 → AudioTrack 无法播放）
val buffer = ByteArray(4096)
val size = ext.readSampleData(ByteBuffer.wrap(buffer), 0)
track.write(buffer, 0, size) // 写入 AAC 数据，AudioTrack 不认识

// 修复后：音频经 MediaCodec 解码（AAC → PCM → AudioTrack）
audioCodec = MediaCodec.createDecoderByType(mime).apply {
    configure(format, null, null, 0)
    start()
}
// audioLoop() 中：audioExtractor → audioCodec → PCM → AudioTrack
val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
codec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
val pcm = ByteArray(info.size)
buf.get(pcm)
track.write(pcm, 0, info.size) // 写入 PCM 数据 ✓
```

```kotlin
// 修复前：首帧同步逻辑有问题
private fun syncFrame(pts: Long): Boolean {
    if (wallStartTimeUs < 0L) {
        wallStartTimeUs = nowUs      // ← 设为当前时间
        mediaStartTimeUs = pts
        return true
    }
    // 后续帧：mediaElapsed ≈ 33ms, wallElapsed ≈ 0ms → 永远不满足
    return mediaElapsed <= wallElapsed + 30_000
}

// 修复后：首帧立即渲染，不进入 syncFrame
if (!firstFrameRendered && info.size > 0) {
    codec.releaseOutputBuffer(outIdx, true)  // 立即渲染
    firstFrameRendered = true
    mediaStartTimeUs = info.presentationTimeUs
    wallStartTimeUs = System.nanoTime() / 1000  // 渲染后才设基准
    continue  // 跳过后续 sync 逻辑
}
// 后续帧：wallElapsed 已正常递增，sync 正确
```

### 经验教训

- AudioTrack 只接受 PCM 数据，压缩格式（AAC/MP3）必须经过 MediaCodec 解码
- 音视频播放器需要完整的音频解码管线：Extractor → MediaCodec → AudioTrack
- PTS 同步基准必须在首帧渲染**之后**设置，不能在渲染之前就设为当前时间
- 首帧应该无条件立即渲染，降低用户感知的启动延迟

---

## Bug #6: 视频画面黑屏 + 切换视频无法加载（release/prepare 时序竞争）

- **日期**: 2026-05-21
- **严重程度**: 高
- **状态**: 已修复

### 现象

Bug #5 修复后，音频能正常播放，但画面始终为黑色。滑动到第二个视频后完全无响应（无声音无画面），只有第一个视频有音频。

### 排查过程

1. ~~检查 Surface~~ — SurfaceView 正常创建，`surfaceCreated()` 触发
2. ~~检查 videoLoop~~ — 添加日志发现 videoLoop 可能从未开始或立即退出
3. ~~检查 release/prepare 时序~~ — 发现 Compose 中 `DisposableEffect` 的 `onDispose` 和 `LaunchedEffect` 执行顺序不确定
4. ~~深入分析~~ — 当 `currentIndex` 变化时，旧 VideoPlayerView 被移除（`onDispose` 调用 `player.release()`），新 VideoPlayerView 被创建（`LaunchedEffect` 调用 `prepareAndPlay()`）。但 `onDispose` 可能在 `LaunchedEffect` **之后**执行
5. ~~确认竞态~~ — `release()` 中 `isReleased.set(true)` → `currentJob.cancel()`。如果在 `prepareAndPlay()` 已设置 `isReleased = false` 之后执行，会导致：
   - 旧 job 被取消 → 旧资源泄露（codec/extractor 未释放）
   - 新 job 的 decode loop 看到 `isReleased = true`（被旧 release 设置）→ 立即退出
   - 新 job 的 `finally` 块释放了新创建的 codec → Surface 无帧可渲染

### 根因

**release() 和 prepareAndPlay() 之间存在竞态条件**。Compose 的生命周期回调执行顺序不确定：

```
场景：滚动到新视频

可能的执行顺序 A（正常）：
  1. onDispose(旧) → release() → isReleased=true → cancel(旧job)
  2. LaunchedEffect(新) → prepareAndPlay() → isReleased=false → 启动新job
  ✅ 正常工作

可能的执行顺序 B（竞态）：
  1. LaunchedEffect(新) → prepareAndPlay() → isReleased=false → 启动新job
  2. onDispose(旧) → release() → isReleased=true → cancel(新job!!!)
  ❌ 新job被旧release取消，画面黑屏
```

此外，即使顺序正确，`release()` 中直接操作 `isReleased` 标志也可能干扰新 job 的 decode loop。

### 修复方案

**引入 generation 计数器**，替代 `isReleased` 全局标志：

1. 每次 `prepareAndPlay()` 递增 `currentGen`，当前任务使用 `gen` 副本
2. Decode loop 通过 `gen == currentGen` 判断是否继续运行
3. `release()` 只做两件事：递增 `currentGen`（让所有 loop 退出）+ `currentJob.cancel()`
4. 资源释放移入 job 的 `finally` 块，确保每个任务清理自己的资源
5. `VideoPlayerView` 移除 `DisposableEffect`，不再主动调用 `player.release()`（由 ViewModel 管理）

```kotlin
// 修复前：全局标志 + 竞态
private val isReleased = AtomicBoolean(false)

fun prepareAndPlay() {
    isReleased.set(false)  // ← 可能被之后的 release() 覆盖
    currentJob = scope.launch {
        while (!isReleased.get()) { ... }  // ← 可能被旧 release 设置为 true
    }
}

fun release() {
    isReleased.set(true)   // ← 可能干扰新 job
    currentJob?.cancel()   // ← 可能取消了新 job
}

// 修复后：generation 计数器
private var currentGen = 0

fun prepareAndPlay() {
    currentGen++                    // 递增，旧 loop 自动退出
    val gen = currentGen            // 捕获当前 generation
    currentJob?.cancel()            // 取消旧 job
    currentJob = scope.launch {
        try {
            // ... setup codec, extractor ...
            videoLoop(..., gen)     // 传递 gen
        } finally {
            // 释放本代资源
            videoCodec?.release()
            audioExtractor?.release()
            // ...
        }
    }
}

fun release() {
    currentGen++                    // 让所有 loop 退出
    currentJob?.cancel()
}

// videoLoop 中：
while (gen == currentGen) { ... }  // 旧 loop 自然退出
```

### 经验教训

- Compose 的 DisposableEffect 和 LaunchedEffect 执行顺序不确定，不能依赖时序
- 单例 player + 多个 Composable 共享时，不应该在视图层调用 release()
- 全局 AtomicBoolean 标志在 release/prepare 竞态下不可靠
- Generation/Epoch 模式是处理 "取消旧任务启动新任务" 的可靠方案
- 资源释放应该放在创建它们的协程的 finally 块中，而非外部 release() 方法

---

## Bug #7: 音频卡顿 + 音画不同步

- **日期**: 2026-05-22
- **严重程度**: 中
- **状态**: 已修复

### 现象

视频画面正常播放，但音频有明显的卡顿/爆音现象，音画不同步。

### 根因分析

1. **AudioTrack 缓冲区太小**：`minBuf * 2` 在 MODE_STREAM 下不够。网络流解码速率不均匀，小缓冲区无法吸收波动，导致 `write()` 频繁阻塞，产生间隙
2. **音频解码无节奏控制**：`decodeAudio()` 以最快速度向 AudioTrack dump PCM 数据，没有基于播放速率控制写入节奏。解码速度快于播放速率时缓冲区满 → write 阻塞 → 恢复后出现空白
3. **视频同步未使用音频基准**：`checkPts()` 用墙钟同步视频，当有音频时应使用 `AudioTrack.getPlaybackHeadPosition()` 作为主时钟
4. **AudioTrack 使用 ENCODING_PCM_16BIT 但 MediaCodec 输出可能是浮点**：某些 AAC 编码器输出 float PCM，写入 16-bit AudioTrack 会截断产生噪音

### 修复方案

1. 增大 AudioTrack 缓冲区至 `minBuf * 4`，给流式播放更多缓冲空间
2. 音频同步使用 `AudioTrack.getPlaybackHeadPosition()` 获取已播放帧数，换算成时间
3. 有音频时视频以音频为基准同步（master clock），无音频时回退到墙钟
4. AudioTrack 输出格式匹配 MediaCodec 实际输出格式（float vs 16bit）

### 参考

- Android AudioTrack 官方文档建议 MODE_STREAM 缓冲区至少为 `getMinBufferSize()` 的两倍
- ExoPlayer 使用音频 PTS + getPlaybackHeadPosition() 实现 A/V sync
- IJKPlayer 使用音视频 PTS 差值做同步判断

---

## Bug #8: Android 9+ 禁止明文 HTTP 导致视频无法播放

- **日期**: 2026-05-22
- **严重程度**: 高（致命）
- **状态**: 已修复

### 现象

应用 UI 正常显示（标题、作者、点赞按钮等），但视频区域始终为黑色，无任何视频画面。上下滑动切换视频，所有视频均为黑屏。Logcat 中 MediaExtractor 报错但不易察觉。

### 排查过程

1. ~~检查播放流程~~ — `prepareAndPlay()` 逻辑正确，Bug #1 的修复已生效
2. ~~检查视频 URL~~ — 所有 mock URL 经 curl 验证均返回 200 OK
3. ~~检查代理服务器~~ — `VideoProxy.start()` 正常启动，端口正常绑定
4. ~~检查 MediaExtractor 调用~~ — `setDataSource(proxyUrl)` 中 proxyUrl 格式为 `http://127.0.0.1:PORT/proxy?url=...`
5. **发现问题** — `AndroidManifest.xml` 中**缺少** `android:usesCleartextTraffic="true"` 和 `android:networkSecurityConfig`
6. **确认根因** — Android 9 (API 28) 起默认禁止明文 HTTP 流量。`MediaExtractor` 使用原生 HTTP 客户端，不受应用层 Java HTTP 库的异常处理覆盖，连接 `http://127.0.0.1:PORT` 被系统静默拒绝

### 根因

**Android 9+ (API 28) 默认禁止明文 HTTP 通信**。项目中本地视频代理运行在 `http://127.0.0.1:<PORT>`，使用的是明文 HTTP 协议。`MediaExtractor.setDataSource()` 在底层调用原生 `MediaHTTPConnection`（基于 libcurl），该原生 HTTP 客户端受 Android 网络安全策略约束：

- 应用 targetSdk=34，minSdk=26
- 在 API 28+ 设备/模拟器上，默认网络安全策略**禁止所有明文 HTTP**
- `MediaExtractor` 尝试连接 `http://127.0.0.1:PORT` → 被系统拦截 → `setDataSource()` 失败
- 失败后 `videoExtractor.trackCount` 为 0 → 进入 "No video track found" → state = ERROR → 黑屏

与 Bug #1 的区别：Bug #1 是 prepare/play 流程断裂（代码逻辑问题），本 Bug 是网络策略问题（系统安全策略拦截了 HTTP 连接）。

### 修复方案

1. **创建网络安全配置文件** `res/xml/network_security_config.xml`：
```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

2. **更新 AndroidManifest.xml**：
```xml
<application
    android:usesCleartextTraffic="true"
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

3. **修复 VideoProxy HTTP 协议问题**（附带修复）：
   - `serveContent()` 中当上游返回 200 OK 时，不再错误发送 Content-Range 头
   - 上游请求添加 `User-Agent` 和 `Accept` 头，避免 CDN 拒绝无头请求
   - 缓存逻辑修正：仅对非 Range 请求的结果进行缓存

### 经验教训

- Android 9+ 的网络安全策略会影响**所有**网络客户端，包括原生层的 `MediaExtractor`
- 本地 HTTP 代理方案必须在 `network_security_config.xml` 中显式允许 localhost 明文通信
- `android:usesCleartextTraffic="true"` 全局允许 + `networkSecurityConfig` 域名级允许 双保险
- 原生组件（MediaCodec/MediaExtractor）的网络访问不受 Java 层 try-catch 保护，失败可能表现为静默错误

---

## Bug #9: release() 与 decode loop 竞争 MediaCodec 导致 IllegalStateException

- **日期**: 2026-05-22
- **严重程度**: 高
- **状态**: 已修复

### 现象

修复 Bug #8 后，本地 asset 视频可以成功通过代理加载（日志显示 "Asset full: 770857 bytes, Transfer complete"），但随即报错：
```
Playback error [seq=2]: IllegalStateException (codec2)
```
视频仍然黑屏，无画面渲染。

### 排查过程

1. ~~检查代理~~ — 代理正常工作，asset 文件成功读取 770,857 bytes
2. ~~检查 MediaExtractor~~ — `setDataSource` 成功完成
3. **发现时序问题** — 日志显示请求完成 200ms 后即触发 `Manual release, currentSeq=3`，又过了 568ms 才报 `IllegalStateException`
4. **确认竞态** — `release()` 同步执行：递增 seq → `cancel()` 协程 → 旧协程 `finally` 块调用 `codec.stop()/release()`。但此时新的 decode loop 可能已经启动，正在使用同一个 codec2 实例
5. **codec2 敏感性** — Android 12+ 的 codec2 实现对并发操作（stop/release 同时 decode）比旧版 MediaCodec 更敏感，会抛出 `IllegalStateException`

### 根因

**`release()` 与 decode loop 的 MediaCodec 竞争**。时序如下：

```
时间线：
t0: prepareAndPlay(seq=2) 启动 → codec.start() → decode loop 运行中
t1: release() 被调用 → seq 变为 3 → cancel(旧协程)
t2: 旧协程 finally 块 → codec.stop() → codec.release()
t3: prepareAndPlay(seq=3) 启动 → 创建新 codec → codec.start()
    但 t2 和 t3 可能重叠：旧 codec.stop() 正在执行时，新 codec 开始配置
```

问题根源：
1. `release()` 是同步的，不等待旧协程退出
2. `finally` 块中的 `codec.stop()` 可能在新 decode loop 使用 codec 时执行
3. codec2（Android 12+）对这种竞态更敏感，直接抛 `IllegalStateException`

### 修复方案

1. **后台清理协程**：将 `finally` 块中的资源释放改为提交到独立的 `cleanupJob`，延迟 100ms 后执行，确保新的 decode loop 已经启动
2. **decode loop 容错**：在 `videoDecodeLoop` 和 `audioDecodeLoop` 中添加 `try-catch(IllegalStateException)`，codec 被后台清理时安全退出
3. **Volatile 标注**：`activeAudioTrack` 和 `audioSampleRate` 添加 `@Volatile` 确保多线程可见性

```kotlin
// 修复前：finally 直接释放 codec（可能和新 decode loop 竞争）
finally {
    vCodec?.safeRelease()  // ← codec.stop() 可能导致 IllegalStateException
    aCodec?.safeRelease()
}

// 修复后：后台协程延迟释放
finally {
    cleanupJob = scope.launch {
        delay(100)  // 等待新 decode loop 先启动
        try { vc?.stop() } catch (_: Exception) {}
        try { vc?.release() } catch (_: Exception) {}
        // ...
    }
}
```

### 经验教训

- MediaCodec（特别是 codec2）不支持并发的 stop/release + decode 操作
- `release()` 协程 cancel 后的 `finally` 块可能和新协程的 codec 操作重叠
- 后台清理 + 延迟释放 + decode loop 容错 = 三重保护
- `@Volatile` 在多线程 codec 场景下是必要的，不能依赖 AtomicReference 或 synchronized

---

## Bug #10: Asset 双 Extractor 共享 FD + 缺少防御性检查导致播放失败

- **日期**: 2026-05-22
- **严重程度**: 高（致命）
- **状态**: 已修复

### 现象

所有视频（包括本地 asset）均无法播放，黑屏。Logcat 报错：
```
setDataSource FAILED for asset: test_video.mp4
Playback error [seq=2]: IllegalStateException (no message)
```

### 排查过程

1. ~~检查 asset 文件~~ — 文件存在（991KB），`file` 命令确认为标准 ISO Media/MP4
2. ~~检查 openFd~~ — 日志显示 `Asset fd: offset=0, length=991017`，openFd 成功
3. **发现问题** — 两个 MediaExtractor 共享同一个 `AssetFileDescriptor.fileDescriptor`：
   ```kotlin
   videoExtractor.setDataSource(fd, offset, length)  // 第一个 OK
   audioExtractor.setDataSource(fd, offset, length)  // 失败！共享 fd 读取位置冲突
   ```
4. **确认根因** — AssetFileDescriptor 的 fd 是共享的，两个 MediaExtractor 实例各自独立 seek 时互相干扰，第二个 `setDataSource` 失败
5. **附带问题** — 缺少 `surface.isValid` 检查、缺少 `trackCount == 0` 防御、视频尺寸无安全提取

### 根因

**两个 MediaExtractor 共享同一个 AssetFileDescriptor 的 fd**。`AssetFileDescriptor.fileDescriptor` 返回的是底层文件描述符，两个 MediaExtractor 同时对其调用 `setDataSource(fd, offset, length)` 时，它们共享文件偏移指针。第一个 extractor 正常初始化并 seek 到某个位置后，第二个 extractor 的初始化过程会受到干扰，导致 `setDataSource` 抛出异常。

此外缺少多项防御性检查：
- `surface.isValid` 未检查 → Surface 已销毁时 configure 崩溃
- `trackCount == 0` 未检查 → 不支持的文件格式导致空指针
- `KEY_DURATION` / `KEY_WIDTH` / `KEY_HEIGHT` 直接读取 → 某些文件缺少这些字段时崩溃

### 修复方案

**核心修复：Asset 复制到临时文件**

```kotlin
// 修复前：共享 fd
val fd = assetFd.fileDescriptor
videoExtractor.setDataSource(fd, offset, length)  // OK
audioExtractor.setDataSource(fd, offset, length)  // FAIL!

// 修复后：复制到临时文件，各自独立加载
val tempFile = copyAssetToTemp(assetPath)
videoExtractor.setDataSource(tempFile.absolutePath)  // 独立
audioExtractor.setDataSource(tempFile.absolutePath)  // 独立
```

**防御性检查**

```kotlin
// 1. Surface 有效性检查
if (!surface.isValid) {
    Log.e(TAG, "Surface is already invalid, aborting configure")
    return@launch
}

// 2. trackCount 检查
if (videoExtractor.trackCount == 0) {
    Log.e(TAG, "MediaExtractor has 0 tracks! File may be corrupted.")
    return@launch
}

// 3. 安全的格式字段读取
_duration.value = try { videoFormat.getLong(KEY_DURATION) / 1000 } catch (_: Exception) { 0L }
val width = if (videoFormat.containsKey(KEY_WIDTH)) videoFormat.getInteger(KEY_WIDTH) else 0
val height = if (videoFormat.containsKey(KEY_HEIGHT)) videoFormat.getInteger(KEY_HEIGHT) else 0
```

**视频尺寸暴露 + 自适应比例**

```kotlin
// VideoPlayer: 暴露视频尺寸
private val _videoSize = MutableStateFlow(Pair(0, 0))
val videoSize: StateFlow<Pair<Int, Int>> = _videoSize

// VideoPlayerView: 根据视频尺寸调整 SurfaceView 比例
val videoSize by player.videoSize.collectAsState()
modifier = if (videoSize.first > 0 && videoSize.second > 0) {
    Modifier.aspectRatio(videoSize.first.toFloat() / videoSize.second.toFloat())
} else {
    Modifier.fillMaxSize()
}
```

**网络 URL 恢复代理路径**

```kotlin
// FeedScreen: 网络 URL 走代理（缓存 + 流式），本地 Asset 直接传给 Player
val proxyUrl = if (video.url.startsWith("file:///android_asset/")) {
    video.url  // Asset 由 Player 内部处理
} else {
    viewModel.videoProxy.getProxyUrl(video.url)  // 网络 URL 走代理
}
```

### 经验教训

- `AssetFileDescriptor.fileDescriptor` 返回的 fd 是共享的，多个 MediaExtractor 不能共享同一个 fd
- Asset 文件应先复制到临时文件再由 MediaExtractor 加载，避免 fd 共享问题
- MediaCodec 全链路需要防御性检查：Surface 有效性、trackCount、格式字段安全性
- 视频播放器应暴露视频尺寸，UI 层根据实际尺寸自适应渲染比例
- 代理架构对网络 URL 仍有价值（缓存 + Range 支持），不应完全移除

### 里程碑

本 Bug 修复标志着 **V1 基础播放版本** 完成：
- 本地 Asset 视频可正常播放（音画同步）
- 网络 HTTPS 视频可正常播放（通过代理）
- 视频尺寸自适应，画面不变形
- 滑动切换视频可正常工作
- 防御性检查覆盖主要崩溃路径

---

## Bug #11: 代理网络抖动后断开连接导致播放永久停止

- **日期**: 2026-05-22
- **严重程度**: 高
- **状态**: 已修复

### 现象

视频播放过程中，如果网络发生瞬时抖动（如 WiFi 切换、信号弱），视频会卡住，且永远不会自动恢复。卡住后画面冻结，无任何报错日志。

### 排查过程

1. ~~检查 videoDecodeLoop~~ — 时钟重同步逻辑存在但只在有帧输出时触发，网络卡住时 `dequeueOutputBuffer` 返回 -1（TIMEOUT），根本不进入同步逻辑
2. ~~检查 MediaExtractor~~ — MediaExtractor 通过代理读取数据，代理 socket 关闭时 MediaExtractor 收到 EOF
3. **发现根因** — `VideoProxy.serveContent()` 中 `source.read(buffer)` 捕获异常后返回 -1 → break 退出循环 → socket 关闭 → MediaExtractor 收到 EOF → 认为文件结束 → `readSampleData` 返回 -1 → codec 收到 EOS → **播放永久停止**
4. **确认致命性** — 即使网络在 1 秒后恢复，代理已经关闭了 socket，播放器无法感知网络恢复

### 根因

**代理服务器在网络异常时直接断开连接，而非尝试恢复**。`serveContent()` 的流式传输循环中：

```kotlin
// 修复前：网络出错 → break → socket 关闭 → EOF → 播放器停止
val bytesRead = try { source.read(buffer) } catch (e: Exception) { -1 }
if (bytesRead == -1) break  // ← 直接退出，没有重连机会
```

问题链路：
```
网络抖动 → OkHttp source.read() 抛 IOException
→ catch 返回 -1 → break 退出 while 循环
→ response.use{} 结束 → socket 关闭
→ MediaExtractor 的 HTTP 连接收到 EOF
→ readSampleData() 返回 -1（看起来像文件尾）
→ codec.queueInputBuffer(EOS) → codec 输出完毕
→ 播放器状态变为 COMPLETED → 永久停止
```

### 修复方案

**外层重试循环**：网络出错时重连上游 CDN，用 Range 请求从断点继续传输。

```kotlin
// 修复后：外层重试循环 + 内层流式传输
while (isRunning.get() && maxRetries > 0) {
    // 用 Range 请求从 currentPosition 继续
    val request = Request.Builder()
        .url(url)
        .addHeader("Range", "bytes=$currentPosition-")  // 从断点续传
        .build()

    NetworkClient.client.newCall(request).execute().use { response ->
        // 首次连接发送 HTTP 头，重连不重复发送
        if (!headerSent) { output.write(httpHeader); headerSent = true }

        // 重连时如果 CDN 不支持 Range，放弃（避免重复数据）
        if (headerSent && response.code != 206) { return }

        val source = body.source()
        while (isRunning.get()) {
            val bytesRead = try { source.read(buffer) }
                catch (e: Exception) { streamError = true; break }  // 跳出内层，进入重连

            if (bytesRead == -1) break  // 正常 EOF
            output.write(buffer, 0, bytesRead)
            currentPosition += bytesRead
        }

        if (!streamError) return  // 正常传输完毕
    }
    maxRetries--
    Thread.sleep(500)  // 等待网络恢复
}
```

关键设计：
- `headerSent` 标志：HTTP 响应头只发一次，重连不重复发送（否则播放器解析异常）
- `currentPosition` 追踪：重连时用 Range 请求从断点续传
- 重连时检查 `response.code == 206`：如果 CDN 不返回 206，说明不支持 Range，放弃重连
- `maxRetries = 3`：最多重试 3 次，避免无限循环
- 重连间隔 500ms：给网络恢复的时间

### 经验教训

- 代理服务器是 MediaExtractor 和 CDN 之间的桥梁，代理断开 = 播放器看到 EOF = 视频结束
- 网络瞬时抖动是常态（WiFi 切换、信号弱），代理必须有重连机制
- 重连必须用 Range 请求从断点续传，否则播放器收到重复数据
- HTTP 响应头在整个传输过程中只能发一次，重连时不重复发送

