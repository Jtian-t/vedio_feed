## Context

本项目需要从零实现一个抖音风格的短视频 Feed 安卓客户端。核心挑战在于禁用所有高层播放器封装（ExoPlayer/IjkPlayer），直接使用 Android 底层 MediaCodec API 完成视频解码和渲染，同时保证流畅的用户体验（首帧 < 800ms，95% 帧 >= 55fps）。

技术约束：
- 语言：Kotlin
- UI：Jetpack Compose
- 异步：Coroutines + Flow
- 网络：OkHttp（裸用，禁用 Retrofit 高级特性，必须自己写 Interceptor）
- 性能工具：Android Studio Profiler、Perfetto、Systrace

## Goals / Non-Goals

**Goals:**
- 实现基于 MediaCodec 的自研视频解码渲染管线
- 实现上下滑全屏切换的无限视频流，支持下拉刷新和上拉加载更多
- 实现智能预加载和三级缓存系统（内存 + 磁盘 + 网络）
- 实现边下边播（HTTP Range + 本地代理）
- 实现弱网降级和后台资源管理
- 满足所有验收标准（首帧、帧率、内存、崩溃率、缓存命中率）

**Non-Goals:**
- 不实现真实后端服务，仅使用 mock 数据
- 不支持直播流
- 不实现用户登录/注册系统
- 不实现评论的真实存储和同步

## Decisions

### D1: UI 框架选择 — Jetpack Compose

**选择**: Jetpack Compose

**理由**:
1. 声明式 UI 更适合构建动态 Feed 列表
2. 内置动画支持，滑动切换更自然
3. 与 Coroutines/Flow 深度集成
4. 项目要求中 Compose 为首选方案

**备选**: 传统 View + RecyclerView + ViewPager2 — 更成熟但代码量更大

### D2: 视频列表方案 — Compose LazyColumn + 状态驱动

**选择**: 使用 Compose 的 `LazyColumn` + `PagerState` 实现全屏翻页效果，每个 item 占满一屏

**理由**:
1. Compose 原生方案，无需引入 ViewPager2
2. `LazyColumn` 配合 `Modifier.fillMaxSize()` 可实现全屏 item
3. 通过 `visibleItemIndex` 状态管理当前播放项
4. 滑动检测简单，可直接用 `onScrollStopped` 回调

**备选**: ViewPager2（更传统但需要桥接 Compose/View）

### D3: 视频解码架构 — MediaCodec 异步模式

**选择**: MediaCodec 异步回调模式 + SurfaceView 渲染

**架构**:
```
VideoPlayer (Compose)
  └─ AndroidView(SurfaceView)
       └─ MediaPlayer (核心播放器)
            ├─ MediaCodec (H.264/H.265 解码)
            ├─ MediaExtractor (容器解析)
            ├─ Surface → SurfaceView (视频渲染)
            └─ AudioTrack (音频播放)
```

**关键设计**:
- 每个视频实例维护独立的 MediaCodec + MediaExtractor
- 使用 `MediaCodec.setCallback()` 异步模式避免阻塞
- 解码线程与渲染线程分离
- 音频使用 AudioTrack 同步播放

**备选**: 同步模式 — 更简单但容易阻塞 UI 线程

### D4: 预加载策略 — 基于滑动方向的 N 个视频预加载

**策略**:
- 预加载窗口：当前视频前后各 N 个（N=2，可配置）
- 仅预加载元数据和前几秒的缓存，不完整下载
- 滑动时动态调整预加载窗口
- 使用 OkHttp 的 `enqueue` 异步下载，不阻塞 UI

### D5: 三级缓存架构

**设计**:
```
L1: 内存缓存 (LruCache<String, ByteArray>)
  → 已解码的视频帧或前几秒数据
  → 容量限制：50MB

L2: 磁盘缓存 (File-based, LRU 淘汰)
  → 按 URL hash 存储文件片段
  → 容量限制：500MB
  → 支持断点续传 (HTTP Range)

L3: 网络 (OkHttp + 自定义 Interceptor)
  → Range 请求支持
  → 带宽检测
```

**本地代理方案**（边下边播）:
- 启动本地 HTTP 代理服务（localhost:port）
- 播放器请求本地代理 → 代理从网络/缓存获取 → 回传给播放器
- 类似 AndroidVideoCache 思路，完全自实现

### D6: 弱网降级

**策略**:
- OkHttp Interceptor 监测下载速度
- 滑动平均速度低于阈值时，切换到低码率 mock URL
- 恢复高带宽时自动切回高码率

## Risks / Trade-offs

### R1: MediaCodec 兼容性风险
**风险**: 不同 Android 版本和设备的 MediaCodec 行为差异
**缓解**: 优先支持 API 26+，增加设备兼容性测试，提供降级策略

### R2: 性能达标风险
**风险**: 纯自研解码可能难以达到 800ms 首帧要求
**缓解**: 优化预加载时机，使用 SurfaceView（比 TextureView 性能更好），提前初始化 Codec

### R3: 内存管理风险
**风险**: 多个 MediaCodec 实例可能导致内存泄漏
**缓解**: 严格管理生命周期，使用 LeakCanary 监测，及时释放 Codec 资源

### R4: 边下边播复杂度
**风险**: 自实现本地代理方案复杂度高
**缓解**: 先实现基础版本（完整下载再播放），再迭代加入代理方案

## Migration Plan

（全新项目，无需迁移）
