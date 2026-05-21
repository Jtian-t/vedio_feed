# CLAUDE.md

## 项目概述

自研短视频 Feed 安卓客户端应用，使用 Kotlin + Jetpack Compose 开发，基于 MediaCodec 底层 API 实现视频解码渲染，禁用 ExoPlayer/IjkPlayer 等高层封装。

## Git 工作流

- **远程仓库**: https://github.com/Jtian-t/vedio_feed.git
- **分支策略**: main 分支
- **提交时机**: 完成重大改动（功能实现、文档补全、Bug 修复等）后，立即将内容推送到 GitHub 仓库
- **提交规范**: 使用中文提交信息，简明描述本次变更内容

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose
- **异步**: Coroutines + Flow
- **网络**: OkHttp（裸用，禁用 Retrofit 高级特性，自写 Interceptor）
- **播放器**: 自研 MediaCodec + SurfaceView（禁用 ExoPlayer/IjkPlayer）
- **性能工具**: Android Studio Profiler、Perfetto、Systrace

## 项目结构

```
Feed_vedio/
├── openspec/                  # OpenSpec 规范文档
│   ├── changes/
│   │   ├── short-video-feed-android/   # 核心功能变更
│   │   └── project-delivery-docs/      # 交付文档变更
│   └── specs/
├── 项目要求.md                # 项目需求文档
├── CLAUDE.md                  # 本文件
└── (后续添加的 Android 项目代码)
```

## 验收标准

| 项目 | 标准 |
|---|---|
| 首帧时间 | 中端机（骁龙 778G 级）< 800ms |
| 滑动帧率 | 95% 帧 >= 55fps，提交 Perfetto 报告 |
| 内存峰值 | 单视频播放期间 < 200MB |
| 崩溃率 | 200 次自动滑动测试，0 崩溃 |
| 缓存命中率 | 重复观看场景 >= 90% |
| 原理答辩 | 抽 5 题，答错 <= 1 题 |
