package com.example.feedvideo.ui.player

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.example.feedvideo.player.VideoPlayer

/**
 * 全屏视频播放 Composable。
 */
@Composable
fun VideoPlayerView(
    player: VideoPlayer,
    videoUrl: String,
    isCurrentVideo: Boolean,
    modifier: Modifier = Modifier
) {
    val videoSize by player.videoSize.collectAsState()

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
                                Log.i("VideoPlayerView", "surfaceCreated | isCurrent=$isCurrentVideo | url=$videoUrl | surface=${holder.surface}")
                                if (isCurrentVideo) {
                                    Log.i("VideoPlayerView", "Calling prepareAndPlay for: $videoUrl")
                                    player.prepareAndPlay(videoUrl, holder.surface)
                                }
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                Log.d("VideoPlayerView", "surfaceChanged: ${width}x${height}")
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                Log.d("VideoPlayerView", "surfaceDestroyed: $videoUrl")
                            }
                        })
                    }
                },
                modifier = if (videoSize.first > 0 && videoSize.second > 0) {
                    Modifier.aspectRatio(videoSize.first.toFloat() / videoSize.second.toFloat())
                } else {
                    Modifier.fillMaxSize()
                }
            )
        }
    }

    // 当该条目不再是当前播放项时，释放资源
    DisposableEffect(isCurrentVideo) {
        if (!isCurrentVideo) {
            Log.d("VideoPlayerView", "onEffect: isCurrentVideo=false, releasing player | url=$videoUrl")
            player.release()
        }
        onDispose {
            Log.d("VideoPlayerView", "onDispose: releasing player | url=$videoUrl")
            player.release()
        }
    }
}
