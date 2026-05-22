package com.example.feedvideo.ui.player

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.example.feedvideo.player.VideoPlayer

/**
 * 全屏视频播放 Composable — AndroidView 包裹 SurfaceView。
 */
@Composable
fun VideoPlayerView(
    player: VideoPlayer,
    videoUrl: String,
    isCurrentVideo: Boolean,
    modifier: Modifier = Modifier
) {
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var surfaceReady by remember { mutableStateOf(false) }

    LaunchedEffect(videoUrl, surfaceReady) {
        if (surfaceReady && surfaceView != null) {
            val surface = surfaceView?.holder?.surface
            if (surface != null && surface.isValid) {
                player.prepareAndPlay(videoUrl, surface)
            }
        }
    }

    // URL 变化或 composable 离开时释放资源
    DisposableEffect(videoUrl) {
        onDispose {
            player.release()
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
                            surfaceReady = true
                        }

                        override fun surfaceChanged(
                            holder: android.view.SurfaceHolder,
                            format: Int, width: Int, height: Int
                        ) {}

                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                            surfaceReady = false
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
