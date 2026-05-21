package com.example.feedvideo.player

import android.media.*
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自研视频播放器 — MediaCodec + MediaExtractor + AudioTrack。
 * 禁用 ExoPlayer/IjkPlayer，完全基于 Android 底层 API。
 */
class VideoPlayer {

    companion object {
        private const val TAG = "VideoPlayer"
        private const val TIMEOUT_US = 10_000L
    }

    enum class State { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private var videoExtractor: MediaExtractor? = null
    private var audioExtractor: MediaExtractor? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var surface: Surface? = null

    private var playerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isReleased = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var mediaStartTimeUs = -1L
    private var wallStartTimeUs = -1L

    fun prepareAndPlay(url: String, surface: Surface) {
        Log.d(TAG, "prepareAndPlay: $url")
        release()
        isReleased.set(false)
        isPaused.set(false)
        this.surface = surface
        _state.value = State.PREPARING

        playerJob = scope.launch {
            try {
                // ===== 1. 视频 Extractor =====
                val vExt = MediaExtractor()
                vExt.setDataSource(url)
                var videoFormat: MediaFormat? = null
                for (i in 0 until vExt.trackCount) {
                    val fmt = vExt.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && videoFormat == null) {
                        videoFormat = fmt
                        vExt.selectTrack(i)
                    }
                }
                if (videoFormat == null) {
                    Log.e(TAG, "No video track in $url")
                    _state.value = State.ERROR
                    return@launch
                }
                videoExtractor = vExt
                _duration.value = videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000
                Log.d(TAG, "Video: duration=${_duration.value}ms")

                // ===== 2. 视频 Codec =====
                val videoMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
                videoCodec = MediaCodec.createDecoderByType(videoMime).apply {
                    configure(videoFormat, surface, null, 0)
                    start()
                }
                Log.d(TAG, "Video codec: $videoMime")

                // ===== 3. 音频 Extractor + Codec =====
                val aExt = MediaExtractor()
                aExt.setDataSource(url)
                var audioFormat: MediaFormat? = null
                for (i in 0 until aExt.trackCount) {
                    val fmt = aExt.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/") && audioFormat == null) {
                        audioFormat = fmt
                        aExt.selectTrack(i)
                    }
                }
                if (audioFormat != null) {
                    audioExtractor = aExt
                    try {
                        setupAudioPipeline(audioFormat)
                        Log.d(TAG, "Audio pipeline ready")
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio setup failed, video-only mode", e)
                    }
                }

                _state.value = State.PLAYING
                Log.d(TAG, "=== Starting playback ===")

                // ===== 4. 并行解码 =====
                coroutineScope {
                    val vJob = launch(Dispatchers.Default) { videoLoop() }
                    val aJob = launch(Dispatchers.Default) { audioLoop() }
                    vJob.join()
                    aJob.cancel()
                }

            } catch (e: Exception) {
                Log.e(TAG, "prepareAndPlay failed", e)
                _state.value = State.ERROR
            }
        }
    }

    // ==================== 音频管线 ====================

    private fun setupAudioPipeline(format: MediaFormat) {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // AudioTrack（PCM 输出）
        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
            AudioFormat.Builder().setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            minBuf * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()

        // AudioCodec（AAC → PCM 解码）
        audioCodec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
        Log.d(TAG, "Audio codec: $mime ${sampleRate}Hz ${channelCount}ch")
    }

    // ==================== 视频解码循环 ====================

    private fun videoLoop() {
        val ext = videoExtractor ?: return
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false
        var firstFrameRendered = false

        while (!outputEOS && !isReleased.get()) {
            if (isPaused.get()) { Thread.sleep(30); continue }

            // 输入
            if (!inputEOS) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx) ?: continue
                    val size = ext.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                        ext.advance()
                    }
                }
            }

            // 输出
            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIdx >= 0 -> {
                    val isEOS = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (isEOS) outputEOS = true

                    // 首帧：立即渲染，不做同步等待
                    if (!firstFrameRendered && info.size > 0) {
                        codec.releaseOutputBuffer(outIdx, true)
                        firstFrameRendered = true
                        mediaStartTimeUs = info.presentationTimeUs
                        wallStartTimeUs = System.nanoTime() / 1000
                        _currentPosition.value = info.presentationTimeUs / 1000
                        Log.d(TAG, "First frame rendered at ${info.presentationTimeUs / 1000}ms")
                        continue
                    }

                    // 后续帧：音视频同步
                    val render = info.size > 0 && shouldRender(info.presentationTimeUs)
                    codec.releaseOutputBuffer(outIdx, render)
                    if (info.size > 0) _currentPosition.value = info.presentationTimeUs / 1000
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Video format: ${codec.outputFormat}")
                }
            }
        }
        if (outputEOS) { _state.value = State.COMPLETED; Log.d(TAG, "Video done") }
    }

    // ==================== 音频解码循环 ====================

    private fun audioLoop() {
        val ext = audioExtractor ?: return
        val codec = audioCodec ?: return
        val track = audioTrack ?: return
        val info = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false

        while (!outputEOS && !isReleased.get()) {
            if (isPaused.get()) { Thread.sleep(30); continue }

            // 输入
            if (!inputEOS) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx) ?: continue
                    val size = ext.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                        ext.advance()
                    }
                }
            }

            // 输出：PCM → AudioTrack
            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIdx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEOS = true
                    val buf = codec.getOutputBuffer(outIdx) ?: continue
                    if (info.size > 0) {
                        val pcm = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(pcm)
                        track.write(pcm, 0, info.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Audio format: ${codec.outputFormat}")
                }
            }
        }
        if (outputEOS) Log.d(TAG, "Audio done")
    }

    // ==================== 音视频同步 ====================

    private fun shouldRender(presentationTimeUs: Long): Boolean {
        val nowUs = System.nanoTime() / 1000
        if (wallStartTimeUs < 0L) return true // 还没初始化，直接渲染
        val mediaElapsed = presentationTimeUs - mediaStartTimeUs
        val wallElapsed = nowUs - wallStartTimeUs
        // 帧时间 <= 当前时间 + 30ms 容忍 → 渲染
        return mediaElapsed <= wallElapsed + 30_000
    }

    // ==================== 播放控制 ====================

    fun play() {
        if (isReleased.get()) return
        isPaused.set(false)
        wallStartTimeUs = -1L // 重置同步基准，下次首帧立即渲染
        if (_state.value == State.PAUSED || _state.value == State.COMPLETED) _state.value = State.PLAYING
        Log.d(TAG, "Play")
    }

    fun pause() {
        if (isReleased.get()) return
        isPaused.set(true)
        if (_state.value == State.PLAYING) _state.value = State.PAUSED
        Log.d(TAG, "Pause")
    }

    fun togglePlayPause() {
        when (_state.value) {
            State.PLAYING -> pause()
            State.PAUSED, State.COMPLETED -> play()
            else -> {}
        }
    }

    fun seekTo(positionMs: Long) {
        if (isReleased.get()) return
        videoExtractor?.seekTo(positionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        audioExtractor?.seekTo(positionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        wallStartTimeUs = -1L
        _currentPosition.value = positionMs
    }

    fun setSpeed(speed: Float) { Log.d(TAG, "Speed: $speed") }

    // ==================== 释放 ====================

    fun release() {
        if (isReleased.getAndSet(true)) return
        Log.d(TAG, "Releasing...")
        playerJob?.cancel()
        try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        try { audioCodec?.stop(); audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
        try { videoExtractor?.release() } catch (_: Exception) {}
        videoExtractor = null
        try { audioExtractor?.release() } catch (_: Exception) {}
        audioExtractor = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        surface = null
        mediaStartTimeUs = -1L
        wallStartTimeUs = -1L
        _state.value = State.IDLE
        _currentPosition.value = 0L
        Log.d(TAG, "Released")
    }
}
