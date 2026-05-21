# PERFORMANCE.md — 性能报告与 Demo 视频指引

## 目录

- [Profiler 采集指引](#profiler-采集指引)
  - [CPU Profiler](#cpu-profiler)
  - [Memory Profiler](#memory-profiler)
  - [Network Profiler](#network-profiler)
- [Perfetto 帧率采集指引](#perfetto-帧率采集指引)
- [Systrace 帧率采集指引](#systrace-帧率采集指引)
- [Demo 视频录制指引](#demo-视频录制指引)

---

## Profiler 采集指引

### CPU Profiler

**目的**: 验证视频解码的 CPU 占用是否合理，排查性能瓶颈。

**步骤**:

1. 连接真机（推荐骁龙 778G 级设备），开启 USB 调试
2. Android Studio → **View → Tool Windows → Profiler**
3. 选择目标设备和进程
4. 点击 **CPU** 区域 → 选择 **Trace System Calls** 或 **Sample C++ Methods**
5. 点击 **Record** 按钮（红色圆点）
6. 在设备上操作应用：上下滑动切换 5-10 个视频
7. 点击 **Stop** 结束录制
8. 查看 **Flame Chart** 和 **Top Down** 视图
9. **截图要点**: 解码线程的 CPU 占用、主线程是否被阻塞

**输出**: CPU Profiler 截图，标注关键线程和热点函数。

---

### Memory Profiler

**目的**: 验证单视频播放期间内存峰值 < 200MB，检测内存泄漏。

**步骤**:

1. Android Studio → **Profiler** → 点击 **Memory** 区域
2. 观察实时内存曲线，注意 **Java/Kotlin** 和 **Native** 两部分
3. 播放单个视频，观察内存峰值
4. 连续滑动浏览 20+ 个视频，观察内存是否持续增长
5. 点击 **Dump Java Heap** 查看对象分配详情
6. **截图要点**: 内存峰值 < 200MB 的截图、长时间浏览后的内存趋势图

**验收**: 单视频播放期间 Memory Profiler 显示 < 200MB。

---

### Network Profiler

**目的**: 验证网络请求时序、带宽利用、缓存是否生效。

**步骤**:

1. Android Studio → **Profiler** → 点击 **Network** 区域
2. 首次观看某个视频，观察网络下载曲线
3. 重复观看同一视频，确认无网络请求（缓存命中）
4. 连续快速滑动，观察预加载的网络请求时序
5. **截图要点**: 网络请求时序图、缓存命中对比（有请求 vs 无请求）

---

## Perfetto 帧率采集指引

**目的**: 验证 95% 帧 >= 55fps，生成官方认可的帧率报告。

**步骤**:

1. 连接真机，开启 USB 调试
2. 打开终端，执行 Perfetto 采集命令：

```bash
# 采集 10 秒的 gfx 和 view 帧率数据
adb shell perfetto \
  -c - --txt \
  -o /data/misc/perfetto-traces/trace.perfetto-trace \
<<EOF
buffers: {
  size_kb: 63488
  fill_policy: RING_BUFFER
}
data_sources: {
  config {
    name: "android.surfaceflinger.frametimeline"
  }
}
data_sources: {
  config {
    name: "android.surfaceflinger.framestimeline"
  }
}
duration_ms: 10000
EOF

# 拉取 trace 文件
adb pull /data/misc/perfetto-traces/trace.perfetto-trace ./perfetto_trace.perfetto-trace
```

3. 打开 [ui.perfetto.dev](https://ui.perfetto.dev)，上传 trace 文件
4. 在 Perfetto UI 中：
   - 搜索 `frame` 查看帧耗时
   - 查看 **Frame timeline** 视图，确认每帧耗时 < 16.67ms（60fps）或 < 18.18ms（55fps）
   - 统计掉帧数量，验证 95% 帧 >= 55fps
5. **截图要点**: Frame timeline 视图、帧耗时分布、掉帧统计

**验收**: Perfetto 报告显示 95% 帧 >= 55fps。

---

## Systrace 帧率采集指引

**目的**: 补充 Perfetto 的帧率数据，从系统层面分析渲染性能。

**步骤**:

1. 安装 `systrace` 工具（Android SDK 自带，位于 `platform-tools/systrace/`）
2. 执行采集命令：

```bash
python systrace.py \
  gfx view sched freq idle am wm res \
  -o systrace_report.html \
  -t 10
```

3. 在设备上操作应用（上下滑动切换视频）
4. 采集结束后用 Chrome 打开 `systrace_report.html`
5. 在 Systrace 中：
   - 查看 **SurfaceFlinger** 行，确认帧提交频率
   - 查看 **Choreographer#doFrame**，确认每帧耗时
   - 标注掉帧区域（红色标记）
6. **截图要点**: SurfaceFlinger 帧提交时序、掉帧标记截图

---

## Demo 视频录制指引

**目的**: 录制 5-10 分钟的功能演示视频，展示所有核心功能。

### 录制工具

推荐方案：

| 工具 | 命令 | 说明 |
|---|---|---|
| **scrcpy** (推荐) | `scrcpy --record demo.mp4` | 高质量录屏，支持电脑端操作 |
| **adb screenrecord** | `adb shell screenrecord /sdcard/demo.mp4` | 系统自带，简单直接 |
| **Android Studio** | Profiler 窗口内点击录屏按钮 | 集成度最高 |

### 录制内容清单

按照以下顺序录制，每个操作之间停留 2-3 秒：

1. **应用启动** — 展示启动画面和首帧加载
2. **上下滑切换视频** — 连续滑动 5-10 个视频，展示流畅度
3. **下拉刷新** — 在顶部下拉触发刷新
4. **上拉加载更多** — 滑到底部触发加载更多
5. **点击暂停/播放** — 点击屏幕暂停，再点击恢复
6. **长按倍速** — 长按屏幕展示 2 倍速播放
7. **点赞操作** — 点击点赞按钮，观察动画
8. **评论操作** — 打开评论输入框，输入并提交评论
9. **后台恢复** — 按 Home 键进入后台，再回到前台恢复播放
10. **弱网降级**（可选）— 模拟弱网环境，展示自动降级

### 输出要求

- **格式**: MP4
- **时长**: 5-10 分钟
- **分辨率**: 1080p 或以上
- **建议**: 添加旁白或字幕说明每个功能点
