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
 * 优化后的视频播放器 — 解决音画同步、杂音和声音残留问题
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
            var extractor: MediaExtractor? = null
            var aTrack: AudioTrack? = null

            try {
                extractor = MediaExtractor().apply { setDataSource(url) }
                
                var vIndex = -1
                var aIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && vIndex == -1) {
                        vIndex = i
                        extractor.selectTrack(i)
                    } else if (mime.startsWith("audio/") && aIndex == -1) {
                        aIndex = i
                        extractor.selectTrack(i)
                    }
                }

                if (vIndex == -1) {
                    if (seq == currentSeq.get()) _state.value = State.ERROR
                    return@launch
                }

                val videoFormat = extractor.getTrackFormat(vIndex)
                _duration.value = videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000

                // 配置视频
                vCodec = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
                vCodec.configure(videoFormat, surface, null, 0)
                vCodec.start()

                // 配置音频
                if (aIndex != -1) {
                    val audioFormat = extractor.getTrackFormat(aIndex)
                    val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                    
                    aTrack = AudioTrack(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                        AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelConfig).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                        minBufSize * 4, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    activeAudioTrack = aTrack
                    aTrack.play()

                    aCodec = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME)!!)
                    aCodec.configure(audioFormat, null, null, 0)
                    aCodec.start()
                }

                if (seq != currentSeq.get()) return@launch
                _state.value = State.PLAYING

                // 启动解码循环
                coroutineScope {
                    launch { videoDecodeLoop(seq, vCodec, extractor, vIndex) }
                    if (aCodec != null && aTrack != null) {
                        launch { audioDecodeLoop(seq, aCodec, extractor, aIndex, aTrack) }
                    }
                }

                if (seq == currentSeq.get()) _state.value = State.COMPLETED
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Playback error [seq=$seq]", e)
                    if (seq == currentSeq.get()) _state.value = State.ERROR
                }
            } finally {
                Log.d(TAG, "Releasing resources [seq=$seq]")
                vCodec?.safeRelease()
                aCodec?.safeRelease()
                extractor?.release()
                aTrack?.safeRelease()
            }
        }
    }

    private suspend fun videoDecodeLoop(seq: Int, codec: MediaCodec, extractor: MediaExtractor, trackIndex: Int) {
        val info = MediaCodec.BufferInfo()
        var startWallTimeUs = -1L
        var startVideoTimeUs = -1L

        while (seq == currentSeq.get()) {
            if (isPaused.get()) { delay(50); continue }

            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    synchronized(extractor) {
                        extractor.selectTrack(trackIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outputIndex >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                
                // 同步
                if (startWallTimeUs == -1L) {
                    startWallTimeUs = SystemClock.elapsedRealtimeNanos() / 1000
                    startVideoTimeUs = info.presentationTimeUs
                }
                val wallElapsed = (SystemClock.elapsedRealtimeNanos() / 1000) - startWallTimeUs
                val videoElapsed = (info.presentationTimeUs - startVideoTimeUs) / _speed.value
                val delayUs = (videoElapsed - wallElapsed).toLong()
                if (delayUs > 0) delay(delayUs / 1000)

                codec.releaseOutputBuffer(outputIndex, info.size > 0)
                _currentPosition.value = info.presentationTimeUs / 1000
            }
        }
    }

    private suspend fun audioDecodeLoop(seq: Int, codec: MediaCodec, extractor: MediaExtractor, trackIndex: Int, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        while (seq == currentSeq.get()) {
            if (isPaused.get()) { delay(50); continue }

            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    synchronized(extractor) {
                        extractor.selectTrack(trackIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && info.size > 0) {
                    val chunk = ByteArray(info.size)
                    outputBuffer.get(chunk)
                    track.write(chunk, 0, info.size)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    fun release() {
        Log.d(TAG, "Manual release")
        currentSeq.incrementAndGet()
        playerJob?.cancel()
        _state.value = State.IDLE
    }

    private fun MediaCodec.safeRelease() {
        try { stop() } catch (e: Exception) {}
        try { release() } catch (e: Exception) {}
    }

    private fun AudioTrack.safeRelease() {
        try { stop() } catch (e: Exception) {}
        try { flush() } catch (e: Exception) {}
        try { release() } catch (e: Exception) {}
    }

    private var activeAudioTrack: AudioTrack? = null

    fun play() {
        isPaused.set(false)
        try { activeAudioTrack?.play() } catch (e: Exception) {}
        if (_state.value == State.PAUSED || _state.value == State.COMPLETED) _state.value = State.PLAYING
    }

    fun pause() {
        isPaused.set(true)
        try { activeAudioTrack?.pause() } catch (e: Exception) {}
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
}
