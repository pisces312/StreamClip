package com.pisces312.streamclip.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder

/**
 * Mode B: Fast preview waveform loader.
 *
 * Seeks through the file at 1-second intervals, decoding only a few frames
 * at each seek point to get peak amplitude. This gives ~1 frame/sec resolution
 * which is enough for a preview waveform — a 4-minute file = 240 seek points
 * instead of 4800 (at 50ms interval).
 *
 * For a 4-min file: ~240 seeks vs ~2.5M samples full decode → 10-50x faster.
 */
class FastWaveformLoader {

    companion object {
        private const val TAG = "FastWaveformLoader"
        private const val FRAME_INTERVAL_MS = 1000   // 1 second per waveform frame
        private const val DECODE_FRAMES_PER_POINT = 2 // decode 2 frames at each seek point
    }

    data class PreviewResult(
        val frameGains: IntArray,
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Int,
        val numFrames: Int
    )

    interface ProgressListener {
        fun onProgress(fraction: Double)
    }

    fun loadPreview(
        filePath: String,
        listener: ProgressListener? = null
    ): PreviewResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)

        var format: MediaFormat? = null
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                format = f
                trackIndex = i
                break
            }
        }

        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("No audio track found in $filePath")
        }

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        val durationMs = (durationUs / 1000).toInt()

        if (durationUs <= 0) {
            extractor.release()
            throw IllegalStateException("Cannot determine audio duration")
        }

        extractor.selectTrack(trackIndex)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val numFrames = (durationMs / FRAME_INTERVAL_MS).toInt().coerceAtLeast(1)
        val seekIntervalUs = (FRAME_INTERVAL_MS * 1000).toLong()
        val frameGains = IntArray(numFrames)
        val info = MediaCodec.BufferInfo()

        for (frameIdx in 0 until numFrames) {
            val seekTimeUs = frameIdx * seekIntervalUs

            codec.flush()
            extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            var framesDecoded = 0
            var maxAmplitude = 0
            var doneReading = false
            var eosSent = false

            while (framesDecoded < DECODE_FRAMES_PER_POINT && !doneReading) {
                if (!eosSent) {
                    val inputBufferIndex = codec.dequeueInputBuffer(5000)
                    if (inputBufferIndex >= 0) {
                        val codecInputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(codecInputBuffer, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, -1,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosSent = true
                        } else {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(info, 5000)
                if (outputBufferIndex >= 0) {
                    if (info.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        while (shortBuffer.remaining() > 0) {
                            val sample = Math.abs(shortBuffer.get().toInt())
                            if (sample > maxAmplitude) maxAmplitude = sample
                        }
                        framesDecoded++
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        doneReading = true
                    }
                }
            }

            frameGains[frameIdx] = Math.sqrt(maxAmplitude.toDouble()).toInt()
            listener?.onProgress((frameIdx + 1).toDouble() / numFrames)
        }

        codec.stop()
        codec.release()
        extractor.release()

        Log.i(TAG, "FastPreview: $filePath | ${sampleRate}Hz | ${channels}ch | ${durationMs}ms | ${numFrames} frames (1fps)")

        return PreviewResult(
            frameGains = frameGains,
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs,
            numFrames = numFrames
        )
    }
}
