package com.example.feedvideo.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 自定义 OkHttp Interceptor — 日志记录和带宽检测。
 * 项目要求必须自己写 Interceptor，禁用 Retrofit 高级特性。
 */
class LoggingInterceptor : Interceptor {

    companion object {
        private const val TAG = "NetworkInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "→ ${request.method} ${request.url}")

        val response = chain.proceed(request)

        val duration = System.currentTimeMillis() - startTime
        val contentLength = response.body?.contentLength() ?: -1
        val speed = if (duration > 0 && contentLength > 0) {
            contentLength / duration / 1024.0 // KB/ms → MB/s 近似
        } else 0.0

        Log.d(TAG, "← ${response.code} ${request.url} (${duration}ms, ${String.format("%.2f", speed)} KB/ms)")

        return response
    }
}
