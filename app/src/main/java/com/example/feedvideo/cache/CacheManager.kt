package com.example.feedvideo.cache

/**
 * 缓存管理器 — 统一管理三级缓存查询链
 * L1: 内存缓存 → L2: 磁盘缓存 → L3: 网络
 */
class CacheManager {

    val memoryCache = MemoryCache()
    val diskCache = DiskCache()

    /**
     * 查询缓存数据
     * @return 缓存数据，如果没有则返回 null
     */
    fun get(url: String): ByteArray? {
        val key = diskCache.cacheKey(url)

        // L1: 内存缓存
        memoryCache.get(key)?.let { return it }

        // L2: 磁盘缓存
        diskCache.get(key)?.let { data ->
            // 回写到内存缓存
            memoryCache.put(key, data)
            return data
        }

        // L3: 需要从网络获取
        return null
    }

    /**
     * 将数据写入缓存
     */
    fun put(url: String, data: ByteArray) {
        val key = diskCache.cacheKey(url)
        diskCache.put(key, data)
        memoryCache.put(key, data)
    }

    /**
     * 追加写入磁盘缓存（支持边下边播）
     */
    fun append(url: String, data: ByteArray) {
        val key = diskCache.cacheKey(url)
        diskCache.append(key, data)
    }

    fun isCached(url: String): Boolean {
        val key = diskCache.cacheKey(url)
        return memoryCache.get(key) != null || diskCache.exists(key)
    }

    fun getCachedSize(url: String): Long {
        val key = diskCache.cacheKey(url)
        return diskCache.getSize(key)
    }

    fun clear() {
        memoryCache.clear()
        diskCache.clear()
    }
}
