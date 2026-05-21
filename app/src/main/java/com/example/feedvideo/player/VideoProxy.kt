package com.example.feedvideo.player

import android.util.Log
import com.example.feedvideo.cache.CacheManager
import com.example.feedvideo.network.NetworkClient
import okhttp3.Request
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 HTTP 代理服务器 — 支持边下边播。
 * 类似 AndroidVideoCache 思路，完全自实现。
 *
 * 工作流程：
 *   播放器请求 localhost:port/proxy?url=xxx
 *   → 代理拦截 → 从缓存/网络获取数据 → Range 请求支持 → 回传给播放器
 */
class VideoProxy {

    companion object {
        private const val TAG = "VideoProxy"
        private const val PORT = 0 // 随机可用端口
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)
    private val cacheManager = CacheManager()
    private var actualPort = 0

    val proxyUrl: String
        get() = "http://127.0.0.1:$actualPort"

    /**
     * 启动代理服务器
     */
    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        serverSocket = ServerSocket(PORT).also {
            actualPort = it.localPort
            Log.d(TAG, "Proxy started on port $actualPort")
        }

        executor.execute {
            while (isRunning.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleRequest(client) }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Accept error", e)
                    }
                }
            }
        }
    }

    /**
     * 获取代理 URL（将原始视频 URL 转换为代理 URL）
     */
    fun getProxyUrl(originalUrl: String): String {
        return "$proxyUrl/proxy?url=${java.net.URLEncoder.encode(originalUrl, "UTF-8")}"
    }

    /**
     * 处理客户端请求
     */
    private fun handleRequest(client: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()

            // 解析 HTTP 请求
            val requestLine = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val parts = line!!.split(": ", limit = 2)
                if (parts.size == 2) {
                    headers[parts[0].lowercase()] = parts[1]
                }
            }

            // 解析 URL
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val fullPath = parts[1]

            if (!fullPath.startsWith("/proxy")) {
                sendError(output, 404, "Not Found")
                return
            }

            // 提取原始视频 URL
            val queryIndex = fullPath.indexOf("?url=")
            if (queryIndex < 0) {
                sendError(output, 400, "Missing url parameter")
                return
            }
            val originalUrl = URLDecoder.decode(fullPath.substring(queryIndex + 5), "UTF-8")

            // 解析 Range 请求头
            val rangeHeader = headers["range"]
            val rangeStart = parseRangeStart(rangeHeader)
            val rangeEnd = parseRangeEnd(rangeHeader)

            // 从缓存或网络获取数据
            serveContent(output, originalUrl, rangeStart, rangeEnd, rangeHeader != null)

        } catch (e: Exception) {
            Log.e(TAG, "Handle request error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * 提供内容 — 优先从缓存获取，否则从网络下载
     */
    private fun serveContent(
        output: OutputStream,
        url: String,
        rangeStart: Long,
        rangeEnd: Long,
        isRangeRequest: Boolean
    ) {
        val client = NetworkClient.client

        // 构建网络请求（支持 Range）
        val requestBuilder = Request.Builder().url(url)
        if (isRangeRequest) {
            val rangeStr = if (rangeEnd > 0) {
                "bytes=$rangeStart-$rangeEnd"
            } else {
                "bytes=$rangeStart-"
            }
            requestBuilder.addHeader("Range", rangeStr)
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body ?: run {
                sendError(output, 500, "Empty response")
                return
            }

            val contentLength = body.contentLength()
            val contentType = body.contentType()?.toString() ?: "video/mp4"

            // 发送响应头
            val statusLine = if (isRangeRequest && response.code == 206) {
                "HTTP/1.1 206 Partial Content\r\n"
            } else {
                "HTTP/1.1 200 OK\r\n"
            }

            val header = buildString {
                append(statusLine)
                append("Content-Type: $contentType\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (isRangeRequest) {
                    val contentRange = response.header("Content-Range")
                        ?: "bytes $rangeStart-${if (rangeEnd > 0) rangeEnd else contentLength - 1}/$contentLength"
                    append("Content-Range: $contentRange\r\n")
                }
                append("Connection: close\r\n")
                append("\r\n")
            }

            output.write(header.toByteArray())
            output.flush()

            // 流式传输数据
            val buffer = ByteArray(8 * 1024) // 8KB buffer
            val source = body.source()
            var bytesRead: Int
            while (true) {
                val bufferOkio = okio.Buffer()
                val read = source.read(buffer, 0, buffer.size)
                if (read <= 0) break
                output.write(buffer, 0, read)
                output.flush()
            }

            body.close()

        } catch (e: Exception) {
            Log.e(TAG, "Serve content error for $url", e)
            sendError(output, 500, "Internal error: ${e.message}")
        }
    }

    private fun parseRangeStart(range: String?): Long {
        if (range == null || !range.startsWith("bytes=")) return 0
        val parts = range.substring(6).split("-")
        return parts[0].toLongOrNull() ?: 0
    }

    private fun parseRangeEnd(range: String?): Long {
        if (range == null || !range.startsWith("bytes=")) return -1
        val parts = range.substring(6).split("-")
        if (parts.size < 2 || parts[1].isEmpty()) return -1
        return parts[1].toLongOrNull() ?: -1
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    /**
     * 停止代理服务器
     */
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor.shutdownNow()
        Log.d(TAG, "Proxy stopped")
    }
}
