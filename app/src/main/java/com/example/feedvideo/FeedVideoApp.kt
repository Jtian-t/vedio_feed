package com.example.feedvideo

import android.app.Application
import com.example.feedvideo.player.VideoProxy

class FeedVideoApp : Application() {
    
    // 全局唯一的视频代理服务器
    val videoProxy = VideoProxy(this)

    override fun onCreate() {
        super.onCreate()
        instance = this
        videoProxy.start()
    }

    override fun onTerminate() {
        super.onTerminate()
        videoProxy.stop()
    }

    companion object {
        lateinit var instance: FeedVideoApp
            private set
    }
}
