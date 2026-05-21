package com.example.feedvideo.ui.player

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.feedvideo.player.VideoPlayer

/**
 * 全屏视频播放 Composable — AndroidView 包裹 SurfaceView。
 * 通过 MediaCodec 渲染视频帧。
 */
@Composable
fun VideoPlayerView(
    player: VideoPlayer,
    videoUrl: String,
    isCurrentVideo: Boolean,
    onSurfaceReady: (SurfaceView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    // 当视频 URL 或播放状态变化时，重新准备
    LaunchedEffect(videoUrl, isCurrentVideo) {
        if (isCurrentVideo && surfaceView != null) {
            surfaceView?.holder?.surface?.let { surface ->
                player.prepare(videoUrl, surface)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            surfaceView = this@apply
                            onSurfaceReady(this@apply)
                            if (isCurrentVideo) {
                                player.prepare(videoUrl, holder.surface)
                            }
                        }

                        override fun surfaceChanged(
                            holder: android.view.SurfaceHolder,
                            format: Int, width: Int, height: Int
                        ) {}

                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                            player.release()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
