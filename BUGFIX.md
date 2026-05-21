# BUGFIX.md

## 目录

- [Bug #1: 视频无法加载播放 — prepare/play 流程断裂](#bug-1-视频无法加载播放--mediacodec-初始化与播放流程断裂)
- [Bug #2: 视频加载极慢 — 双 MediaExtractor 重复请求](#bug-2-视频加载极慢--双-mediaextractor-重复请求同一-url)
- [Bug #3: Google Storage URL 返回 403 Forbidden](#bug-3-google-storage-视频-url-全部返回-403-forbidden)

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
