package com.example.feedvideo.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.buffer
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
        val body = response.body ?: return response

        // 使用自定义 ResponseBody 包装，在读取流的过程中动态计算速度，避免阻塞
        val wrappedBody = object : okhttp3.ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source(): okio.BufferedSource {
                val source = body.source()
                return object : okio.ForwardingSource(source) {
                    var totalBytesRead = 0L

                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val bytesRead = super.read(sink, byteCount)
                        if (bytesRead != -1L) {
                            totalBytesRead += bytesRead
                            
                            val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
                            if (durationMs > 100) { // 采样周期 100ms
                                val instantKbps = totalBytesRead / 1024.0 / (durationMs / 1000.0)
                                updateBandwidth(instantKbps)
                            }
                        }
                        return bytesRead
                    }
                }.buffer()
            }
        }

        return response.newBuilder().body(wrappedBody).build()
    }

    private fun updateBandwidth(instantKbps: Double) {
        val current = currentBandwidthKbps.get()
        val smoothed = if (current == 0.0) {
            instantKbps
        } else {
            current * (1 - SMOOTHING_FACTOR) + instantKbps * SMOOTHING_FACTOR
        }
        currentBandwidthKbps.set(smoothed)

        val weak = smoothed < WEAK_NETWORK_THRESHOLD_KBPS
        isWeakNetwork.set(weak)
        
        Log.v(TAG, "Current Bandwidth: ${String.format("%.1f", smoothed)} KB/s")
    }
}
