# AI_USAGE.md

## 目录

- [采纳案例](#采纳案例)
  - [案例 1: UI 框架选择 — Jetpack Compose](#案例-1-ui-框架选择--jetpack-compose)
  - [案例 2: MediaCodec 异步回调模式](#案例-2-mediacodec-异步回调模式)
  - [案例 3: 三级缓存架构设计](#案例-3-三级缓存架构设计)
  - [案例 4: 本地代理边下边播方案](#案例-4-本地代理边下边播方案)
  - [案例 5: Compose LazyColumn 全屏翻页](#案例-5-compose-lazycolumn-全屏翻页)
- [未采纳案例](#未采纳案例)
  - [案例 6: 使用 ExoPlayer 方案](#案例-6-使用-exoplayer-方案)
  - [案例 7: 使用 Retrofit 高级特性](#案例-7-使用-retrofit-高级特性)
  - [案例 8: 同步 MediaCodec 模式](#案例-8-同步-mediacodec-模式)

---

## 采纳案例

### 案例 1: UI 框架选择 — Jetpack Compose

- **日期**: 2026-05-21
- **场景**: 选择 Android UI 框架，项目要求 Jetpack Compose 或传统 View 任选
- **AI 方案**: 推荐使用 Jetpack Compose，理由：声明式 UI 更适合动态 Feed 列表、内置动画支持、与 Coroutines/Flow 深度集成
- **采纳**: 是
- **理由**: 1) 声明式范式与短视频 Feed 的动态数据流天然契合；2) LazyColumn 可直接实现全屏 item 布局，无需额外引入 ViewPager2；3) 团队（个人项目）对现代 UI 框架的学习意愿强
- **效果**: Compose 大幅减少了 UI 代码量，滑动动画实现更简洁

---

### 案例 2: MediaCodec 异步回调模式

- **日期**: 2026-05-21
- **场景**: 设计 MediaCodec 视频解码架构，需要选择同步或异步模式
- **AI 方案**: 推荐使用 `MediaCodec.setCallback()` 异步回调模式，解码线程与渲染线程分离
- **采纳**: 是
- **理由**: 1) 同步模式需要手动管理 dequeue/input/output 循环，容易阻塞 UI 线程；2) 异步模式通过回调自动驱动解码流程，代码结构更清晰；3) 与 Coroutines 配合更自然，可用 Channel 传递解码事件
- **效果**: 解码逻辑与 UI 逻辑解耦，播放控制（暂停/倍速）实现更直观

---

### 案例 3: 三级缓存架构设计

- **日期**: 2026-05-21
- **场景**: 设计视频缓存系统，满足"三级缓存 + LRU 淘汰 + 断点续传"需求
- **AI 方案**: L1 内存 LruCache(50MB) + L2 磁盘 File LRU(500MB) + L3 网络，按 URL hash 做缓存键，查询链：内存 → 磁盘 → 网络
- **采纳**: 是
- **理由**: 1) 三级缓存是 Android 图片/视频加载的标准架构（参考 Glide 思路），成熟可靠；2) LruCache 天然适合"最近观看的视频最可能再看"的场景；3) 分层设计便于单独优化每一层（如内存层可缓存解码头信息，磁盘层存视频片段）
- **效果**: 重复观看场景下缓存命中率目标 >= 90%，首帧时间显著降低

---

### 案例 4: 本地代理边下边播方案

- **日期**: 2026-05-21
- **场景**: 实现"边下边播"功能，类似 AndroidVideoCache 的思路
- **AI 方案**: 启动本地 HTTP 代理服务（localhost:port），播放器请求代理 → 代理通过 OkHttp 从网络/缓存获取 → 回传给播放器，支持 HTTP Range 请求和断点续传
- **采纳**: 是
- **理由**: 1) MediaCodec 的 MediaExtractor 支持 HTTP 数据源，但不支持 Range 请求和缓存，本地代理可以在中间拦截实现这些能力；2) 方案成熟（AndroidVideoCache 验证过），但要求自实现，正好锻炼网络编程能力；3) 代理层天然可集成缓存查询逻辑
- **效果**: 实现了边下边播和断点续传，播放首帧不需要等待完整下载

---

### 案例 5: Compose LazyColumn 全屏翻页

- **日期**: 2026-05-21
- **场景**: 在 Compose 中实现抖音风格的全屏视频翻页效果
- **AI 方案**: 使用 `LazyColumn` 配合 `Modifier.fillMaxSize()` 实现全屏 item，通过 `visibleItemIndex` 管理当前播放项，`onScrollStopped` 检测滑动结束
- **采纳**: 是
- **理由**: 1) LazyColumn 是 Compose 原生方案，无需桥接 View 系统的 ViewPager2；2) 惰性加载天然支持大量数据，内存占用低；3) 通过状态管理当前播放项，比 ViewPager2 的 adapter 模式更简洁
- **效果**: 全屏翻页效果流畅，代码量比 ViewPager2 方案减少约 40%

---

## 未采纳案例

### 案例 6: 使用 ExoPlayer 方案

- **日期**: 2026-05-21
- **场景**: 选择视频播放器方案
- **AI 方案**: 推荐使用 ExoPlayer，功能完善、性能优秀、社区活跃
- **采纳**: 否
- **理由**: 项目明确要求**禁用 ExoPlayer 等高层封装**，必须使用 MediaCodec 底层 API 自行实现。使用 ExoPlayer 违反项目核心约束，且无法达成"深入掌握音视频底层技术"的学习目标
- **实际方案**: 自研 MediaCodec + MediaExtractor + AudioTrack 播放管线

---

### 案例 7: 使用 Retrofit 高级特性

- **日期**: 2026-05-21
- **场景**: 网络请求层设计
- **AI 方案**: 推荐使用 Retrofit + OkHttp，通过注解定义 API 接口，自动生成网络请求代码
- **采纳**: 否
- **理由**: 项目要求"OkHttp 裸用，禁用 Retrofit 的高级特性，必须自己写 Interceptor"。Retrofit 的注解和动态代理机制属于高层封装，绕过了手动管理请求/响应的过程，不符合项目要求
- **实际方案**: 直接使用 OkHttp，手动构建 Request/Response，自写 Interceptor 处理缓存、带宽检测等逻辑

---

### 案例 8: 同步 MediaCodec 模式

- **日期**: 2026-05-21
- **场景**: MediaCodec 解码模式选择
- **AI 方案**: 推荐同步模式（dequeueInputBuffer → queueInputBuffer → dequeueOutputBuffer 循环），代码更直观，更容易理解
- **采纳**: 否
- **理由**: 1) 同步模式需要在循环中阻塞等待，容易卡住 UI 线程导致掉帧；2) 暂停/恢复/倍速等控制逻辑在同步模式下实现复杂，需要打断循环再重建；3) 异步模式通过回调驱动，天然支持状态切换
- **实际方案**: MediaCodec 异步回调模式
