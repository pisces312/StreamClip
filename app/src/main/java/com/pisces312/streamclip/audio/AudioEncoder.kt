package com.pisces312.streamclip.audio

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import java.nio.ShortBuffer

/**
 * 使用 FFmpeg (ffmpeg-kit) 将 PCM 数据编码为目标格式。
 * 支持 MP3, FLAC, AAC/M4A, WAV, Opus 等。
 *
 * 工作流程：
 * 1. 将选区内的 PCM samples 写入临时 WAV 文件
 * 2. 调用 FFmpeg 将 WAV 转换为目标格式
 */
class AudioEncoder {

    enum class OutputFormat(val extension: String, val mimeType: String, val displayName: String) {
        MP3("mp3", "audio/mpeg", "MP3"),
        FLAC("flac", "audio/flac", "FLAC"),
        M4A("m4a", "audio/mp4", "M4A (AAC)"),
        WAV("wav", "audio/wav", "WAV"),
        OPUS("opus", "audio/ogg", "Opus");

        companion object {
            fun fromExtension(ext: String): OutputFormat? =
                entries.find { it.extension.equals(ext, ignoreCase = true) }
        }
    }

    data class EncodeConfig(
        val format: OutputFormat,
        val bitrate: Int = 192000,       // for lossy formats
        val sampleRate: Int = 0,         // 0 = keep original
        val channels: Int = 0,           // 0 = keep original
        val fadeInSec: Float = 0f,
        val fadeOutSec: Float = 0f
    )

    data class EncodeResult(
        val success: Boolean,
        val outputPath: String? = null,
        val errorMessage: String? = null,
        val durationMs: Long = 0
    )

    interface ProgressListener {
        fun onProgress(fraction: Double): Boolean
    }

    companion object {
        private const val TAG = "AudioEncoder"
    }

    private var currentSession: FFmpegSession? = null

    /**
     * 编码选区内的 PCM 数据为目标格式文件。
     *
     * @param samples   解码后的 PCM 数据
     * @param sampleRate 原始采样率
     * @param channels   原始声道数
     * @param startTimeSec 选区起点（秒）
     * @param endTimeSec   选区终点（秒）
     * @param outputPath 输出文件路径
     * @param config     编码配置
     */
    fun encode(
        samples: ShortBuffer,
        sampleRate: Int,
        channels: Int,
        numSamples: Int,
        startTimeSec: Float,
        endTimeSec: Float,
        outputPath: String,
        config: EncodeConfig,
        listener: ProgressListener? = null
    ): EncodeResult {
        val startTime = System.currentTimeMillis()

        // 1. 提取选区 PCM，写入临时 WAV
        val tempWav = createTempWavFile()
        val startOffset = (startTimeSec * sampleRate).toInt()
        val endOffset = (endTimeSec * sampleRate).toInt().coerceAtMost(numSamples)
        val selSamples = endOffset - startOffset

        if (selSamples <= 0) {
            return EncodeResult(false, errorMessage = "Invalid selection range")
        }

        writePcmToWav(
            tempWav, samples, sampleRate, channels,
            startOffset, selSamples
        )

        // 2. 构建 FFmpeg 命令
        val selectionDurationSec = endTimeSec - startTimeSec
        val cmd = buildFFmpegCommand(tempWav.absolutePath, outputPath, config, sampleRate, channels, selectionDurationSec)

        Log.i(TAG, "FFmpeg command: ffmpeg $cmd")

        // 3. 执行 FFmpeg
        val session = FFmpegKit.execute(cmd)
        currentSession = session

        // 4. 清理临时文件
        tempWav.delete()

        val returnCode = session.returnCode
        val durationMs = System.currentTimeMillis() - startTime

        return if (ReturnCode.isSuccess(returnCode)) {
            Log.i(TAG, "Encode success: $outputPath (${durationMs}ms)")
            EncodeResult(true, outputPath, durationMs = durationMs)
        } else {
            val errorMsg = session.allLogsAsString ?: "FFmpeg failed with code ${returnCode.value}"
            Log.e(TAG, "Encode failed: $errorMsg")
            EncodeResult(false, errorMessage = errorMsg, durationMs = durationMs)
        }
    }

    /**
     * 取消编码
     */
    fun cancel() {
        currentSession?.let {
            if (it.state == SessionState.RUNNING) {
                FFmpegKit.cancel(it.sessionId)
            }
        }
    }

    private fun buildFFmpegCommand(
        inputPath: String,
        outputPath: String,
        config: EncodeConfig,
        srcSampleRate: Int,
        srcChannels: Int,
        selectionDurationSec: Float = 0f
    ): String {
        val sb = StringBuilder("-y -i \"$inputPath\"")

        // 淡入淡出 afade filter
        if (config.fadeInSec > 0f || config.fadeOutSec > 0f) {
            val filters = mutableListOf<String>()
            if (config.fadeInSec > 0f) {
                filters.add("afade=t=in:st=0:d=${config.fadeInSec}")
            }
            if (config.fadeOutSec > 0f && selectionDurationSec > config.fadeOutSec) {
                val fadeOutStart = selectionDurationSec - config.fadeOutSec
                filters.add("afade=t=out:st=${"%.3f".format(fadeOutStart)}:d=${config.fadeOutSec}")
            }
            if (filters.isNotEmpty()) {
                sb.append(" -af \"${filters.joinToString(",")}\"")
            }
        }

        // 采样率
        val targetSampleRate = if (config.sampleRate > 0) config.sampleRate else srcSampleRate
        sb.append(" -ar $targetSampleRate")

        // 声道
        val targetChannels = if (config.channels > 0) config.channels else srcChannels
        sb.append(" -ac $targetChannels")

        // 编码器 + 比特率
        when (config.format) {
            OutputFormat.MP3 -> {
                sb.append(" -c:a libmp3lame -b:a ${config.bitrate}")
            }
            OutputFormat.FLAC -> {
                sb.append(" -c:a flac")
            }
            OutputFormat.M4A -> {
                sb.append(" -c:a aac -b:a ${config.bitrate}")
            }
            OutputFormat.WAV -> {
                sb.append(" -c:a pcm_s16le")
            }
            OutputFormat.OPUS -> {
                sb.append(" -c:a libopus -b:a ${config.bitrate}")
            }
        }

        sb.append(" \"$outputPath\"")
        return sb.toString()
    }

    /**
     * 将 PCM 选区数据写入 WAV 文件
     */
    private fun writePcmToWav(
        file: java.io.File,
        samples: ShortBuffer,
        sampleRate: Int,
        channels: Int,
        startOffset: Int,
        numSamples: Int
    ) {
        val bytesPerSample = 2
        val dataSize = numSamples * channels * bytesPerSample
        val totalSize = 44 + dataSize  // WAV header = 44 bytes

        val buffer = ByteArray(dataSize)
        val position = startOffset * channels
        samples.position(position)

        // 将 ShortBuffer 转为 little-endian byte array
        for (i in 0 until numSamples * channels) {
            if (samples.remaining() > 0) {
                val s = samples.get()
                buffer[i * 2] = (s.toInt() and 0xFF).toByte()
                buffer[i * 2 + 1] = (s.toInt() shr 8 and 0xFF).toByte()
            } else {
                buffer[i * 2] = 0
                buffer[i * 2 + 1] = 0
            }
        }
        samples.rewind()

        java.io.FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            writeIntLE(fos, totalSize - 8)
            fos.write("WAVE".toByteArray())

            // fmt chunk
            fos.write("fmt ".toByteArray())
            writeIntLE(fos, 16)           // chunk size
            writeShortLE(fos, 1)          // PCM = 1
            writeShortLE(fos, channels)
            writeIntLE(fos, sampleRate)
            writeIntLE(fos, sampleRate * channels * bytesPerSample)  // byte rate
            writeShortLE(fos, channels * bytesPerSample)             // block align
            writeShortLE(fos, 16)         // bits per sample

            // data chunk
            fos.write("data".toByteArray())
            writeIntLE(fos, dataSize)
            fos.write(buffer)
        }
    }

    private fun writeIntLE(os: java.io.OutputStream, value: Int) {
        os.write(value and 0xFF)
        os.write((value shr 8) and 0xFF)
        os.write((value shr 16) and 0xFF)
        os.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(os: java.io.OutputStream, value: Int) {
        os.write(value and 0xFF)
        os.write((value shr 8) and 0xFF)
    }

    private fun createTempWavFile(): java.io.File {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "waveforge")
        tempDir.mkdirs()
        return java.io.File(tempDir, "pcm_${System.currentTimeMillis()}.wav")
    }
}
