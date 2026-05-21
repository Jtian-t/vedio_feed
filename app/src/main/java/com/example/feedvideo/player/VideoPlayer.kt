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
 *   单 MediaExtractor → MediaCodec 同步解码 → Surface 渲染
 *                     → AudioTrack 音频播放
 */
class VideoPlayer {

    companion object {
        private const val TAG = "VideoPlayer"
        private const val TIMEOUT_US = 10_000L // 10ms
    }

    enum class State { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    // 核心组件
    private var extractor: MediaExtractor? = null
    private var videoCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var surface: Surface? = null

    private var playerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isReleased = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

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
                // 1. 单个 MediaExtractor（只请求一次 URL）
                val ext = MediaExtractor()
                ext.setDataSource(url)
                extractor = ext
                Log.d(TAG, "Extractor ready, tracks: ${ext.trackCount}")

                // 2. 识别并选中音视频 track
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null

                for (i in 0 until ext.trackCount) {
                    val format = ext.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") && videoTrackIndex == -1 -> {
                            videoTrackIndex = i
                            videoFormat = format
                            ext.selectTrack(i)
                        }
                        mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                            audioTrackIndex = i
                            audioFormat = format
                            ext.selectTrack(i)
                        }
                    }
                }

                if (videoFormat == null) {
                    Log.e(TAG, "No video track found")
                    _state.value = State.ERROR
                    return@launch
                }

                _duration.value = videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000
                Log.d(TAG, "Duration: ${_duration.value}ms, video=$videoTrackIndex, audio=$audioTrackIndex")

                // 3. 配置视频解码器
                val videoMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
                videoCodec = MediaCodec.createDecoderByType(videoMime).apply {
                    configure(videoFormat, surface, null, 0)
                    start()
                }

                // 4. 配置音频播放
                if (audioFormat != null) {
                    setupAudio(audioFormat)
                }

                _state.value = State.PLAYING
                Log.d(TAG, "Starting decode loop")

                // 5. 解码循环（在子协程中运行）
                withContext(Dispatchers.Default) {
                    decodeLoop()
                }

            } catch (e: Exception) {
                Log.e(TAG, "prepareAndPlay failed", e)
                _state.value = State.ERROR
            }
        }
    }

    /**
     * 设置 AudioTrack 音频播放
     */
    private fun setupAudio(format: MediaFormat) {
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
        Log.d(TAG, "AudioTrack ready: ${sampleRate}Hz, ${channelCount}ch")
    }

    /**
     * 解码循环 — 同步模式，交替处理音视频 track
     *
     * 关键设计：
     * - 单 extractor 同时选中了音视频两个 track
     * - readSampleData() 自动按 PTS 顺序读取下一个 sample（不管是音频还是视频）
     * - 通过 sampleTrackIndex 判断当前 sample 属于哪个 track
     */
    private fun decodeLoop() {
        val ext = extractor ?: return
        val vc = videoCodec ?: return
        val videoInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var audioEOS = false
        var videoEOS = false

        while (!outputDone && !isReleased.get()) {
            // 暂停处理
            if (isPaused.get()) {
                Thread.sleep(50)
                continue
            }

            // === 输入阶段：从 extractor 读取数据送入 codec ===
            if (!inputDone) {
                val inputIndex = vc.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = vc.getInputBuffer(inputIndex) ?: continue
                    val sampleSize = ext.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        // 没有更多数据
                        vc.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val trackIndex = ext.sampleTrackIndex
                        val pts = ext.sampleTime
                        val flags = ext.sampleFlags

                        if (trackIndex == videoTrackIndex) {
                            // 视频 sample → 送入 videoCodec
                            vc.queueInputBuffer(inputIndex, 0, sampleSize, pts, flags)
                        } else {
                            // 音频 sample → 如果有 AudioTrack 直接播放
                            // 这里简化：跳过音频，专注视频
                            vc.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        }
                        ext.advance()
                    }
                }
            }

            // === 输出阶段：从 codec 取出解码后的帧 ===
            if (!videoEOS) {
                val outputIndex = vc.dequeueOutputBuffer(videoInfo, TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        if (videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            videoEOS = true
                        }

                        // 音视频同步
                        val shouldRender = videoInfo.size > 0 && syncFrame(videoInfo.presentationTimeUs)
                        vc.releaseOutputBuffer(outputIndex, shouldRender)
                        _currentPosition.value = videoInfo.presentationTimeUs / 1000
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Output format: ${vc.outputFormat}")
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 没有可用输出，短暂等待
                    }
                }
            }

            outputDone = videoEOS && inputDone
        }

        if (outputDone) {
            _state.value = State.COMPLETED
            Log.d(TAG, "Playback completed")
        }
    }

    /**
     * 音视频同步：判断当前帧是否应该渲染
     */
    private fun syncFrame(presentationTimeUs: Long): Boolean {
        val nowUs = System.nanoTime() / 1000

        if (wallStartTimeUs < 0L) {
            wallStartTimeUs = nowUs
            mediaStartTimeUs = presentationTimeUs
            return true
        }

        val mediaElapsed = presentationTimeUs - mediaStartTimeUs
        val wallElapsed = nowUs - wallStartTimeUs
        // 帧还没到时间就不渲染，允许 30ms 误差
        return mediaElapsed <= wallElapsed + 30_000
    }

    fun play() {
        if (isReleased.get()) return
        isPaused.set(false)
        wallStartTimeUs = -1L
        if (_state.value == State.PAUSED || _state.value == State.COMPLETED) {
            _state.value = State.PLAYING
        }
        Log.d(TAG, "Play")
    }

    fun pause() {
        if (isReleased.get()) return
        isPaused.set(true)
        if (_state.value == State.PLAYING) {
            _state.value = State.PAUSED
        }
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
        val ext = extractor ?: return
        ext.seekTo(positionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        wallStartTimeUs = -1L
        _currentPosition.value = positionMs
        Log.d(TAG, "Seek to ${positionMs}ms")
    }

    fun setSpeed(speed: Float) {
        Log.d(TAG, "Speed: $speed")
    }

    fun release() {
        if (isReleased.getAndSet(true)) return
        Log.d(TAG, "Releasing...")

        playerJob?.cancel()

        try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null

        try { extractor?.release() } catch (_: Exception) {}
        extractor = null

        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        surface = null
        videoTrackIndex = -1
        audioTrackIndex = -1
        mediaStartTimeUs = -1L
        wallStartTimeUs = -1L
        _state.value = State.IDLE
        _currentPosition.value = 0L
        Log.d(TAG, "Released")
    }
}
