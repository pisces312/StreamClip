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

    interface OnCompletionListener {
        fun onCompletion()
    }

    private var audioTrack: AudioTrack
    private var buffer: ShortArray
    private var playbackStart = 0  // in samples (per channel)
    private var playThread: Thread? = null
    @Volatile private var keepPlaying = true
    private var listener: OnCompletionListener? = null

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

        audioTrack.setNotificationMarkerPosition(numSamples - 1)
        audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onPeriodicNotification(track: AudioTrack?) {}
            override fun onMarkerReached(track: AudioTrack?) {
                if (isLooping) {
                    stop()
                    playbackStart = loopStartSample
                    audioTrack.setNotificationMarkerPosition(loopEndSample - 1 - loopStartSample)
                    start()
                } else {
                    stop()
                    listener?.onCompletion()
                }
            }
        })
    }

    constructor(decoded: AudioDecoder.DecodedAudio) : this(
        decoded.samples, decoded.sampleRate, decoded.channels, decoded.numSamples
    )

    fun setOnCompletionListener(l: OnCompletionListener) {
        listener = l
    }

    fun isPlaying(): Boolean = audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
    fun isPaused(): Boolean = audioTrack.playState == AudioTrack.PLAYSTATE_PAUSED

    fun start() {
        if (isPlaying()) return
        keepPlaying = true
        audioTrack.flush()
        audioTrack.play()

        playThread = Thread {
            val position = playbackStart * channels
            samples.position(position)
            val limit = numSamples * channels
            while (samples.position() < limit && keepPlaying) {
                val remaining = limit - samples.position()
                if (remaining >= buffer.size) {
                    samples.get(buffer)
                } else {
                    for (i in remaining until buffer.size) buffer[i] = 0
                    samples.get(buffer, 0, remaining)
                }
                audioTrack.write(buffer, 0, buffer.size)
            }
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
     * 设置播放起止范围（用于选区播放）
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
