package com.example.feedvideo.cache

import android.content.Context
import com.example.feedvideo.FeedVideoApp
import java.io.File
import java.security.MessageDigest

/**
 * L2 磁盘缓存 — File-based, LRU 淘汰策略
 * 容量上限：500MB
 */
class DiskCache {

    companion object {
        private const val MAX_SIZE_BYTES = 500L * 1024 * 1024 // 500MB
        private const val CACHE_DIR = "video_cache"
    }

    private val cacheDir: File by lazy {
        File(FeedVideoApp.instance.cacheDir, CACHE_DIR).apply { mkdirs() }
    }

    /**
     * 生成缓存键 — URL 的 MD5 hash
     */
    fun cacheKey(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): ByteArray? {
        val file = File(cacheDir, key)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    fun put(key: String, data: ByteArray) {
        val file = File(cacheDir, key)
        try {
            file.writeBytes(data)
            evictIfNeeded()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 追加写入（支持断点续传）
     */
    fun append(key: String, data: ByteArray) {
        val file = File(cacheDir, key)
        try {
            file.appendBytes(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun exists(key: String): Boolean = File(cacheDir, key).exists()

    fun getFile(key: String): File = File(cacheDir, key)

    fun getSize(key: String): Long = File(cacheDir, key).length()

    fun remove(key: String) {
        File(cacheDir, key).delete()
    }

    fun clear() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    /**
     * LRU 淘汰 — 删除最旧的文件直到总大小 < 上限
     */
    private fun evictIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }

        if (totalSize <= MAX_SIZE_BYTES) return

        // 按最后修改时间排序，最旧的在前
        val sorted = files.sortedBy { it.lastModified() }

        for (file in sorted) {
            if (totalSize <= MAX_SIZE_BYTES) break
            totalSize -= file.length()
            file.delete()
        }
    }

    fun getTotalSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
