package com.example.feedvideo.ui.player

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.example.feedvideo.player.VideoPlayer

/**
 * 全屏视频播放 Composable。
 *
 * 生命周期管理：
 * - key(videoUrl) 确保 URL 变化时彻底重建 SurfaceView
 * - DisposableEffect(isCurrentVideo) 处理滑动切换时的资源释放
 * - surfaceCreated 触发播放，surfaceDestroyed 仅打日志（资源由 DisposableEffect 释放）
 */
@Composable
fun VideoPlayerView(
    player: VideoPlayer,
    videoUrl: String,
    isCurrentVideo: Boolean,
    modifier: Modifier = Modifier
) {
    // 使用 videoUrl 作为 key，URL 变化时彻底重组，创建新的 SurfaceView
    key(videoUrl) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                Log.d("VideoPlayerView", "surfaceCreated: $videoUrl")
                                if (isCurrentVideo) {
                                    player.prepareAndPlay(videoUrl, holder.surface)
                                }
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                Log.d("VideoPlayerView", "surfaceDestroyed: $videoUrl")
                                // 不在此处调 release()，由 DisposableEffect 统一管理
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // 当该条目不再是当前播放项时，释放资源
    DisposableEffect(isCurrentVideo) {
        if (!isCurrentVideo) {
            Log.d("VideoPlayerView", "Not current video, releasing: $videoUrl")
            player.release()
        }
        onDispose {
            Log.d("VideoPlayerView", "onDispose, releasing: $videoUrl")
            player.release()
        }
    }
}
