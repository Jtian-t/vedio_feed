package com.example.feedvideo.network

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * 弱网降级处理器 — 检测带宽自动切换清晰度。
 * 基于 BandwidthInterceptor 的速度数据做降级决策。
 */
object WeakNetworkHandler {

    private const val TAG = "WeakNetworkHandler"
    private const val WEAK_THRESHOLD_KBPS = 500.0
    private const val RECOVERY_THRESHOLD_KBPS = 1000.0

    enum class Quality { HIGH, LOW }

    val currentQuality = AtomicReference(Quality.HIGH)

    /**
     * 检查当前网络状态，决定是否需要降级
     */
    fun checkAndDegrade() {
        val bandwidth = BandwidthInterceptor.currentBandwidthKbps.get()
        val current = currentQuality.get()

        if (bandwidth < WEAK_THRESHOLD_KBPS && current == Quality.HIGH) {
            currentQuality.set(Quality.LOW)
            Log.w(TAG, "Weak network detected (${bandwidth}KB/s), downgrading to LOW quality")
        } else if (bandwidth > RECOVERY_THRESHOLD_KBPS && current == Quality.LOW) {
            currentQuality.set(Quality.HIGH)
            Log.i(TAG, "Network recovered (${bandwidth}KB/s), upgrading to HIGH quality")
        }
    }

    /**
     * 根据网络质量选择视频 URL
     */
    fun selectUrl(highQualityUrl: String, lowQualityUrl: String): String {
        return when (currentQuality.get()) {
            Quality.HIGH -> highQualityUrl
            Quality.LOW -> lowQualityUrl
        }
    }
}
