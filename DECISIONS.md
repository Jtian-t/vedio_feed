# DECISIONS.md

## 目录

- [ADR-001: UI 框架选择 — Jetpack Compose](#adr-001-ui-框架选择--jetpack-compose)
- [ADR-002: 视频列表方案 — LazyColumn 全屏翻页](#adr-002-视频列表方案--lazycolumn-全屏翻页)
- [ADR-003: 视频解码方案 — MediaCodec 异步模式](#adr-003-视频解码方案--mediacodec-异步模式)
- [ADR-004: 缓存架构 — 三级缓存 LRU 淘汰策略](#adr-004-缓存架构--三级缓存-lru-淘汰策略)
- [ADR-005: 边下边播方案 — 自实现本地 HTTP 代理](#adr-005-边下边播方案--自实现本地-http-代理)
- [ADR-006: 弱网降级策略 — 带宽检测与码率切换](#adr-006-弱网降级策略--带宽检测与码率切换)

---

## ADR-001: UI 框架选择 — Jetpack Compose

**Status**: Accepted

**Context**: 项目需要构建一个全屏短视频 Feed 列表 UI。项目要求提供 Jetpack Compose 或传统 View 的选择理由。

**Decision**: 使用 Jetpack Compose 作为 UI 框架。

**Rationale**:
1. 声明式 UI 范式与短视频 Feed 的动态数据流天然契合，状态变化自动驱动 UI 更新
2. LazyColumn 可直接实现全屏 item 布局，无需引入 ViewPager2
3. 内置动画支持，滑动切换效果实现更简洁
4. 与 Coroutines/Flow 深度集成，数据流驱动 UI
5. 代码量更少，可维护性更高

**Alternatives Considered**:
- 传统 View + RecyclerView + ViewPager2：更成熟，但代码量大，View/Adapter 模式繁琐
- Flutter：跨平台但不符合 Kotlin 技术栈要求

**Consequences**: 需要学习 Compose 的声明式编程范式；部分底层 View（如 SurfaceView）需要通过 AndroidView 桥接。

---

## ADR-002: 视频列表方案 — LazyColumn 全屏翻页

**Status**: Accepted

**Context**: 需要实现抖音风格的全屏视频上下滑翻页效果，在 Compose 框架下选择具体方案。

**Decision**: 使用 Compose LazyColumn + Modifier.fillMaxSize() 实现全屏 item，通过状态管理当前播放项。

**Rationale**:
1. LazyColumn 是 Compose 原生组件，无需桥接 View 系统
2. 惰性加载天然支持大数据量，非可见 item 自动回收
3. 通过 visibleItemIndex 状态管理当前播放视频，比 ViewPager2 的 adapter 模式更简洁
4. onScrollStopped 回调可精确检测滑动结束事件

**Alternatives Considered**:
- ViewPager2：更传统的翻页方案，但需要通过 AndroidView 桥接 Compose，增加复杂度
- Compose Pager（Accompanist）：第三方库，可能引入额外依赖

**Consequences**: 需要自行管理滑动惯性、边界检测等细节；全屏 item 的测量和布局需要精确控制。

---

## ADR-003: 视频解码方案 — MediaCodec 异步模式

**Status**: Accepted

**Context**: 项目禁用 ExoPlayer，必须使用 MediaCodec 底层 API 自研解码渲染管线。需要选择同步或异步解码模式。

**Decision**: 使用 MediaCodec 异步回调模式（setCallback），解码线程与渲染线程分离。

**Rationale**:
1. 同步模式需要手动管理 dequeue/input/output 循环，容易阻塞 UI 线程
2. 异步模式通过回调自动驱动解码流程，代码结构更清晰
3. 暂停/恢复/倍速等控制在异步模式下更自然（通过控制输入 buffer 的送入速率）
4. 与 Coroutines 配合更自然，可用 Channel 传递解码事件

**Alternatives Considered**:
- 同步模式：更直观，但阻塞式循环不适合需要频繁暂停/恢复的场景
- 第三方库（FFmpeg JNI）：增加编译复杂度，且项目要求底层 API 自实现

**Consequences**: 异步模式的错误处理更复杂；需要仔细管理 MediaCodec 生命周期以避免内存泄漏。

---

## ADR-004: 缓存架构 — 三级缓存 LRU 淘汰策略

**Status**: Accepted

**Context**: 需要实现"三级缓存 + LRU 淘汰 + 断点续传"功能，提高视频加载速度和缓存命中率。

**Decision**: L1 内存缓存（LruCache, 50MB）+ L2 磁盘缓存（File-based LRU, 500MB）+ L3 网络层，按 URL hash 做缓存键。

**Rationale**:
1. 三级缓存是 Android 媒体加载的标准架构（参考 Glide/Picasso），成熟可靠
2. LRU 淘汰策略天然适合"最近观看的视频最可能再看"的场景
3. 分层设计便于独立优化：内存层缓存元数据和前几秒数据，磁盘层存完整片段
4. URL hash 作为缓存键简单高效，碰撞概率极低

**Alternatives Considered**:
- 仅两级缓存（内存 + 网络）：无法持久化，重启应用后缓存丢失
- 数据库缓存：增加复杂度，对于视频这种大二进制数据不适合
- SQLite + 文件混合：管理复杂，纯文件方案更直接

**Consequences**: 磁盘缓存需要定期清理；多设备间缓存不共享；首次观看仍需完整网络请求。

---

## ADR-005: 边下边播方案 — 自实现本地 HTTP 代理

**Status**: Accepted

**Context**: 需要实现"边下边播"功能，让视频不需要完整下载即可开始播放。MediaCodec 的 MediaExtractor 支持 HTTP 数据源但不支持 Range 请求和缓存。

**Decision**: 自实现本地 HTTP 代理服务（类似 AndroidVideoCache 思路），播放器请求代理 → 代理从网络/缓存获取数据 → 回传给播放器。

**Rationale**:
1. 本地代理在 MediaCodec 和网络之间插入一层，可透明地实现 Range 请求、缓存查询、断点续传
2. 方案成熟（AndroidVideoCache 验证过），但要求自实现，正好锻炼网络编程能力
3. 代理层天然可集成三级缓存查询逻辑
4. 对 MediaCodec 完全透明，无需修改播放器逻辑

**Alternatives Considered**:
- 直接下载完整文件再播放：简单但首帧时间长，无法满足 < 800ms 要求
- 自定义 DataSource（类似 ExoPlayer 的 DataSource）：需要修改 MediaExtractor 的数据源实现，复杂度高
- 使用 OkHttp 下载到文件再用 MediaPlayer 播放：无法实现真正的"边下边播"

**Consequences**: 增加了系统复杂度；本地代理服务需要管理端口和生命周期；需要处理代理服务异常的情况。

---

## ADR-006: 弱网降级策略 — 带宽检测与码率切换

**Status**: Accepted

**Context**: 需要实现"检测带宽自动切换清晰度"功能，在网络条件差时自动降级以保证播放流畅。

**Decision**: 通过 OkHttp Interceptor 监测下载速度（滑动平均），低于阈值时切换到低码率 URL，恢复后自动切回。

**Rationale**:
1. Interceptor 是 OkHttp 的标准扩展点，可在不侵入业务代码的情况下监测所有网络请求
2. 滑动平均算法可平滑瞬时波动，避免频繁切换
3. Mock 数据中提供多码率 URL，天然支持切换逻辑
4. 与缓存系统解耦，切换逻辑在请求层处理

**Alternatives Considered**:
- 基于丢包率的检测：需要更底层的网络访问，Android 上实现复杂
- ABR（自适应码率）算法：更智能但过度设计，mock 场景下简单阈值即可
- 手动切换：不符合自动化要求

**Consequences**: 阈值需要根据实际测试调整；切换时可能出现短暂卡顿；需要在 mock 数据中准备多码率版本。
