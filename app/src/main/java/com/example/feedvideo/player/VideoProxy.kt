package com.example.feedvideo.player

import android.content.Context
import android.util.Log
import com.example.feedvideo.cache.CacheManager
import com.example.feedvideo.network.NetworkClient
import okhttp3.Request
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 HTTP 代理服务器 — 支持边下边播、二级缓存和本地 Asset 资源。
 *
 * 架构：
 *   播放器 → MediaExtractor → http://127.0.0.1:PORT/proxy?url=xxx
 *   → 代理拦截 → 缓存/Asset/网络 → 流式传输 → 播放器解码
 */
class VideoProxy(private val context: Context) {

    companion object {
        private const val TAG = "VideoProxy"
        private const val PORT = 0 // 随机可用端口
        private const val ASSET_SCHEME = "file:///android_asset/"
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)
    val cacheManager = CacheManager()
    private var actualPort = 0

    val proxyUrl: String
        get() = "http://127.0.0.1:$actualPort"

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        try {
            serverSocket = ServerSocket(PORT).also {
                actualPort = it.localPort
                Log.i(TAG, "Proxy started on port $actualPort")
            }

            executor.execute {
                while (isRunning.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.execute { handleRequest(client) }
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.e(TAG, "Accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start error", e)
        }
    }

    fun getProxyUrl(originalUrl: String): String {
        return "$proxyUrl/proxy?url=${URLEncoder.encode(originalUrl, "UTF-8")}"
    }

    private fun handleRequest(client: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()

            val requestLine = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val parts = line!!.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0].lowercase()] = parts[1].trim()
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val fullPath = parts[1]

            if (!fullPath.startsWith("/proxy")) {
                sendError(output, 404, "Not Found")
                return
            }

            val queryIndex = fullPath.indexOf("?url=")
            if (queryIndex < 0) {
                sendError(output, 400, "Missing url parameter")
                return
            }
            val originalUrl = URLDecoder.decode(fullPath.substring(queryIndex + 5), "UTF-8")

            val rangeHeader = headers["range"]
            val rangeStart = parseRangeStart(rangeHeader)
            val isRangeRequest = rangeHeader != null

            Log.d(TAG, ">>> Request: $originalUrl | Range: ${rangeHeader ?: "none"}")

            // 判断是否为本地 Asset URL
            if (originalUrl.startsWith(ASSET_SCHEME)) {
                val assetPath = originalUrl.removePrefix(ASSET_SCHEME)
                serveAsset(output, assetPath, rangeStart, isRangeRequest)
            } else {
                serveContent(output, originalUrl, rangeStart, isRangeRequest)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Handle request error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * 从本地 assets 目录提供视频文件
     */
    private fun serveAsset(
        output: OutputStream,
        assetPath: String,
        rangeStart: Long,
        isRangeRequest: Boolean
    ) {
        Log.d(TAG, "Serving asset: $assetPath")
        try {
            val inputStream = context.assets.open(assetPath)
            val totalSize = context.assets.openFd(assetPath).length

            if (isRangeRequest && rangeStart > 0) {
                // Range 请求：跳到指定位置读取
                inputStream.skip(rangeStart)
                val remainingSize = totalSize - rangeStart
                Log.d(TAG, "Asset Range: bytes=$rangeStart-, remaining=$remainingSize bytes")

                val header = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Content-Length: $remainingSize\r\n" +
                        "Content-Range: bytes $rangeStart-${totalSize - 1}/$totalSize\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(header.toByteArray())
                streamData(inputStream, output)
            } else {
                Log.d(TAG, "Asset full: $totalSize bytes")
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Content-Length: $totalSize\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(header.toByteArray())
                streamData(inputStream, output)
            }
            Log.i(TAG, "Asset transfer complete: $assetPath")
        } catch (e: Exception) {
            Log.e(TAG, "Asset error: $assetPath", e)
            sendError(output, 404, "Asset not found: $assetPath")
        }
    }

    /**
     * 从 CDN 网络获取视频并流式传输
     */
    private fun serveContent(
        output: OutputStream,
        url: String,
        rangeStart: Long,
        isRangeRequest: Boolean
    ) {
        // 尝试从缓存获取（仅完整请求）
        if (cacheManager.isCached(url) && rangeStart == 0L && !isRangeRequest) {
            val cacheFile = cacheManager.diskCache.getFile(cacheManager.diskCache.cacheKey(url))
            if (cacheFile.exists() && cacheFile.length() > 0) {
                Log.i(TAG, "Cache HIT (File): $url, size=${cacheFile.length()} bytes")
                try {
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: video/mp4\r\n")
                        append("Content-Length: ${cacheFile.length()}\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    output.write(header.toByteArray())
                    cacheFile.inputStream().use { input ->
                        streamData(input, output)
                    }
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Error serving from cache file: ${e.message}")
                }
            }
        }

        // 从网络获取并流式传输（支持网络中断后自动重连）
        val buffer = ByteArray(8192)
        var totalStreamed = 0L
        var currentPosition = rangeStart
        val shouldCache = rangeStart == 0L && !isRangeRequest
        var headerSent = false
        var maxRetries = 3

        if (shouldCache) {
            Log.d(TAG, "Starting incremental cache for: $url")
        }

        while (isRunning.get() && maxRetries > 0) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "FeedVideo/1.0")
                    .header("Accept", "*/*")
                    .apply { if (currentPosition > 0) addHeader("Range", "bytes=$currentPosition-") }
                    .build()

                NetworkClient.client.newCall(request).execute().use { response ->
                    val body = response.body ?: run {
                        Log.e(TAG, "Upstream returned null body for: $url")
                        if (!headerSent) sendError(output, 502, "Bad Gateway")
                        return
                    }

                    Log.d(TAG, "<<< Upstream: HTTP ${response.code} | Content-Length=${body.contentLength()} | URL=$url")

                    // 重连时如果 CDN 不支持 Range，发送重复数据会导致播放器解析错误，直接放弃
                    if (headerSent && response.code != 206) {
                        Log.w(TAG, "Retry got HTTP ${response.code} (expected 206), CDN doesn't support Range. Stopping.")
                        return
                    }

                    // 仅在首次连接时发送 HTTP 响应头给播放器
                    if (!headerSent) {
                        val contentType = body.contentType()?.toString() ?: "video/mp4"
                        val contentLength = body.contentLength()
                        val upstreamSupportsRange = response.code == 206
                        val statusLine = if (upstreamSupportsRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
                        val header = buildString {
                            append(statusLine)
                            append("Content-Type: $contentType\r\n")
                            append("Content-Length: $contentLength\r\n")
                            append("Accept-Ranges: bytes\r\n")
                            if (upstreamSupportsRange) {
                                response.header("Content-Range")?.let { append("Content-Range: $it\r\n") }
                            }
                            append("Connection: close\r\n\r\n")
                        }
                        output.write(header.toByteArray())
                        headerSent = true
                    }

                    val source = body.source()
                    var streamError = false

                    // 内层流式传输循环
                    while (isRunning.get()) {
                        val bytesRead = try {
                            source.read(buffer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Upstream read error for $url: ${e.message}")
                            streamError = true
                            break  // 跳出内层循环，进入重连逻辑
                        }

                        if (bytesRead == -1) break  // 正常 EOF

                        try {
                            output.write(buffer, 0, bytesRead)
                            output.flush()
                        } catch (e: Exception) {
                            Log.e(TAG, "Client socket write error: ${e.message}")
                            return  // 客户端断开，无法恢复
                        }

                        if (shouldCache) {
                            val data = if (bytesRead == buffer.size) buffer else buffer.copyOfRange(0, bytesRead)
                            cacheManager.append(url, data)
                        }

                        currentPosition += bytesRead
                        totalStreamed += bytesRead
                    }

                    // 内层循环正常结束（EOF），无需重连
                    if (!streamError) {
                        Log.d(TAG, "Transfer complete: $totalStreamed bytes streamed | URL=$url")
                        return
                    }

                    // 网络出错，准备重连
                    maxRetries--
                    Log.w(TAG, "Upstream error, will retry ($maxRetries retries left) | offset=$currentPosition | URL=$url")
                }
            } catch (e: Exception) {
                maxRetries--
                Log.e(TAG, "Connection error for $url: ${e.message} | retries left=$maxRetries")
            }

            // 重连前等待
            if (maxRetries > 0 && isRunning.get()) {
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
        }

        Log.d(TAG, "Transfer ended: $totalStreamed bytes streamed | URL=$url")
    }

    private fun streamData(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            output.flush()
        }
        input.close()
    }

    private fun parseRangeStart(range: String?): Long {
        if (range == null || !range.startsWith("bytes=")) return 0
        return range.substring(6).split("-")[0].toLongOrNull() ?: 0
    }

    private fun sendResponse(output: OutputStream, data: ByteArray, contentType: String, isPartial: Boolean) {
        val header = "HTTP/1.1 ${if (isPartial) 206 else 200} OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(data)
        output.flush()
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        Log.i(TAG, "Proxy stopped")
    }
}
