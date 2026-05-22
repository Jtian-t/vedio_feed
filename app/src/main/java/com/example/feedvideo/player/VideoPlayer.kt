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
 * 设计要点：
 * - 每次 prepareAndPlay 递增 seq，旧任务通过 seq != currentSeq 自行退出
 * - 不使用 runBlocking，所有协程在 Dispatchers.Default 上运行
 * - 资源释放放在每个任务自己的 finally 块中
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

    // 序列号：每次 prepareAndPlay +1，decode loop 用 seq == currentSeq 判断是否继续
    @Volatile
    private var currentSeq = 0

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isPaused = AtomicBoolean(false)

    // 音视频同步
    @Volatile
    private var mediaStartUs = -1L
    @Volatile
    private var wallStartUs = -1L

    fun prepareAndPlay(url: String, surface: Surface) {
        Log.d(TAG, "prepareAndPlay: $url")

        // 让旧任务自行退出
        currentSeq++
        val seq = currentSeq
        job?.cancel()

        isPaused.set(false)
        _state.value = State.PREPARING

        job = scope.launch {
            // 所有资源都是局部变量，finally 负责释放
            var vExt: MediaExtractor? = null
            var aExt: MediaExtractor? = null
            var vCodec: MediaCodec? = null
            var aCodec: MediaCodec? = null
            var aTrack: AudioTrack? = null

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
                        val buf = AudioTrack.getMinBufferSize(sr, cfg, AudioFormat.ENCODING_PCM_16BIT)
                        aTrack = AudioTrack(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                            AudioFormat.Builder()
                                .setSampleRate(sr).setChannelMask(cfg)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                            buf * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
                        )
                        aTrack?.play()
                        aCodec = MediaCodec.createDecoderByType(aMime).also {
                            it.configure(aFmt, null, null, 0)
                            it.start()
                        }
                        Log.d(TAG, "Audio: $aMime ${sr}Hz ${ch}ch")
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio setup failed", e)
                    }
                }

                if (!isActive || seq != currentSeq) return@launch
                _state.value = State.PLAYING
                Log.d(TAG, "=== Playback started (seq=$seq) ===")

                // === 4. 并行解码 ===
                val vj = launch { decodeVideo(vExt!!, vCodec!!, seq) }
                val aj = if (aExt != null && aCodec != null && aTrack != null) {
                    launch { decodeAudio(aExt!!, aCodec!!, aTrack!!, seq) }
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
                releaseRes(vCodec, aCodec, vExt, aExt, aTrack)
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

                        if (!firstDone && info.size > 0) {
                            // 首帧立即渲染
                            codec.releaseOutputBuffer(idx, true)
                            firstDone = true
                            mediaStartUs = info.presentationTimeUs
                            wallStartUs = System.nanoTime() / 1000
                            _currentPosition.value = info.presentationTimeUs / 1000
                            Log.d(TAG, "First frame ${info.presentationTimeUs / 1000}ms")
                            continue
                        }

                        val render = info.size > 0 && checkPts(info.presentationTimeUs)
                        codec.releaseOutputBuffer(idx, render)
                        if (info.size > 0) _currentPosition.value = info.presentationTimeUs / 1000
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

    // ==================== 音频解码 ====================

    private fun decodeAudio(ext: MediaExtractor, codec: MediaCodec, track: AudioTrack, seq: Int) {
        val info = MediaCodec.BufferInfo()
        var inEos = false
        var outEos = false

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
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(pcm)
                            track.write(pcm, 0, info.size)
                        }
                        codec.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Thread.sleep(2)
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        Log.d(TAG, "Afmt: ${codec.outputFormat}")
                }
            } catch (e: Exception) {
                if (seq == currentSeq) Log.e(TAG, "Audio output error", e)
                break
            }
        }
        Log.d(TAG, "Audio exit seq=$seq")
    }

    // ==================== 同步 ====================

    private fun checkPts(ptsUs: Long): Boolean {
        val wall = wallStartUs
        if (wall < 0L) return true
        val now = System.nanoTime() / 1000
        return (ptsUs - mediaStartUs) <= (now - wall) + 30_000
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
    }

    private fun releaseRes(
        vc: MediaCodec?, ac: MediaCodec?,
        ve: MediaExtractor?, ae: MediaExtractor?,
        at: AudioTrack?
    ) {
        try { vc?.stop(); vc?.release() } catch (_: Exception) {}
        try { ac?.stop(); ac?.release() } catch (_: Exception) {}
        try { ve?.release() } catch (_: Exception) {}
        try { ae?.release() } catch (_: Exception) {}
        try { at?.stop(); at?.release() } catch (_: Exception) {}
    }
}
