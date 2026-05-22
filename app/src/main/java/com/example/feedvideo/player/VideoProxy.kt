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
    private val cacheManager = CacheManager()
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
            val cachedData = cacheManager.get(url)
            if (cachedData != null) {
                Log.i(TAG, "Cache HIT: $url, size=${cachedData.size} bytes")
                sendResponse(output, cachedData, "video/mp4", false)
                return
            }
            Log.d(TAG, "Cache MISS (isCached=true but get returned null): $url")
        } else if (!isRangeRequest && rangeStart == 0L) {
            Log.d(TAG, "Cache MISS: $url")
        }

        // 从网络获取并流式传输
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FeedVideo/1.0")
            .header("Accept", "*/*")
            .apply { if (rangeStart > 0) addHeader("Range", "bytes=$rangeStart-") }
            .build()

        try {
            NetworkClient.client.newCall(request).execute().use { response ->
                val body = response.body ?: run {
                    Log.e(TAG, "Upstream returned null body for: $url")
                    sendError(output, 502, "Bad Gateway")
                    return
                }
                val contentType = body.contentType()?.toString() ?: "video/mp4"
                val contentLength = body.contentLength()

                Log.d(TAG, "<<< Upstream: HTTP ${response.code} | Content-Length=$contentLength | Content-Type=$contentType | URL=$url")

                // 根据上游实际响应码决定状态行
                val upstreamSupportsRange = response.code == 206
                val statusLine = if (upstreamSupportsRange) {
                    "HTTP/1.1 206 Partial Content\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\n"
                }

                val header = buildString {
                    append(statusLine)
                    append("Content-Type: $contentType\r\n")
                    append("Content-Length: $contentLength\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (upstreamSupportsRange) {
                        response.header("Content-Range")?.let { cr ->
                            append("Content-Range: $cr\r\n")
                        }
                    }
                    append("Connection: close\r\n\r\n")
                }

                output.write(header.toByteArray())

                val source = body.source()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalStreamed = 0L
                // 仅对小文件（<5MB）缓存，大文件纯流式避免 OOM
                val cacheStream = if (rangeStart == 0L && !isRangeRequest && contentLength in 1..(5 * 1024 * 1024)) ByteArrayOutputStream() else null

                while (source.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                    cacheStream?.write(buffer, 0, bytesRead)
                    totalStreamed += bytesRead
                }

                Log.d(TAG, "Transfer complete: $totalStreamed bytes streamed | cached=${cacheStream != null} | URL=$url")

                // 如果是完整请求且下载完成且文件较小，存入缓存
                if (cacheStream != null) {
                    val data = cacheStream.toByteArray()
                    cacheManager.put(url, data)
                    Log.d(TAG, "Cached: ${data.size} bytes for $url")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error for $url: ${e.message}")
        }
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
