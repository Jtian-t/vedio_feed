## 1. 项目初始化与基础架构

- [x] 1.1 创建 Android 项目，配置 Kotlin、Jetpack Compose、Coroutines/Flow 依赖
- [x] 1.2 配置 OkHttp 依赖，创建自定义 Interceptor 基础框架
- [x] 1.3 创建项目包结构：data/domain/ui/player/cache 分层架构
- [x] 1.4 配置 AndroidManifest.xml 权限（INTERNET、ACCESS_NETWORK_STATE）
- [x] 1.5 配置 LeakCanary 依赖（可选）

## 2. Mock 数据层

- [x] 2.1 创建 mock JSON 文件，包含 100+ MP4/HLS 视频 URL
- [x] 2.2 创建 Video 数据模型类（id, url, title, author, likeCount, commentCount 等）
- [x] 2.3 实现 MockDataSource，支持分页加载（每页 10-20 条）
- [x] 2.4 实现 Repository 层，封装数据获取逻辑

## 3. MediaCodec 视频解码与渲染引擎

- [x] 3.1 实现 MediaPlayer 核心类：MediaCodec + MediaExtractor 初始化
- [x] 3.2 实现 MediaCodec 异步回调模式解码（setCallback）
- [x] 3.3 实现 Surface 渲染：MediaCodec 输出 → SurfaceView
- [x] 3.4 实现 AudioTrack 音频播放与音视频同步
- [x] 3.5 实现播放控制：play/pause/seekTo/倍速
- [x] 3.6 实现进度回调（每秒更新当前播放时间）
- [x] 3.7 处理 MediaCodec 生命周期：start/stop/release

## 4. 视频 Feed 列表 UI（Jetpack Compose）

- [x] 4.1 实现全屏视频播放 Composable（AndroidView 包裹 SurfaceView）
- [x] 4.2 实现 LazyColumn 全屏 item 布局（fillMaxSize）
- [x] 4.3 实现上下滑检测与视频切换逻辑（scrollState 监听）
- [x] 4.4 实现可见性检测：仅播放当前可见 item 的视频
- [x] 4.5 实现视频信息叠加层（标题、作者、操作按钮）

## 5. 下拉刷新与上拉加载更多

- [x] 5.1 实现下拉刷新逻辑（PullRefresh 或自定义手势检测）
- [x] 5.2 实现刷新指示器 UI
- [x] 5.3 实现上拉加载更多触发检测（滚动到底部附近）
- [x] 5.4 实现加载更多指示器 UI
- [x] 5.5 实现"没有更多了"状态处理

## 6. 三级缓存系统

- [x] 6.1 实现 L1 内存缓存（LruCache<String, ByteArray>，50MB 上限）
- [x] 6.2 实现 L2 磁盘缓存（File-based，LRU 淘汰，500MB 上限）
- [x] 6.3 实现缓存键生成策略（URL hash）
- [x] 6.4 实现缓存查询链：内存 → 磁盘 → 网络

## 7. 边下边播（本地代理）

- [x] 7.1 实现本地 HTTP 代理服务器（ServerSocket，localhost:port）
- [x] 7.2 实现 HTTP Range 请求解析与响应
- [x] 7.3 实现代理 → OkHttp 网络请求 → 缓存 → 回传链路
- [x] 7.4 实现断点续传支持
- [x] 7.5 将 MediaPlayer 的数据源切换为本地代理 URL

## 8. 智能预加载

- [x] 8.1 实现预加载管理器（PreloadManager），管理预加载窗口
- [x] 8.2 实现基于滑动方向的预加载调度
- [x] 8.3 实现预加载任务优先级控制（低优先级网络请求）
- [x] 8.4 实现预加载任务取消机制（滑出窗口时取消）
- [x] 8.5 编写预加载策略文档

## 9. UI 交互功能

- [x] 9.1 实现点赞按钮 UI 与本地状态管理
- [x] 9.2 实现点赞数显示与动画
- [x] 9.3 实现评论输入框 UI
- [x] 9.4 实现评论列表展示（本地数据）
- [x] 9.5 实现操作按钮布局（右侧：点赞、评论、分享）

## 10. 弱网降级与资源管理

- [x] 10.1 实现 OkHttp Interceptor 带宽检测（下载速度滑动平均）
- [x] 10.2 实现弱网降级切换逻辑（低阈值 → 低码率 URL）
- [x] 10.3 实现网络恢复自动切回高码率
- [x] 10.4 实现 onPause 释放 MediaCodec 资源
- [x] 10.5 实现 onResume 从上次进度快速恢复播放
- [x] 10.6 实现内存压力回调下的资源释放

## 11. 性能优化与测试

- [x] 11.1 优化首帧时间：prepareAndPlay 自动播放，准备完成后立即开始解码渲染
- [x] 11.2 优化滑动流畅度：仅当前视频创建 SurfaceView，减少 Compose 重组
- [ ] 11.3 执行 200 次自动滑动测试，验证 0 崩溃
- [ ] 11.4 使用 Perfetto/Systrace 采集帧率数据，验证 95% >= 55fps
- [ ] 11.5 使用 Android Studio Profiler 验证内存峰值 < 200MB
- [ ] 11.6 测试重复观看场景，验证缓存命中率 >= 90%
