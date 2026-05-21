package com.example.feedvideo.cache

import android.util.LruCache

/**
 * L1 内存缓存 — LruCache<String, ByteArray>
 * 容量上限：50MB
 */
class MemoryCache {

    companion object {
        private const val MAX_SIZE_BYTES = 50 * 1024 * 1024 // 50MB
    }

    private val cache = object : LruCache<String, ByteArray>(MAX_SIZE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int {
            return value.size
        }
    }

    fun get(key: String): ByteArray? = cache.get(key)

    fun put(key: String, data: ByteArray) {
        cache.put(key, data)
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun clear() {
        cache.evictAll()
    }

    val size: Int get() = cache.size()

    val maxSize: Int get() = cache.maxSize()
}
