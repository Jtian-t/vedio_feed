package com.example.feedvideo

import android.app.Application

class FeedVideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: FeedVideoApp
            private set
    }
}
