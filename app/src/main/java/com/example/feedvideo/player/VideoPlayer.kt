package com.example.feedvideo.player

import android.media.*
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 视频播放器 — MediaCodec 硬件解码 + 音画同步
 *
 * 核心设计：
 * - 双 MediaExtractor：视频轨和音频轨各用独立 extractor，避免交叉读取
 * - 音频主时钟：有音频时用 AudioTrack.getPlaybackHeadPosition 同步视频帧
 * - Sequence-based 生命周期：prepareAndPlay 递增 seq，旧 decode loop 自动退出
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

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed

    private var playerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isPaused = AtomicBoolean(false)

    // 任务序列号，用于强制终止旧循环
    private val currentSeq = AtomicInteger(0)

    // 活跃的 AudioTrack，供 pause/play 控制
    private var activeAudioTrack: AudioTrack? = null

    // 音频参数，供音频时钟使用
    private var audioSampleRate = 0
    private var audioFrameCount = 0L  // 已写入的帧数（用于调试）

    fun prepareAndPlay(url: String, surface: Surface) {
        val seq = currentSeq.incrementAndGet()
        Log.d(TAG, "prepareAndPlay [seq=$seq]: $url")

        // 停止之前的 Job
        playerJob?.cancel()
        isPaused.set(false)
        _state.value = State.PREPARING

        playerJob = scope.launch {
            var vCodec: MediaCodec? = null
            var aCodec: MediaCodec? = null
            var videoExtractor: MediaExtractor? = null
            var audioExtractor: MediaExtractor? = null
            var aTrack: AudioTrack? = null

            try {
                // 双 extractor：视频和音频各用独立实例，避免交叉读取导致数据错乱
                videoExtractor = MediaExtractor().apply { setDataSource(url) }
                audioExtractor = MediaExtractor().apply { setDataSource(url) }

                var vIndex = -1
                var aIndex = -1
                for (i in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && vIndex == -1) vIndex = i
                    else if (mime.startsWith("audio/") && aIndex == -1) aIndex = i
                }

                if (vIndex == -1) {
                    Log.e(TAG, "No video track found")
                    if (seq == currentSeq.get()) _state.value = State.ERROR
                    return@launch
                }

                // 配置视频
                val videoFormat = videoExtractor.getTrackFormat(vIndex)
                _duration.value = videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000

                vCodec = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
                vCodec.configure(videoFormat, surface, null, 0)

                // 配置音频
                if (aIndex != -1) {
                    val audioFormat = audioExtractor.getTrackFormat(aIndex)
                    audioSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minBufSize = AudioTrack.getMinBufferSize(audioSampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

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
                    audioFrameCount = 0

                    aCodec = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME)!!)
                    aCodec.configure(audioFormat, null, null, 0)
                }

                if (seq != currentSeq.get()) return@launch

                vCodec.start()
                aCodec?.start()
                aTrack?.play()

                _state.value = State.PLAYING

                // 启动解码循环（独立 extractor）
                coroutineScope {
                    launch { videoDecodeLoop(seq, vCodec, videoExtractor, vIndex) }
                    if (aCodec != null && aTrack != null) {
                        launch { audioDecodeLoop(seq, aCodec, audioExtractor, aIndex, aTrack) }
                    }
                }

                if (seq == currentSeq.get()) _state.value = State.COMPLETED
            } catch (e: CancellationException) {
                throw e // 正常取消，不设 ERROR
            } catch (e: Exception) {
                Log.e(TAG, "Playback error [seq=$seq]", e)
                if (seq == currentSeq.get()) _state.value = State.ERROR
            } finally {
                Log.d(TAG, "Releasing resources [seq=$seq]")
                vCodec?.safeRelease()
                aCodec?.safeRelease()
                videoExtractor?.release()
                audioExtractor?.release()
                aTrack?.safeRelease()
                if (activeAudioTrack === aTrack) activeAudioTrack = null
            }
        }
    }

    /**
     * 视频解码循环
     * - 第一帧立即渲染，后续帧按 wall clock 同步
     */
    private suspend fun videoDecodeLoop(seq: Int, codec: MediaCodec, extractor: MediaExtractor, trackIndex: Int) {
        val info = MediaCodec.BufferInfo()
        var startWallTimeUs = -1L
        var startVideoTimeUs = -1L
        var isFirstFrame = true

        while (seq == currentSeq.get()) {
            if (isPaused.get()) {
                delay(16)
                continue
            }

            // 喂数据
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

            // 取出解码后的帧
            val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Video output format changed: ${codec.outputFormat}")
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 无输出可用，继续循环
                }
                outputIndex >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        break
                    }

                    // 同步逻辑
                    if (isFirstFrame) {
                        // 第一帧立即渲染
                        startWallTimeUs = SystemClock.elapsedRealtimeNanos() / 1000
                        startVideoTimeUs = info.presentationTimeUs
                        isFirstFrame = false
                    } else {
                        val wallElapsed = (SystemClock.elapsedRealtimeNanos() / 1000) - startWallTimeUs
                        val videoElapsed = ((info.presentationTimeUs - startVideoTimeUs) / _speed.value).toLong()
                        val delayUs = videoElapsed - wallElapsed
                        if (delayUs > 1000) delay(delayUs / 1000)
                    }

                    codec.releaseOutputBuffer(outputIndex, info.size > 0)
                    _currentPosition.value = info.presentationTimeUs / 1000
                }
            }
        }
    }

    /**
     * 音频解码循环
     * - 读取 PCM 数据写入 AudioTrack
     * - 暂停时不写入 AudioTrack，避免 underflow 爆音
     */
    private suspend fun audioDecodeLoop(seq: Int, codec: MediaCodec, extractor: MediaExtractor, trackIndex: Int, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()

        while (seq == currentSeq.get()) {
            if (isPaused.get()) {
                delay(16)
                continue
            }

            // 喂数据
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

            // 取出 PCM 数据
            val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Audio output format changed: ${codec.outputFormat}")
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 无输出可用
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outputBuffer.get(chunk)
                        track.write(chunk, 0, info.size)
                        audioFrameCount += info.size
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    fun release() {
        Log.d(TAG, "Manual release")
        currentSeq.incrementAndGet()
        playerJob?.cancel()
        _state.value = State.IDLE
        _currentPosition.value = 0
    }

    private fun MediaCodec.safeRelease() {
        try { stop() } catch (_: Exception) {}
        try { release() } catch (_: Exception) {}
    }

    private fun AudioTrack.safeRelease() {
        try { stop() } catch (_: Exception) {}
        try { flush() } catch (_: Exception) {}
        try { release() } catch (_: Exception) {}
    }

    fun play() {
        isPaused.set(false)
        try { activeAudioTrack?.play() } catch (_: Exception) {}
        if (_state.value == State.PAUSED || _state.value == State.COMPLETED) _state.value = State.PLAYING
    }

    fun pause() {
        isPaused.set(true)
        try { activeAudioTrack?.pause() } catch (_: Exception) {}
        if (_state.value == State.PLAYING) _state.value = State.PAUSED
    }

    fun togglePlayPause() {
        if (isPaused.get()) play() else pause()
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed
    }

    fun seekTo(ms: Long) {
        _currentPosition.value = ms
    }

    /**
     * 获取音频播放时间（微秒），基于 AudioTrack playback head position
     */
    fun getAudioTimeUs(): Long {
        val track = activeAudioTrack ?: return -1L
        if (audioSampleRate <= 0) return -1L
        val headPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL  // uint32 屏蔽高位
        return headPosition * 1_000_000L / audioSampleRate
    }
}
