## Why

开发一个自研短视频 Feed 安卓客户端应用，支持上下滑无限视频流、下拉刷新和上拉加载更多。项目要求禁用 ExoPlayer 等高层封装，直接基于 Android 底层 MediaCodec API 自行完成解码、渲染、预加载与列表调度，以深入掌握音视频核心技术。

## What Changes

- 从零搭建 Android 短视频 Feed 应用，使用 Kotlin + Jetpack Compose
- 自实现 MediaCodec 视频解码与 SurfaceView 渲染，禁用 ExoPlayer/IjkPlayer
- 实现上下滑全屏切换的无限视频流，支持下拉刷新和上拉加载更多
- 实现视频播放控制：点击暂停、长按倍速、进度条
- 简单点赞/评论 UI（纯本地状态）
- 提供 mock JSON 数据源（100+ 视频 URL）
- 智能预加载策略与三级缓存（内存 + 磁盘 + 网络）
- 边下边播：HTTP Range 请求 + 本地代理
- 弱网降级：检测带宽自动切换清晰度
- 后台资源释放：onPause 释放 Codec，onResume 快速恢复

## Capabilities

### New Capabilities

- `video-playback`: 基于 MediaCodec 的视频解码与 SurfaceView 渲染，包括播放/暂停/倍速/进度控制
- `feed-list`: 上下滑无限视频流列表，支持下拉刷新和上拉加载更多，ViewPager2 + RecyclerView 方案
- `video-cache`: 三级缓存（内存 + 磁盘 + 网络），LRU 淘汰策略，边下边播本地代理
- `video-preload`: 智能预加载策略，根据滑动方向和网络状况动态调整预加载数量
- `mock-data`: Mock JSON 数据源，提供 100+ HLS/MP4 视频 URL
- `ui-interactions`: 点赞/评论 UI 交互，点击暂停、长按倍速等手势控制
- `performance-monitoring`: 性能监控与弱网降级，资源管理

### Modified Capabilities

（无，此为全新项目）

## Impact

- **技术栈**: Kotlin + Jetpack Compose + Coroutines/Flow + OkHttp（裸用，自写 Interceptor）
- **新依赖**: OkHttp、Jetpack Compose、Coroutines、LeakCanary（可选）
- **性能要求**: 首帧 < 800ms，95% 帧 >= 55fps，内存峰值 < 200MB，0 崩溃
- **开发工具**: Android Studio Profiler、Perfetto、Systrace
