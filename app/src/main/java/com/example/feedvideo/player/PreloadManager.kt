package com.example.feedvideo.player

import android.util.Log
import com.example.feedvideo.data.model.Video
import com.example.feedvideo.network.NetworkClient
import kotlinx.coroutines.*
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 智能预加载管理器 — 根据滑动方向预加载视频。
 *
 * 策略：
 * - 预加载窗口：当前视频前后各 N 个（N=2，可配置）
 * - 仅预加载前几秒数据，不完整下载
 * - 低优先级网络请求，不与当前播放争抢带宽
 */
class PreloadManager {

    companion object {
        private const val TAG = "PreloadManager"
        private const val PRELOAD_WINDOW = 2 // 前后各预加载 2 个
        private const val PRELOAD_BYTES = 512 * 1024L // 预加载 512KB（约几秒的视频）
    }

    // 预加载任务跟踪
    private val preloadJobs = ConcurrentHashMap<String, Job>()
    private val preloadedUrls = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 低优先级线程池（不与播放争抢）
    private val preloadExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "preload-worker").apply {
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * 更新预加载窗口
     * @param videos 完整视频列表
     * @param currentIndex 当前播放的视频索引
     */
    fun updatePreloadWindow(videos: List<Video>, currentIndex: Int) {
        val startIndex = maxOf(0, currentIndex - PRELOAD_WINDOW)
        val endIndex = minOf(videos.size - 1, currentIndex + PRELOAD_WINDOW)

        // 取消窗口外的预加载任务
        val windowUrls = (startIndex..endIndex).map { videos[it].url }.toSet()
        preloadJobs.keys.forEach { url ->
            if (url !in windowUrls) {
                preloadJobs[url]?.cancel()
                preloadJobs.remove(url)
                Log.d(TAG, "Cancelled preload for out-of-window: $url")
            }
        }

        // 启动窗口内的预加载
        for (i in startIndex..endIndex) {
            if (i == currentIndex) continue // 当前视频不需要预加载
            val video = videos[i]
            if (preloadedUrls.contains(video.url)) continue
            if (preloadJobs.containsKey(video.url)) continue

            preloadVideo(video)
        }
    }

    /**
     * 预加载单个视频的前 N 字节
     */
    private fun preloadVideo(video: Video) {
        val job = scope.launch {
            try {
                Log.d(TAG, "Preloading: ${video.title}")

                // 跳过本地 asset（无需预加载）
                if (video.url.startsWith("file:///android_asset/")) {
                    preloadedUrls.add(video.url)
                    Log.d(TAG, "Skipping preload for local asset: ${video.title}")
                    return@launch
                }

                val request = Request.Builder()
                    .url(video.url)
                    .header("Range", "bytes=0-${PRELOAD_BYTES - 1}")
                    .build()

                val response = NetworkClient.client.newCall(request).execute()
                val body = response.body

                if (body != null && response.isSuccessful) {
                    // 读取前 N 字节（触发网络下载和系统缓存）
                    val source = body.source()
                    val buffer = ByteArray(4096)
                    var totalRead = 0L
                    while (totalRead < PRELOAD_BYTES) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        totalRead += read
                    }
                    body.close()

                    preloadedUrls.add(video.url)
                    Log.d(TAG, "Preloaded ${totalRead}B for: ${video.title}")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Preload cancelled: ${video.title}")
            } catch (e: Exception) {
                Log.e(TAG, "Preload failed: ${video.title}", e)
            } finally {
                preloadJobs.remove(video.url)
            }
        }

        preloadJobs[video.url] = job
    }

    /**
     * 取消所有预加载任务
     */
    fun cancelAll() {
        preloadJobs.values.forEach { it.cancel() }
        preloadJobs.clear()
        Log.d(TAG, "All preload jobs cancelled")
    }

    /**
     * 释放资源
     */
    fun release() {
        cancelAll()
        scope.cancel()
        preloadExecutor.shutdownNow()
        preloadedUrls.clear()
        Log.d(TAG, "PreloadManager released")
    }
}
