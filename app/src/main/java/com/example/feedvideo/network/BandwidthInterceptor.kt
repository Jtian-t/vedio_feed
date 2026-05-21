package com.example.feedvideo.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.util.concurrent.atomic.AtomicReference

/**
 * 带宽检测 Interceptor — 监测下载速度，支持弱网降级。
 * 使用滑动平均算法平滑速度波动。
 */
class BandwidthInterceptor : Interceptor {

    companion object {
        private const val TAG = "BandwidthInterceptor"
        private const val WEAK_NETWORK_THRESHOLD_KBPS = 500.0 // 500KB/s
        private const val SMOOTHING_FACTOR = 0.3

        val currentBandwidthKbps = AtomicReference(0.0)
        val isWeakNetwork = AtomicReference(false)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.nanoTime()

        val response = chain.proceed(request)

        // 计算下载速度
        val responseBody = response.body ?: return response
        val contentLength = responseBody.contentLength()

        if (contentLength > 0) {
            val source = responseBody.source()
            source.request(Long.MAX_VALUE) // 确保数据加载完毕
            val buffer = source.buffer
            val bytesRead = buffer.size

            val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
            if (durationMs > 0) {
                val instantKbps = bytesRead / 1024.0 / (durationMs / 1000.0)

                // 滑动平均
                val current = currentBandwidthKbps.get()
                val smoothed = if (current == 0.0) {
                    instantKbps
                } else {
                    current * (1 - SMOOTHING_FACTOR) + instantKbps * SMOOTHING_FACTOR
                }
                currentBandwidthKbps.set(smoothed)

                val weak = smoothed < WEAK_NETWORK_THRESHOLD_KBPS
                isWeakNetwork.set(weak)

                Log.d(TAG, "Bandwidth: ${String.format("%.1f", smoothed)} KB/s, weak=$weak")
            }
        }

        return response
    }
}
