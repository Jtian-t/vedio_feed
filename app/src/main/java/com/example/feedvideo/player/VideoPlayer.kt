package com.example.feedvideo.player

import android.media.*
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自研视频播放器 — MediaCodec + MediaExtractor + AudioTrack。
 * 禁用 ExoPlayer/IjkPlayer，完全基于 Android 底层 API。
 *
 * 设计要点：
 * - 每次 prepareAndPlay 递增 seq，旧任务通过 seq != currentSeq 自行退出
 * - 不使用 runBlocking，所有协程在 Dispatchers.Default 上运行
 * - 资源释放放在每个任务自己的 finally 块中
 * - 音频为主时钟，视频向音频对齐（A/V sync）
 */
class VideoPlayer {

    companion object {
        private const val TAG = "VideoPlayer"
        private const val TIMEOUT_US = 10_000L
        // 视频帧允许提前渲染的时间窗口（微秒）
        private const val AV_SYNC_TOLERANCE_US = 30_000L
    }

    enum class State { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    // 序列号：每次 prepareAndPlay +1，decode loop 用 seq == currentSeq 判断是否继续
    @Volatile
    private var currentSeq = 0

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isPaused = AtomicBoolean(false)

    // 音视频同步 —— 墙钟回退（无音频时使用）
    @Volatile
    private var mediaStartUs = -1L
    @Volatile
    private var wallStartUs = -1L

    // 音频同步 —— 首帧 PTS & 采样率，用于 A/V sync 时钟
    @Volatile
    private var audioStartUs = -1L
    @Volatile
    private var audioSampleRate = 44100
    @Volatile
    private var hasAudio = false

    // 当前活跃的 AudioTrack —— 如果 float 重建会被替换
    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    fun prepareAndPlay(url: String, surface: Surface) {
        Log.d(TAG, "prepareAndPlay: $url")

        // 让旧任务自行退出
        currentSeq++
        val seq = currentSeq
        job?.cancel()

        isPaused.set(false)
        _state.value = State.PREPARING
        // 重置同步状态
        audioStartUs = -1L
        mediaStartUs = -1L
        wallStartUs = -1L
        hasAudio = false
        currentAudioTrack = null

        job = scope.launch {
            // 所有资源都是局部变量，finally 负责释放
            var vExt: MediaExtractor? = null
            var aExt: MediaExtractor? = null
            var vCodec: MediaCodec? = null
            var aCodec: MediaCodec? = null

            try {
                // === 1. 视频 Extractor ===
                vExt = MediaExtractor().also { it.setDataSource(url) }
                var vFmt: MediaFormat? = null
                for (i in 0 until vExt.trackCount) {
                    val f = vExt.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (m.startsWith("video/") && vFmt == null) {
                        vFmt = f
                        vExt.selectTrack(i)
                    }
                }
                if (vFmt == null) {
                    Log.e(TAG, "No video track: $url")
                    if (seq == currentSeq) _state.value = State.ERROR
                    return@launch
                }
                if (!isActive) return@launch
                _duration.value = vFmt.getLong(MediaFormat.KEY_DURATION) / 1000

                // === 2. 视频 Codec ===
                val vMime = vFmt.getString(MediaFormat.KEY_MIME)!!
                vCodec = MediaCodec.createDecoderByType(vMime).also {
                    it.configure(vFmt, surface, null, 0)
                    it.start()
                }
                Log.d(TAG, "Video codec: $vMime, duration=${_duration.value}ms")

                // === 3. 音频 ===
                aExt = MediaExtractor().also { it.setDataSource(url) }
                var aFmt: MediaFormat? = null
                for (i in 0 until aExt.trackCount) {
                    val f = aExt.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (m.startsWith("audio/") && aFmt == null) {
                        aFmt = f
                        aExt.selectTrack(i)
                    }
                }
                if (aFmt != null) {
                    try {
                        val aMime = aFmt.getString(MediaFormat.KEY_MIME) ?: ""
                        val sr = aFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val ch = aFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val cfg = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

                        // 先以 16-bit 计算 min buffer
                        val buf16 = AudioTrack.getMinBufferSize(sr, cfg, AudioFormat.ENCODING_PCM_16BIT)
                        // 使用 minBuf * 4 避免因 buffer 不足导致音频卡顿
                        val track = AudioTrack(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                            AudioFormat.Builder()
                                .setSampleRate(sr).setChannelMask(cfg)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                            buf16 * 4, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
                        )
                        track.play()
                        currentAudioTrack = track
                        audioSampleRate = sr
                        aCodec = MediaCodec.createDecoderByType(aMime).also {
                            it.configure(aFmt, null, null, 0)
                            it.start()
                        }
                        hasAudio = true
                        Log.d(TAG, "Audio: $aMime ${sr}Hz ${ch}ch buf=${buf16 * 4}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio setup failed", e)
                        hasAudio = false
                    }
                } else {
                    hasAudio = false
                }

                if (!isActive || seq != currentSeq) return@launch
                _state.value = State.PLAYING
                Log.d(TAG, "=== Playback started (seq=$seq) hasAudio=$hasAudio ===")

                // === 4. 并行解码 ===
                val vj = launch { decodeVideo(vExt!!, vCodec!!, seq) }
                val aj = if (aExt != null && aCodec != null && hasAudio) {
                    launch { decodeAudio(aExt!!, aCodec!!, seq) }
                } else null
                vj.join()
                aj?.cancel()

                if (seq == currentSeq) {
                    _state.value = State.COMPLETED
                    Log.d(TAG, "Completed (seq=$seq)")
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error (seq=$seq)", e)
                if (seq == currentSeq) _state.value = State.ERROR
            } finally {
                Log.d(TAG, "Cleanup seq=$seq")
                try { vCodec?.stop(); vCodec?.release() } catch (_: Exception) {}
                try { aCodec?.stop(); aCodec?.release() } catch (_: Exception) {}
                try { vExt?.release() } catch (_: Exception) {}
                try { aExt?.release() } catch (_: Exception) {}
                try { currentAudioTrack?.stop(); currentAudioTrack?.release() } catch (_: Exception) {}
                currentAudioTrack = null
            }
        }
    }

    // ==================== 视频解码 ====================

    private fun decodeVideo(ext: MediaExtractor, codec: MediaCodec, seq: Int) {
        val info = MediaCodec.BufferInfo()
        var inEos = false
        var outEos = false
        var firstDone = false

        while (!outEos && seq == currentSeq) {
            if (isPaused.get()) { Thread.sleep(15); continue }

            // 送数据
            if (!inEos) {
                try {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx) ?: continue
                        val n = ext.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                } catch (e: Exception) {
                    if (seq == currentSeq) Log.e(TAG, "Video input error", e)
                    break
                }
            }

            // 取帧
            try {
                val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    idx >= 0 -> {
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (eos) outEos = true

                        if (info.size > 0) {
                            if (!firstDone) {
                                // 首帧立即渲染，不等待同步
                                codec.releaseOutputBuffer(idx, true)
                                firstDone = true
                                mediaStartUs = info.presentationTimeUs
                                wallStartUs = System.nanoTime() / 1000
                                _currentPosition.value = info.presentationTimeUs / 1000
                                Log.d(TAG, "First frame ${info.presentationTimeUs / 1000}ms")
                                continue
                            }

                            val render = shouldRenderVideo(info.presentationTimeUs)
                            codec.releaseOutputBuffer(idx, render)
                            _currentPosition.value = info.presentationTimeUs / 1000
                        } else {
                            codec.releaseOutputBuffer(idx, false)
                        }
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Thread.sleep(2)
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        Log.d(TAG, "Vfmt: ${codec.outputFormat}")
                }
            } catch (e: Exception) {
                if (seq == currentSeq) Log.e(TAG, "Video output error", e)
                break
            }
        }
        Log.d(TAG, "Video exit seq=$seq cur=$currentSeq")
    }

    /**
     * 判断当前视频帧是否应该渲染。
     * 有音频时以音频时钟为主时钟；无音频时退回墙钟同步。
     */
    private fun shouldRenderVideo(videoPtsUs: Long): Boolean {
        return if (hasAudio) {
            val audioUs = getAudioTimeUs()
            // 视频 PTS <= 音频时钟 + 容差 → 该渲染了
            videoPtsUs <= audioUs + AV_SYNC_TOLERANCE_US
        } else {
            checkPts(videoPtsUs)
        }
    }

    // ==================== 音频解码 ====================

    private fun decodeAudio(ext: MediaExtractor, codec: MediaCodec, seq: Int) {
        val info = MediaCodec.BufferInfo()
        var inEos = false
        var outEos = false
        // 是否已检测过 codec 输出格式（float vs 16-bit）
        var fmtChecked = false

        while (!outEos && seq == currentSeq) {
            if (isPaused.get()) { Thread.sleep(15); continue }

            if (!inEos) {
                try {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx) ?: continue
                        val n = ext.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                } catch (e: Exception) {
                    if (seq == currentSeq) Log.e(TAG, "Audio input error", e)
                    break
                }
            }

            try {
                val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    idx >= 0 -> {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true

                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(idx)
                            if (buf != null) {
                                // 首次音频 PTS，用于校准 audio clock
                                if (audioStartUs < 0) {
                                    audioStartUs = info.presentationTimeUs
                                    Log.d(TAG, "Audio start PTS: ${info.presentationTimeUs}us")
                                }

                                // 检查输出格式，判断是否需要 float 支持
                                if (!fmtChecked) {
                                    fmtChecked = true
                                    val outFmt = codec.outputFormat
                                    val pcmEncoding = outFmt.getInteger(
                                        MediaFormat.KEY_PCM_ENCODING,
                                        AudioFormat.ENCODING_PCM_16BIT
                                    )
                                    if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                        Log.d(TAG, "Audio output is float PCM, recreating AudioTrack")
                                        recreateAudioTrackForFloat()
                                    }
                                }

                                // 写入 AudioTrack。
                                // MODE_STREAM 下 AudioTrack 内部队列满时 write() 会阻塞，
                                // 这正是背压机制 —— buffer 足够大（minBuf*4），
                                // 阻塞时间短且均匀，不会产生可闻卡顿。
                                writePcmToAudioTrack(buf, info)
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Thread.sleep(2)
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Afmt: ${codec.outputFormat}")
                        fmtChecked = false // 重新检查格式
                    }
                }
            } catch (e: Exception) {
                if (seq == currentSeq) Log.e(TAG, "Audio output error", e)
                break
            }
        }
        Log.d(TAG, "Audio exit seq=$seq")
    }

    /**
     * 将解码后的 PCM 数据写入 currentAudioTrack，自动处理 float 与 16-bit。
     */
    private fun writePcmToAudioTrack(buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        val track = currentAudioTrack ?: return
        buf.position(info.offset)
        buf.limit(info.offset + info.size)

        if (track.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            if (buf.order() != ByteOrder.LITTLE_ENDIAN) {
                buf.order(ByteOrder.LITTLE_ENDIAN)
            }
            val floatBuf = buf.asFloatBuffer()
            val floats = FloatArray(floatBuf.remaining())
            floatBuf.get(floats)
            track.write(floats, 0, floats.size, AudioTrack.WRITE_BLOCKING)
        } else {
            val pcm = ByteArray(info.size)
            buf.get(pcm)
            track.write(pcm, 0, info.size)
        }
    }

    /**
     * 当检测到 codec 输出为 float PCM 时，用 float 编码重建 AudioTrack。
     * 同时更新 currentAudioTrack 和 audioSampleRate。
     */
    private fun recreateAudioTrackForFloat() {
        val oldTrack = currentAudioTrack ?: return
        try {
            val sr = audioSampleRate
            // 根据旧 track 的声道数确定 channel mask
            val chCount = oldTrack.channelCount
            val chMask = if (chCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val buf = AudioTrack.getMinBufferSize(sr, chMask, AudioFormat.ENCODING_PCM_FLOAT)
            val newTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                AudioFormat.Builder()
                    .setSampleRate(sr).setChannelMask(chMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build(),
                buf * 4, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            newTrack.play()
            oldTrack.stop()
            oldTrack.release()
            currentAudioTrack = newTrack
            Log.d(TAG, "Recreated AudioTrack with float encoding, buf=${buf * 4}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate AudioTrack for float", e)
        }
    }

    // ==================== 音频时钟 ====================

    /**
     * 获取当前 AudioTrack 播放位置对应的媒体时间（微秒）。
     * playbackHeadPosition 返回已播放的 PCM 帧数，
     * 除以采样率得到秒数，再乘 1_000_000 得到微秒。
     * 加上 audioStartUs 偏移，使其与媒体 PTS 对齐。
     */
    private fun getAudioTimeUs(): Long {
        val track = currentAudioTrack ?: return 0L
        // playbackHeadPosition 是 uint32，需要屏蔽高位
        val framesPlayed = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val elapsedUs = (framesPlayed * 1_000_000L) / audioSampleRate
        val start = audioStartUs
        return if (start >= 0) start + elapsedUs else elapsedUs
    }

    // ==================== 墙钟同步（无音频时的回退） ====================

    private fun checkPts(ptsUs: Long): Boolean {
        val wall = wallStartUs
        if (wall < 0L) return true
        val now = System.nanoTime() / 1000
        return (ptsUs - mediaStartUs) <= (now - wall) + AV_SYNC_TOLERANCE_US
    }

    // ==================== 控制 ====================

    fun play() {
        isPaused.set(false)
        wallStartUs = -1L
        if (_state.value == State.PAUSED || _state.value == State.COMPLETED) _state.value = State.PLAYING
    }

    fun pause() {
        isPaused.set(true)
        if (_state.value == State.PLAYING) _state.value = State.PAUSED
    }

    fun togglePlayPause() {
        when (_state.value) {
            State.PLAYING -> pause()
            State.PAUSED, State.COMPLETED -> play()
            else -> {}
        }
    }

    fun seekTo(ms: Long) { _currentPosition.value = ms }
    fun setSpeed(speed: Float) { Log.d(TAG, "Speed $speed") }

    // ==================== 释放 ====================

    fun release() {
        Log.d(TAG, "release()")
        currentSeq++
        job?.cancel()
        job = null
        _state.value = State.IDLE
        _currentPosition.value = 0L
        _duration.value = 0L
        mediaStartUs = -1L
        wallStartUs = -1L
        audioStartUs = -1L
        hasAudio = false
    }
}
