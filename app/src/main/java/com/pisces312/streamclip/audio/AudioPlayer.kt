package com.pisces312.streamclip.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.nio.ShortBuffer
import com.pisces312.streamclip.audio.AudioDecoder.DecodedAudio

/**
 * 使用 AudioTrack 播放 PCM 采样数据。
 * 参考 Ringdroid 的 SamplePlayer.java，支持选区播放、暂停、跳转。
 */
class AudioPlayer(
    private val samples: ShortBuffer,
    private val sampleRate: Int,
    private val channels: Int,
    private val numSamples: Int  // per channel
) {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack
    private var buffer: ShortArray
    private var playbackStart = 0  // in samples (per channel)
    private var playThread: Thread? = null
    @Volatile private var keepPlaying = true

    // Looping support (Change 3)
    private var isLooping = false
    private var loopStartSample = 0
    private var loopEndSample = 0

    init {
        val channelConfig = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        var bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        // 至少容纳 1 秒音频
        if (bufferSize < channels * sampleRate * 2) {
            bufferSize = channels * sampleRate * 2
        }
        buffer = ShortArray(bufferSize / 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // marker 仅用于循环模式下的循环重置；非循环下由 playThread 写完自然结束
        audioTrack.setNotificationMarkerPosition(numSamples - 1)
        audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onPeriodicNotification(track: AudioTrack?) {}
            override fun onMarkerReached(track: AudioTrack?) {
                if (isLooping) {
                    stop()
                    playbackStart = loopStartSample
                    audioTrack.setNotificationMarkerPosition(loopEndSample - 1 - loopStartSample)
                    start()
                }
                // 非循环：playThread 已经写完自然结束，由调用方用 playbackCompleteRunnable 复位 UI
            }
        })
    }

    constructor(decoded: AudioDecoder.DecodedAudio) : this(
        decoded.samples, decoded.sampleRate, decoded.channels, decoded.numSamples
    )

    fun isPlaying(): Boolean = audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
    fun isPaused(): Boolean = audioTrack.playState == AudioTrack.PLAYSTATE_PAUSED

    /**
     * 启动播放。三种状态分别处理：
     * - 已在 PLAYING：忽略
     * - 已在 PAUSED：直接 audioTrack.play() 恢复（不 flush，保留内部 buffer，无卡顿）
     * - STOPPED 或首次：flush + play + 启动新 playThread 从 playbackStart 读取样本
     */
    fun start() {
        if (isPlaying()) return
        if (isPaused()) {
            audioTrack.play()
            return
        }
        keepPlaying = true
        audioTrack.flush()
        audioTrack.play()

        playThread = Thread {
            val position = playbackStart * channels
            samples.position(position)
            // When looping, stop at loopEndSample; otherwise play to end
            // But if setPlaybackRange() was called (loopEndSample > playbackStart),
            // honor that as the upper bound even when not looping.
            val endSample = when {
                isLooping -> loopEndSample
                loopEndSample > playbackStart -> loopEndSample
                else -> numSamples
            }
            val limit = endSample * channels
            var naturalEnd = false
            while (samples.position() < limit && keepPlaying) {
                val remaining = limit - samples.position()
                val toWrite = if (remaining >= buffer.size) buffer.size else remaining
                if (remaining >= buffer.size) {
                    samples.get(buffer)
                } else {
                    for (i in remaining until buffer.size) buffer[i] = 0
                    samples.get(buffer, 0, remaining)
                }
                audioTrack.write(buffer, 0, toWrite)
            }
            // 写完：不要主动 pause/stop，让 audioTrack 内部 buffer 自然排空（否则会提前静音）
            if (samples.position() >= limit && keepPlaying) {
                naturalEnd = true
                keepPlaying = false
            }
            // 播放结束通知统一由 Activity 的 playbackCompleteRunnable 处理（无需 listener）
        }.also { it.start() }
    }

    fun pause() {
        if (isPlaying()) {
            audioTrack.pause()
        }
    }

    fun stop() {
        if (isPlaying() || isPaused()) {
            keepPlaying = false
            audioTrack.pause()
            try { audioTrack.stop() } catch (_: IllegalStateException) {}
            playThread?.let {
                try { it.join() } catch (_: InterruptedException) {}
                playThread = null
            }
            audioTrack.flush()
        }
    }

    fun release() {
        stop()
        audioTrack.release()
    }

    /**
     * 跳转到指定毫秒位置
     */
    fun seekTo(msec: Int) {
        val wasPlaying = isPlaying()
        stop()
        playbackStart = (msec * (sampleRate / 1000.0)).toInt().coerceAtMost(numSamples)
        audioTrack.setNotificationMarkerPosition(numSamples - 1 - playbackStart)
        if (wasPlaying) start()
    }

    /**
     * 设置播放起止范围（毫秒）。内部 stop 后更新字段，若原本在播则重新 start。
     * 调用方负责调度 UI 复位（playbackCompleteRunnable）。
     */
    fun setPlaybackRange(startMsec: Int, endMsec: Int) {
        val wasPlaying = isPlaying()
        stop()
        playbackStart = (startMsec * (sampleRate / 1000.0)).toInt().coerceAtMost(numSamples)
        val endSample = (endMsec * (sampleRate / 1000.0)).toInt().coerceAtMost(numSamples)
        loopStartSample = playbackStart
        loopEndSample = endSample
        audioTrack.setNotificationMarkerPosition(loopEndSample - 1 - loopStartSample)
        if (wasPlaying) start()
    }

    fun setLooping(looping: Boolean) {
        isLooping = looping
    }

    /**
     * 获取当前播放位置（毫秒）
     */
    fun getCurrentPosition(): Int {
        return ((playbackStart + audioTrack.playbackHeadPosition) * (1000.0 / sampleRate)).toInt()
    }
}
