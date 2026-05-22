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
 * 使用 generation 计数器 + 同步等待确保 release/prepareAndPlay 时序正确。
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

    private var currentGen = 0
    private var currentJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isPaused = AtomicBoolean(false)

    // 音视频同步基准
    private var mediaStartTimeUs = -1L
    private var wallStartTimeUs = -1L

    /**
     * 准备并播放视频。
     * 内部会先取消上一个任务并同步等待其资源释放完成，再启动新任务。
     */
    fun prepareAndPlay(url: String, surface: Surface) {
        Log.d(TAG, "prepareAndPlay: $url")

        // ===== 同步等待旧任务退出并释放资源 =====
        currentGen++
        val oldJob = currentJob
        if (oldJob != null) {
            oldJob.cancel()
            // 阻塞等待旧任务完成（包括 finally 中的资源释放）
            // 这是安全的：旧 job 在 Dispatchers.Default 运行，当前在 Main
            runBlocking { oldJob.join() }
        }
        currentJob = null

        val gen = currentGen
        isPaused.set(false)
        _state.value = State.PREPARING

        currentJob = scope.launch {
            var videoExtractor: MediaExtractor? = null
            var audioExtractor: MediaExtractor? = null
            var videoCodec: MediaCodec? = null
            var audioCodec: MediaCodec? = null
            var audioTrack: AudioTrack? = null

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
                    if (gen == currentGen) _state.value = State.ERROR
                    return@launch
                }
                if (!isActive) return@launch
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
                        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
                        val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
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
                        audioCodec = MediaCodec.createDecoderByType(mime).apply {
                            configure(audioFormat, null, null, 0)
                            start()
                        }
                        Log.d(TAG, "Audio pipeline: $mime ${sampleRate}Hz ${channelCount}ch")
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio setup failed, video-only mode", e)
                    }
                }

                if (!isActive) return@launch
                if (gen == currentGen) _state.value = State.PLAYING
                Log.d(TAG, "=== Starting playback (gen=$gen) ===")

                // ===== 4. 并行解码 =====
                val vJob = launch { videoLoop(videoExtractor!!, videoCodec!!, gen) }
                val aJob = if (audioExtractor != null && audioCodec != null && audioTrack != null) {
                    launch { audioLoop(audioExtractor!!, audioCodec!!, audioTrack!!, gen) }
                } else null
                vJob.join()
                aJob?.cancel()

                if (gen == currentGen) {
                    _state.value = State.COMPLETED
                    Log.d(TAG, "Video done (gen=$gen)")
                }

            } catch (e: CancellationException) {
                throw e // 正常取消，由 finally 清理
            } catch (e: Exception) {
                Log.e(TAG, "prepareAndPlay failed (gen=$gen)", e)
                if (gen == currentGen) _state.value = State.ERROR
            } finally {
                // 清理本代资源
                Log.d(TAG, "Cleaning up gen=$gen resources")
                try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
                try { audioCodec?.stop(); audioCodec?.release() } catch (_: Exception) {}
                try { videoExtractor?.release() } catch (_: Exception) {}
                try { audioExtractor?.release() } catch (_: Exception) {}
                try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
                Log.d(TAG, "Cleanup done for gen=$gen")
            }
        }
    }

    // ==================== 视频解码循环 ====================

    private fun videoLoop(ext: MediaExtractor, codec: MediaCodec, gen: Int) {
        val info = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false
        var firstFrameRendered = false

        while (!outputEOS && gen == currentGen) {
            if (isPaused.get()) { Thread.sleep(30); continue }

            try {
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

                        if (!firstFrameRendered && info.size > 0) {
                            codec.releaseOutputBuffer(outIdx, true)
                            firstFrameRendered = true
                            mediaStartTimeUs = info.presentationTimeUs
                            wallStartTimeUs = System.nanoTime() / 1000
                            _currentPosition.value = info.presentationTimeUs / 1000
                            Log.d(TAG, "First frame: ${info.presentationTimeUs / 1000}ms")
                            continue
                        }

                        val render = info.size > 0 && shouldRender(info.presentationTimeUs)
                        codec.releaseOutputBuffer(outIdx, render)
                        if (info.size > 0) _currentPosition.value = info.presentationTimeUs / 1000
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Thread.sleep(2)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Video format: ${codec.outputFormat}")
                    }
                }
            } catch (e: Exception) {
                if (gen != currentGen) break // 被取消，正常退出
                Log.e(TAG, "Video loop error (gen=$gen)", e)
                break
            }
        }
        Log.d(TAG, "Video loop exit (gen=$gen, currentGen=$currentGen)")
    }

    // ==================== 音频解码循环 ====================

    private fun audioLoop(ext: MediaExtractor, codec: MediaCodec, track: AudioTrack, gen: Int) {
        val info = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false

        while (!outputEOS && gen == currentGen) {
            if (isPaused.get()) { Thread.sleep(30); continue }

            try {
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
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Thread.sleep(2)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Audio format: ${codec.outputFormat}")
                    }
                }
            } catch (e: Exception) {
                if (gen != currentGen) break
                Log.e(TAG, "Audio loop error (gen=$gen)", e)
                break
            }
        }
        Log.d(TAG, "Audio loop exit (gen=$gen)")
    }

    // ==================== 音视频同步 ====================

    private fun shouldRender(presentationTimeUs: Long): Boolean {
        val nowUs = System.nanoTime() / 1000
        if (wallStartTimeUs < 0L) return true
        val mediaElapsed = presentationTimeUs - mediaStartTimeUs
        val wallElapsed = nowUs - wallStartTimeUs
        return mediaElapsed <= wallElapsed + 30_000
    }

    // ==================== 播放控制 ====================

    fun play() {
        isPaused.set(false)
        wallStartTimeUs = -1L
        if (_state.value == State.PAUSED || _state.value == State.COMPLETED) _state.value = State.PLAYING
        Log.d(TAG, "Play")
    }

    fun pause() {
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
        _currentPosition.value = positionMs
    }

    fun setSpeed(speed: Float) { Log.d(TAG, "Speed: $speed") }

    // ==================== 释放 ====================

    fun release() {
        Log.d(TAG, "Releasing (gen=$currentGen)...")
        currentGen++
        val job = currentJob
        if (job != null) {
            job.cancel()
            // 等待资源释放完成（最多 2 秒）
            try {
                runBlocking { withTimeout(2000) { job.join() } }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "Release timeout, forcing cleanup")
            }
        }
        currentJob = null
        _state.value = State.IDLE
        _currentPosition.value = 0L
        mediaStartTimeUs = -1L
        wallStartTimeUs = -1L
        Log.d(TAG, "Released")
    }
}
