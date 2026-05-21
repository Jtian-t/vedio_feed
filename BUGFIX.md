# BUGFIX.md

## 目录

- [Bug #1: MediaCodec 首帧渲染超时](#bug-1-mediacodec-首帧渲染超时)
- [Bug #2: Surface 销毁后 MediaCodec 崩溃](#bug-2-surface-销毁后-mediacodec-崩溃)
- [Bug #3: 内存泄漏 — MediaCodec 未释放](#bug-3-内存泄漏--mediacodec-未释放)

---

## Bug #1: MediaCodec 首帧渲染超时

- **日期**: （开发时填写）
- **严重程度**: 高
- **状态**: 待开发时填写

### 现象

视频切换后首帧渲染时间远超 800ms，有时需要 2-3 秒才出现画面。

### 复现步骤

1. 播放视频 A
2. 快速滑动到视频 B
3. 观察首帧出现时间

### 排查过程

1. ~~检查网络请求耗时~~ — 网络下载正常，不是瓶颈
2. ~~检查 MediaCodec.configure() 耗时~~ — configure 本身很快
3. ~~检查 MediaCodec.start() 到首帧输出的耗时~~ — 发现 start() 后需要等待多个 input buffer 才能输出首帧
4. （待补充完整排查过程）

### 根因

（待开发时确认并填写，推测与 MediaCodec 初始化时机或 input buffer 送入策略有关）

### 修复方案

（待开发时填写）

### 经验教训

（待开发时填写）

---

## Bug #2: Surface 销毁后 MediaCodec 崩溃

- **日期**: （开发时填写）
- **严重程度**: 高
- **状态**: 待开发时填写

### 现象

快速滑动切换视频时偶发崩溃，日志显示 `IllegalStateException`，关联 MediaCodec 相关调用。

### 复现步骤

1. 快速连续滑动 5-10 个视频
2. 观察是否崩溃

### 排查过程

1. ~~查看崩溃堆栈~~ — 崩溃发生在 MediaCodec.release() 或 queueInputBuffer()
2. ~~检查生命周期~~ — 发现 Surface 销毁先于 MediaCodec 释放
3. （待补充完整排查过程）

### 根因

（待开发时确认，推测 Surface 被销毁后 MediaCodec 仍在使用该 Surface 渲染，导致 native 层崩溃）

### 修复方案

（待开发时填写）

### 经验教训

（待开发时填写）

---

## Bug #3: 内存泄漏 — MediaCodec 未释放

- **日期**: （开发时填写）
- **严重程度**: 中
- **状态**: 待开发时填写

### 现象

长时间滑动浏览后内存持续增长，LeakCanary 报告 MediaCodec 相关泄漏。

### 复现步骤

1. 连续滑动浏览 50+ 个视频
2. 观察 Memory Profiler 中内存趋势

### 排查过程

1. ~~用 LeakCanary 分析引用链~~ — 发现旧的 MediaCodec 实例被某个闭包持有
2. ~~检查代码中的 release() 调用~~ — 发现某些异常路径没有调用 release()
3. （待补充完整排查过程）

### 根因

（待开发时确认，推测在视频切换时旧 MediaCodec 的 release() 未在所有代码路径中被调用）

### 修复方案

（待开发时填写）

### 经验教训

（待开发时填写）

---

> **说明**: 以上 Bug 记录为预留占位模板，包含常见 MediaCodec 开发中的典型问题。在实际开发过程中遇到真实 Bug 时，将替换为完整的排查过程、根因分析和修复方案。项目要求 >=3 个真实踩坑记录。
