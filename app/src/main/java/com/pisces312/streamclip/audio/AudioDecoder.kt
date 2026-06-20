package com.pisces312.streamclip.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * 使用 MediaExtractor + MediaCodec 将音频文件解码为原始 PCM (16-bit) 数据。
 * 支持所有 Android 原生支持的格式：MP3, FLAC, WAV, AAC/M4A, OGG/Vorbis, Opus 等。
 *
 * 参考 Ringdroid 的 SoundFile.java，简化为纯解码，不做帧增益计算。
 */
class AudioDecoder {

    data class DecodedAudio(
        val samples: ShortBuffer,    // interleaved PCM samples: {s1c1, s1c2, s2c1, ...}
        val sampleRate: Int,
        val channels: Int,
        val numSamples: Int,         // samples per channel
        val avgBitrateKbps: Int,
        val fileType: String,
        val fileSize: Int
    )

    interface ProgressListener {
        /**
         * @param fraction 0.0 ~ 1.0
         * @return true 继续解码，false 取消
         */
        fun onProgress(fraction: Double): Boolean
    }

    companion object {
        private const val TAG = "AudioDecoder"
    }

    /**
     * 解码音频文件为 PCM ShortBuffer（保留原接口，内部调用优化版）。
     */
    @Throws(Exception::class)
    fun decode(
        filePath: String,
        listener: ProgressListener? = null
    ): DecodedAudio {
        return decodeOptimized(filePath, listener)
    }

    /**
     * Optimized decode (Mode C):
     * - Larger dequeue timeout (10ms vs 100μs) to reduce CPU spin
     * - Single pre-allocated buffer with 1.1x safety margin (no realloc copy in normal case)
     * - Throttled progress callback (every ~512KB, not every chunk)
     * - Early exit when expected samples reached
     */
    @Throws(Exception::class)
    fun decodeOptimized(
        filePath: String,
        listener: ProgressListener? = null
    ): DecodedAudio {
        val inputFile = java.io.File(filePath)
        val fileSize = inputFile.length().toInt()
        val fileType = filePath.substringAfterLast('.', "").lowercase()

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
        val expectedNumSamples = ((durationUs / 1_000_000.0) * sampleRate + 0.5).toInt()

        extractor.selectTrack(trackIndex)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        // Pre-allocate buffer once with 1.1x safety margin, capped at 512MB
        val expectedBytes = if (expectedNumSamples > 0) {
            ((expectedNumSamples.toLong() * channels * 2 * 1.1).toLong().coerceAtMost(512L shl 20)).toInt()
        } else {
            1 shl 20  // 1MB fallback
        }
        var decodedBytes = ByteBuffer.allocate(expectedBytes)
        var decodedSamples = ByteArray(65536)
        var totalSizeRead = 0
        var doneReading = false
        val info = MediaCodec.BufferInfo()
        var firstSampleData = true

        // Progress throttling: callback every ~512KB read
        var lastProgressBytes = 0
        val progressInterval = 512 * 1024

        while (true) {
            // Use 10ms timeout instead of 100μs to reduce CPU spin
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (!doneReading && inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)

                if (firstSampleData && mime == "audio/mp4a-latm" && sampleSize == 2) {
                    // 某些设备 AAC 需要跳过前 2 字节
                    extractor.advance()
                    totalSizeRead += sampleSize
                } else if (sampleSize < 0) {
                    codec.queueInputBuffer(
                        inputBufferIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    doneReading = true
                } else {
                    val presentationTime = extractor.sampleTime
                    codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTime, 0)
                    extractor.advance()
                    totalSizeRead += sampleSize

                    // Throttled progress callback
                    if (listener != null && totalSizeRead - lastProgressBytes >= progressInterval) {
                        lastProgressBytes = totalSizeRead
                        val fraction = if (fileSize > 0) totalSizeRead.toDouble() / fileSize else 0.0
                        if (!listener.onProgress(fraction)) {
                            extractor.release()
                            codec.stop()
                            codec.release()
                            throw InterruptedException("Decoding cancelled by user")
                        }
                    }
                }
                firstSampleData = false
            }

            // Use 10ms timeout for output too
            val outputBufferIndex = codec.dequeueOutputBuffer(info, 10000)
            if (outputBufferIndex >= 0 && info.size > 0) {
                if (decodedSamples.size < info.size) {
                    decodedSamples = ByteArray(info.size)
                }
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                outputBuffer.get(decodedSamples, 0, info.size)
                outputBuffer.clear()

                // Check capacity before put (no realloc needed in normal case)
                if (decodedBytes.remaining() < info.size) {
                    // Rare case: expand with minimal copy
                    val position = decodedBytes.position()
                    val newSize = (position + info.size + 5 * (1 shl 20)).toLong().coerceAtMost(512L shl 20).toInt()
                    var newBytes: ByteBuffer? = null
                    var retry = 10
                    while (retry > 0) {
                        try {
                            newBytes = ByteBuffer.allocate(newSize)
                            break
                        } catch (e: OutOfMemoryError) {
                            retry--
                        }
                    }
                    if (newBytes == null) {
                        Log.w(TAG, "OOM: failed to expand buffer, truncating decode")
                        break
                    }
                    decodedBytes.rewind()
                    newBytes.put(decodedBytes)
                    decodedBytes = newBytes
                    decodedBytes.position(position)
                }
                decodedBytes.put(decodedSamples, 0, info.size)
                codec.releaseOutputBuffer(outputBufferIndex, false)
            }

            // End conditions
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break
            }
            if (expectedNumSamples > 0 && decodedBytes.position() / (2 * channels) >= expectedNumSamples) {
                break
            }
        }

        // Final progress callback
        listener?.onProgress(1.0)

        val numSamples = decodedBytes.position() / (channels * 2)
        decodedBytes.rewind()
        decodedBytes.order(ByteOrder.LITTLE_ENDIAN)
        val samples = decodedBytes.asShortBuffer()

        val avgBitrate = if (numSamples > 0) {
            ((fileSize * 8) * (sampleRate.toDouble() / numSamples) / 1000).toInt()
        } else 0

        extractor.release()
        codec.stop()
        codec.release()

        Log.i(TAG, "Decoded(optimized): $filePath | type=$fileType | ${sampleRate}Hz | ${channels}ch | ${numSamples} samples | ~${avgBitrate}kbps")

        return DecodedAudio(
            samples = samples,
            sampleRate = sampleRate,
            channels = channels,
            numSamples = numSamples,
            avgBitrateKbps = avgBitrate,
            fileType = fileType,
            fileSize = fileSize
        )
    }
}
