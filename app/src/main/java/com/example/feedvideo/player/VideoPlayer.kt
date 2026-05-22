package com.example.feedvideo.player

import android.content.Context
import android.media.*
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 视频播放器 — MediaCodec 硬件解码 + 音画同步
 *
 * 支持两种数据源：
 * - HTTP/HTTPS URL：MediaExtractor 直连（不走代理）
 * - 本地 Asset：复制到临时文件后双 MediaExtractor 独立加载
 */
class VideoPlayer(private val context: Context) {

    companion object {
        private const val TAG = "VideoPlayer"
        private const val TIMEOUT_US = 10_000L
        private const val ASSET_SCHEME = "file:///android_asset/"
    }

    enum class State { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed

    private val _videoSize = MutableStateFlow(Pair(0, 0))
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize

    private var playerJob: Job? = null
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isPaused = AtomicBoolean(false)

    private val currentSeq = AtomicInteger(0)

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var audioSampleRate = 0

    /**
     * 将 asset 文件复制到临时文件，返回临时文件路径。
     * 解决两个 MediaExtractor 共享 AssetFileDescriptor fd 导致 setDataSource 失败的问题。
     */
    private fun copyAssetToTemp(assetPath: String): File {
        val tempFile = File.createTempFile("fv_asset_", ".mp4", context.cacheDir)
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tempFile).use { output ->
                val buf = ByteArray(8192)
                var n: Int
                var total = 0L
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    total += n
                }
                output.flush()
                Log.d(TAG, "Copied asset '$assetPath' → ${tempFile.absolutePath} ($total bytes)")
            }
        }
        require(tempFile.length() > 0) { "Asset copy resulted in empty file: $assetPath" }
        return tempFile
    }

    fun prepareAndPlay(url: String, surface: Surface) {
        val seq = currentSeq.incrementAndGet()
        Log.i(TAG, ">>> prepareAndPlay [seq=$seq]: $url")

        playerJob?.cancel()
        isPaused.set(false)
        _state.value = State.PREPARING

        playerJob = scope.launch {
            var vCodec: MediaCodec? = null
            var aCodec: MediaCodec? = null
            var videoExtractor: MediaExtractor? = null
            var audioExtractor: MediaExtractor? = null
            var aTrack: AudioTrack? = null
            var tempFile: File? = null

            try {
                // 创建双 extractor
                videoExtractor = MediaExtractor()
                audioExtractor = MediaExtractor()

                if (url.startsWith(ASSET_SCHEME)) {
                    // 本地 Asset：复制到临时文件，双 extractor 独立加载
                    val assetPath = url.removePrefix(ASSET_SCHEME)
                    Log.i(TAG, "[seq=$seq] Source type: LOCAL ASSET | path=$assetPath")

                    tempFile = copyAssetToTemp(assetPath)
                    val tempPath = tempFile.absolutePath
                    Log.d(TAG, "[seq=$seq] Temp file ready: $tempPath (${tempFile.length()} bytes)")

                    videoExtractor.setDataSource(tempPath)
                    Log.d(TAG, "[seq=$seq] Video extractor setDataSource(temp) OK, trackCount=${videoExtractor.trackCount}")

                    audioExtractor.setDataSource(tempPath)
                    Log.d(TAG, "[seq=$seq] Audio extractor setDataSource(temp) OK, trackCount=${audioExtractor.trackCount}")
                } else {
                    // HTTP/HTTPS URL：MediaExtractor 直连
                    Log.i(TAG, "[seq=$seq] Source type: NETWORK URL | url=$url")
                    videoExtractor.setDataSource(url)
                    Log.d(TAG, "[seq=$seq] Video extractor setDataSource(url) OK, trackCount=${videoExtractor.trackCount}")

                    audioExtractor.setDataSource(url)
                    Log.d(TAG, "[seq=$seq] Audio extractor setDataSource(url) OK, trackCount=${audioExtractor.trackCount}")
                }

                // Track 选择
                if (videoExtractor.trackCount == 0) {
                    Log.e(TAG, "[seq=$seq] ERROR: MediaExtractor has 0 tracks! File may be corrupted or unsupported format.")
                    if (seq == currentSeq.get()) _state.value = State.ERROR
                    return@launch
                }

                var vIndex = -1
                var aIndex = -1
                for (i in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    Log.d(TAG, "[seq=$seq] Track $i: $mime")
                    if (mime.startsWith("video/") && vIndex == -1) vIndex = i
                    else if (mime.startsWith("audio/") && aIndex == -1) aIndex = i
                }

                Log.d(TAG, "[seq=$seq] Track scan: trackCount=${videoExtractor.trackCount}, videoIndex=$vIndex, audioIndex=$aIndex")

                if (vIndex == -1) {
                    Log.e(TAG, "[seq=$seq] ERROR: No video track found in $url")
                    if (seq == currentSeq.get()) _state.value = State.ERROR
                    return@launch
                }

                // 各自 select track
                videoExtractor.selectTrack(vIndex)
                if (aIndex != -1) {
                    audioExtractor.selectTrack(aIndex)
                }
                Log.d(TAG, "[seq=$seq] Tracks selected: video=$vIndex, audio=$aIndex")

                // 配置视频 codec
                val videoFormat = videoExtractor.getTrackFormat(vIndex)
                _duration.value = try { videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000 } catch (_: Exception) { 0L }
                val videoMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
                
                val width = if (videoFormat.containsKey(MediaFormat.KEY_WIDTH)) videoFormat.getInteger(MediaFormat.KEY_WIDTH) else 0
                val height = if (videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) videoFormat.getInteger(MediaFormat.KEY_HEIGHT) else 0
                _videoSize.value = width to height
                Log.d(TAG, "[seq=$seq] Video size: ${width}x${height}")

                vCodec = MediaCodec.createDecoderByType(videoMime)
                
                if (!surface.isValid) {
                    Log.e(TAG, "[seq=$seq] Surface is already invalid, aborting configure")
                    return@launch
                }

                vCodec.configure(videoFormat, surface, null, 0)
                Log.d(TAG, "[seq=$seq] Video codec configured")

                // 配置音频 codec
                if (aIndex != -1) {
                    val audioFormat = audioExtractor.getTrackFormat(aIndex)
                    val audioMime = audioFormat.getString(MediaFormat.KEY_MIME)!!
                    audioSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minBufSize = AudioTrack.getMinBufferSize(audioSampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

                    Log.d(TAG, "[seq=$seq] Audio: $audioMime, sampleRate=$audioSampleRate, channels=$channelCount")

                    aTrack = AudioTrack(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                        AudioFormat.Builder()
                            .setSampleRate(audioSampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                        minBufSize * 4,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    activeAudioTrack = aTrack

                    aCodec = MediaCodec.createDecoderByType(audioMime)
                    aCodec.configure(audioFormat, null, null, 0)
                    Log.d(TAG, "[seq=$seq] Audio codec configured")
                } else {
                    Log.d(TAG, "[seq=$seq] No audio track")
                }

                if (seq != currentSeq.get()) {
                    Log.d(TAG, "[seq=$seq] Seq mismatch before start, aborting")
                    return@launch
                }

                // 启动 codec 和 AudioTrack
                vCodec.start()
                Log.d(TAG, "[seq=$seq] Video codec started")
                aCodec?.start()
                aTrack?.play()

                _state.value = State.PLAYING
                Log.i(TAG, "[seq=$seq] Playback started!")

                // 启动解码循环
                coroutineScope {
                    launch { videoDecodeLoop(seq, vCodec, videoExtractor, vIndex) }
                    if (aCodec != null && aTrack != null) {
                        launch { audioDecodeLoop(seq, aCodec, audioExtractor, aIndex, aTrack) }
                    }
                }

                if (seq == currentSeq.get()) {
                    Log.i(TAG, "[seq=$seq] Playback completed (EOS)")
                    _state.value = State.COMPLETED
                } else {
                    Log.d(TAG, "[seq=$seq] Decode loops exited (seq mismatch)")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "[seq=$seq] Playback cancelled (normal)")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[seq=$seq] Playback error: ${e.javaClass.simpleName}: ${e.message}", e)
                if (seq == currentSeq.get()) _state.value = State.ERROR
            } finally {
                // 后台清理，避免和新 decode loop 竞争 codec
                val vc = vCodec; val ac = aCodec
                val ve = videoExtractor; val ae = audioExtractor
                val at = aTrack; val tf = tempFile
                val cleanupSeq = seq

                cleanupJob = scope.launch {
                    Log.d(TAG, "[seq=$cleanupSeq] Background cleanup starting...")
                    delay(100)
                    try { vc?.stop() } catch (e: Exception) { Log.w(TAG, "[seq=$cleanupSeq] vCodec.stop error: ${e.message}") }
                    try { vc?.release() } catch (e: Exception) { Log.w(TAG, "[seq=$cleanupSeq] vCodec.release error: ${e.message}") }
                    try { ac?.stop() } catch (_: Exception) {}
                    try { ac?.release() } catch (_: Exception) {}
                    try { ve?.release() } catch (_: Exception) {}
                    try { ae?.release() } catch (_: Exception) {}
                    try { at?.stop() } catch (_: Exception) {}
                    try { at?.flush() } catch (_: Exception) {}
                    try { at?.release() } catch (_: Exception) {}
                    try { tf?.delete() } catch (_: Exception) {}
                    if (activeAudioTrack === at) { activeAudioTrack = null; audioSampleRate = 0 }
                    Log.d(TAG, "[seq=$cleanupSeq] Background cleanup done")
                }
            }
        }
    }

    private suspend fun videoDecodeLoop(seq: Int, codec: MediaCodec, extractor: MediaExtractor, trackIndex: Int) {
        val info = MediaCodec.BufferInfo()
        var startWallTimeUs = -1L
        var startVideoTimeUs = -1L
        var isFirstFrame = true
        var frameCount = 0

        while (seq == currentSeq.get()) {
            if (isPaused.get()) { delay(16); continue }

            try {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            Log.d(TAG, "[seq=$seq] Video: EOS sent")
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        Log.d(TAG, "[seq=$seq] Video: EOS received, frames=$frameCount")
                        break
                    }

                    if (isFirstFrame) {
                        startWallTimeUs = SystemClock.elapsedRealtimeNanos() / 1000
                        startVideoTimeUs = info.presentationTimeUs
                        isFirstFrame = false
                        Log.i(TAG, "[seq=$seq] ★ First frame! PTS=${info.presentationTimeUs}us")
                    } else {
                        val audioTimeUs = getAudioTimeUs()
                        val delayUs = if (audioTimeUs > 0) {
                            (info.presentationTimeUs - startVideoTimeUs) - audioTimeUs
                        } else {
                            val wallElapsed = (SystemClock.elapsedRealtimeNanos() / 1000) - startWallTimeUs
                            val videoElapsed = ((info.presentationTimeUs - startVideoTimeUs) / _speed.value).toLong()
                            videoElapsed - wallElapsed
                        }
                        if (delayUs > 1000) delay(delayUs / 1000)
                    }

                    codec.releaseOutputBuffer(outputIndex, info.size > 0)
                    _currentPosition.value = info.presentationTimeUs / 1000
                    frameCount++
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "[seq=$seq] Video codec IllegalStateException, exiting loop")
                break
            }
        }
        Log.d(TAG, "[seq=$seq] Video decode loop exited, frames=$frameCount")
    }

    private suspend fun audioDecodeLoop(seq: Int, codec: MediaCodec, extractor: MediaExtractor, trackIndex: Int, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        var totalPcmBytes = 0L

        while (seq == currentSeq.get()) {
            if (isPaused.get()) { delay(16); continue }

            try {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "[seq=$seq] Audio format changed: ${codec.outputFormat}")
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outputBuffer.get(chunk)
                            track.write(chunk, 0, info.size)
                            totalPcmBytes += info.size
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "[seq=$seq] Audio codec IllegalStateException, exiting loop")
                break
            }
        }
        Log.d(TAG, "[seq=$seq] Audio decode loop exited, pcmBytes=$totalPcmBytes")
    }

    fun release() {
        Log.i(TAG, "Manual release, currentSeq=${currentSeq.get()}")
        currentSeq.incrementAndGet()
        playerJob?.cancel()
        _state.value = State.IDLE
        _currentPosition.value = 0
        _videoSize.value = 0 to 0
    }

    fun play() {
        isPaused.set(false)
        try { activeAudioTrack?.play() } catch (_: Exception) {}
        if (_state.value == State.PAUSED || _state.value == State.COMPLETED) _state.value = State.PLAYING
        Log.d(TAG, "play()")
    }

    fun pause() {
        isPaused.set(true)
        try { activeAudioTrack?.pause() } catch (_: Exception) {}
        if (_state.value == State.PLAYING) _state.value = State.PAUSED
        Log.d(TAG, "pause()")
    }

    fun togglePlayPause() {
        if (isPaused.get()) play() else pause()
    }

    fun setSpeed(speed: Float) { _speed.value = speed }
    fun seekTo(ms: Long) { _currentPosition.value = ms }

    fun getAudioTimeUs(): Long {
        val track = activeAudioTrack ?: return -1L
        if (audioSampleRate <= 0) return -1L
        val headPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return headPosition * 1_000_000L / audioSampleRate
    }
}
