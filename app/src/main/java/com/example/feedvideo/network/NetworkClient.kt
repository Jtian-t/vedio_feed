package com.example.feedvideo.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端单例，配置自定义 Interceptor。
 * 项目要求：OkHttp 裸用，禁用 Retrofit，自写 Interceptor。
 */
object NetworkClient {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(LoggingInterceptor())
            .addNetworkInterceptor(BandwidthInterceptor())
            .build()
    }
}
