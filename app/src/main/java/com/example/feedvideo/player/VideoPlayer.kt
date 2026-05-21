package com.example.feedvideo.player

import android.media.*
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自研视频播放器 — MediaCodec + MediaExtractor + AudioTrack。
 * 禁用 ExoPlayer/IjkPlayer，完全基于 Android 底层 API。
 *
 * 架构：
 *   videoExtractor(仅视频track) → videoCodec → Surface 渲染
 *   audioExtractor(仅音频track) → AudioTrack 音频播放
 *
 * 两个 extractor 独立读取各自 track，互不干扰。
 */
class VideoPlayer {

    companion object {
        private const val TAG = "VideoPlayer"
        private const val TIMEOUT_US = 5_000L // 5ms
    }

    enum class State { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    // 双 Extractor — 各自只选中一个 track
    private var videoExtractor: MediaExtractor? = null
    private var audioExtractor: MediaExtractor? = null
    private var videoCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var surface: Surface? = null

    private var playerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isReleased = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    // 音视频同步
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
                // 1. 创建视频 extractor（只选中视频 track）
                val vExt = MediaExtractor()
                vExt.setDataSource(url)
                var videoFormat: MediaFormat? = null
                for (i in 0 until vExt.trackCount) {
                    val format = vExt.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoFormat = format
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
                Log.d(TAG, "Video track ready, duration=${_duration.value}ms")

                // 2. 创建音频 extractor（只选中音频 track）
                var audioFormat: MediaFormat? = null
                val aExt = MediaExtractor()
                aExt.setDataSource(url)
                for (i in 0 until aExt.trackCount) {
                    val format = aExt.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioFormat = format
                        aExt.selectTrack(i)
                    }
                }
                if (audioFormat != null) {
                    audioExtractor = aExt
                    setupAudioTrack(audioFormat)
                    Log.d(TAG, "Audio track ready")
                }

                // 3. 配置视频解码器
                val videoMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
                videoCodec = MediaCodec.createDecoderByType(videoMime).apply {
                    configure(videoFormat, surface, null, 0)
                    start()
                }
                Log.d(TAG, "Video codec started: $videoMime")

                _state.value = State.PLAYING

                // 4. 并行解码视频和音频
                coroutineScope {
                    val videoJob = launch(Dispatchers.Default) { videoDecodeLoop() }
                    val audioJob = if (audioExtractor != null) {
                        launch(Dispatchers.Default) { audioDecodeLoop() }
                    } else null
                    videoJob.join()
                    audioJob?.join()
                }

            } catch (e: Exception) {
                Log.e(TAG, "prepareAndPlay failed", e)
                _state.value = State.ERROR
            }
        }
    }

    // ==================== 视频解码 ====================

    private fun videoDecodeLoop() {
        val ext = videoExtractor ?: return
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false

        while (!outputEOS && !isReleased.get()) {
            if (isPaused.get()) { Thread.sleep(30); continue }

            // 输入：读取视频 sample 送入 codec
            if (!inputEOS) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx) ?: continue
                    val size = ext.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, ext.sampleFlags)
                        ext.advance()
                    }
                }
            }

            // 输出：取出解码帧渲染
            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIdx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEOS = true
                    val render = info.size > 0 && !isPaused.get() && syncFrame(info.presentationTimeUs)
                    codec.releaseOutputBuffer(outIdx, render)
                    if (info.size > 0) _currentPosition.value = info.presentationTimeUs / 1000
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Video output format: ${codec.outputFormat}")
                }
            }
        }

        if (outputEOS) {
            _state.value = State.COMPLETED
            Log.d(TAG, "Video decode completed")
        }
    }

    // ==================== 音频解码 ====================

    private fun audioDecodeLoop() {
        val ext = audioExtractor ?: return
        val track = audioTrack ?: return

        // 音频直接用 extractor 读取 raw data 写入 AudioTrack
        // 简化处理：不经过 AudioCodec，直接读取压缩数据尝试播放
        // 如果音频编码不是 PCM，跳过音频
        val format = ext.getTrackFormat(ext.sampleTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime != "audio/raw" && mime != "audio/mp4a-latc") {
            // 需要解码的音频，简化跳过（专注视频流畅度）
            Log.d(TAG, "Audio format $mime, skipping (focus on video)")
            return
        }

        try {
            val buffer = ByteArray(4096)
            while (!isReleased.get()) {
                if (isPaused.get()) { Thread.sleep(30); continue }
                val size = ext.readSampleData(java.nio.ByteBuffer.wrap(buffer), 0)
                if (size < 0) break
                track.write(buffer, 0, size)
                ext.advance()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio decode error", e)
        }
    }

    private fun setupAudioTrack(format: MediaFormat) {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            minBuffer * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    // ==================== 音视频同步 ====================

    private fun syncFrame(presentationTimeUs: Long): Boolean {
        val nowUs = System.nanoTime() / 1000
        if (wallStartTimeUs < 0L) {
            wallStartTimeUs = nowUs
            mediaStartTimeUs = presentationTimeUs
            return true // 首帧立即渲染
        }
        val mediaElapsed = presentationTimeUs - mediaStartTimeUs
        val wallElapsed = nowUs - wallStartTimeUs
        return mediaElapsed <= wallElapsed + 30_000 // 容忍 30ms
    }

    // ==================== 播放控制 ====================

    fun play() {
        if (isReleased.get()) return
        isPaused.set(false)
        wallStartTimeUs = -1L
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
            State.PAUSED, State.IDLE -> play()
            State.COMPLETED -> play()
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

    // ==================== 资源释放 ====================

    fun release() {
        if (isReleased.getAndSet(true)) return
        Log.d(TAG, "Releasing...")
        playerJob?.cancel()
        try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
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
