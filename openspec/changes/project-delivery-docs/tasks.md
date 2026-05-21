## 1. README.md 完善

- [x] 1.1 创建/完善 README.md 基础结构（项目名称、简介、徽章）
- [x] 1.2 编写项目架构图（Mermaid 格式，展示 UI/播放器/缓存/网络/数据各层）
- [x] 1.3 编写运行指南（环境要求、克隆、导入 Android Studio、同步 Gradle、运行）
- [x] 1.4 创建功能勾选表（A1-A11 所有需求的完成状态 checkbox）
- [x] 1.5 补充项目截图占位（预留 Demo 截图位置）

## 2. AI_USAGE.md 创建与填写

- [x] 2.1 创建 AI_USAGE.md 文件结构（标题、目录、分类说明）
- [x] 2.2 填写采纳案例 #1：UI 框架选择 — Jetpack Compose 方案
- [x] 2.3 填写采纳案例 #2：MediaCodec 异步回调模式方案
- [x] 2.4 填写采纳案例 #3：三级缓存架构设计
- [x] 2.5 填写采纳案例 #4：本地代理边下边播方案
- [x] 2.6 填写采纳案例 #5：Compose LazyColumn 全屏翻页方案
- [x] 2.7 填写未采纳案例 #1：使用 ExoPlayer 方案（违反禁用要求）
- [x] 2.8 填写未采纳案例 #2：使用 Retrofit 高级特性方案（违反裸用 OkHttp 要求）
- [x] 2.9 填写未采纳案例 #3：同步 MediaCodec 模式方案（性能风险）

## 3. DECISIONS.md 创建与填写

- [x] 3.1 创建 DECISIONS.md 文件结构（标题、目录）
- [x] 3.2 填写 ADR-001：UI 框架选择 — Jetpack Compose vs 传统 View
- [x] 3.3 填写 ADR-002：视频列表方案 — LazyColumn 全屏翻页 vs ViewPager2
- [x] 3.4 填写 ADR-003：视频解码方案 — MediaCodec 异步模式 vs 同步模式
- [x] 3.5 填写 ADR-004：缓存架构 — 三级缓存 LRU 淘汰策略
- [x] 3.6 填写 ADR-005：边下边播方案 — 自实现本地 HTTP 代理 vs 直接下载
- [x] 3.7 填写 ADR-006（可选）：弱网降级策略 — 带宽检测与码率切换

## 4. BUGFIX.md 创建与填写

- [x] 4.1 创建 BUGFIX.md 文件结构（标题、目录）
- [x] 4.2 填写 Bug #1：MediaCodec 首帧渲染超时问题（预留占位，开发时填写）
- [x] 4.3 填写 Bug #2：Surface 销毁后 MediaCodec 崩溃问题（预留占位）
- [x] 4.4 填写 Bug #3：内存泄漏 — MediaCodec 未释放问题（预留占位）
- [x] 4.5 填写 Bug #4（可选）：音视频同步偏移问题（预留占位）
- [x] 4.6 填写 Bug #5（可选）：缓存文件损坏导致播放失败（预留占位）

## 5. 性能报告指引文档

- [x] 5.1 编写 Profiler CPU 采集指引（步骤 + 截图说明）
- [x] 5.2 编写 Profiler Memory 采集指引（步骤 + 峰值 < 200MB 验证说明）
- [x] 5.3 编写 Profiler Network 采集指引（步骤 + 带宽曲线说明）
- [x] 5.4 编写 Perfetto 帧率采集指引（adb perfetto 命令 + ui.perfetto.dev 分析）
- [x] 5.5 编写 Systrace 帧率采集指引（systrace.py 命令 + 帧耗时分析）
- [x] 5.6 编写 Demo 视频录制指引（scrcpy/screenrecord + 内容要求 + 时长要求）
- [x] 5.7 将性能报告指引整合到 README.md 或单独 PERFORMANCE.md 中

## 6. 文档交叉检查与整合

- [x] 6.1 检查 README.md 功能勾选表与实际任务状态一致
- [x] 6.2 检查 DECISIONS.md 与 design.md 中决策内容一致
- [x] 6.3 确保所有文档中的项目名称、技术栈描述统一
- [x] 6.4 在 README.md 中添加指向其他文档的链接
