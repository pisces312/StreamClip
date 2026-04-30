package com.pisces312.streamclip.service

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import com.pisces312.streamclip.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object FFmpegService {

    data class Result(
        val success: Boolean,
        val outputPath: String? = null,
        val error: String? = null
    )

    data class Progress(
        val percent: Int,
        val message: String
    )

    /**
     * Audio stream info from ffprobe
     */
    data class AudioInfo(
        val codecName: String,
        val sampleRate: String,
        val channelLayout: String,
        val extension: String
    )

    /**
     * Execute FFmpeg command with ffmpeg-kit
     */
    suspend fun executeCommand(
        command: String,
        outputPath: String? = null,
        onProgress: ((Progress) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            LogCollector.d("FFmpegService", "Executing: $command")

            val session = if (onProgress != null) {
                FFmpegKit.executeAsync(command, { session ->
                    val returnCode = session.returnCode
                    val success = ReturnCode.isSuccess(returnCode)
                    val error = if (success) null else (session.failStackTrace ?: session.output ?: "Unknown error")

                    LogCollector.d("FFmpegService", "Completed: success=$success, code=$returnCode, error=$error")

                    continuation.resume(
                        Result(
                            success = success,
                            outputPath = outputPath,
                            error = error
                        )
                    )
                }, { log ->
                    LogCollector.d("FFmpegService", log.message)
                }, StatisticsCallback { statistics ->
                    val time = statistics.time
                    if (time > 0) {
                        val percent = ((time / 1000.0) / 60 * 100).toInt().coerceIn(0, 100)
                        onProgress(Progress(percent, "Processing: ${time}ms"))
                    }
                })
            } else {
                FFmpegKit.executeAsync(command, { session ->
                    val returnCode = session.returnCode
                    val success = ReturnCode.isSuccess(returnCode)
                    val error = if (success) null else (session.failStackTrace ?: session.output ?: "Unknown error")

                    LogCollector.d("FFmpegService", "Completed: success=$success, code=$returnCode, error=$error")

                    continuation.resume(
                        Result(
                            success = success,
                            outputPath = outputPath,
                            error = error
                        )
                    )
                })
            }

            continuation.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
            }
        }
    }

    /**
     * Trim video without re-encoding (-c copy)
     */
    suspend fun trimVideo(
        context: Context,
        inputPath: String,
        outputPath: String,
        startSec: Double,
        durationSec: Double,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        File(outputPath).parentFile?.mkdirs()

        val command = buildString {
            append("-y ")
            append("-i ")
            append("\"$inputPath\" ")
            append("-ss $startSec ")
            append("-t $durationSec ")
            append("-c copy ")
            append("-avoid_negative_ts make_zero ")
            append("-fflags +genpts ")
            append("\"$outputPath\"")
        }

        LogCollector.d("FFmpegService", "Command: $command")
        return executeCommand(command, outputPath, onProgress)
    }

    /**
     * Merge videos using concat demuxer (lossless, no re-encode)
     */
    suspend fun mergeVideos(
        context: Context,
        inputPaths: List<String>,
        outputPath: String,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        if (inputPaths.size < 2) {
            return Result(false, error = "至少需要2个视频")
        }

        val concatFile = File.createTempFile("concat_list", ".txt", context.cacheDir)
        concatFile.writeText(inputPaths.joinToString("\n") { "file '${it.replace("'", "'\\''")}'" })

        val command = "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" -c copy -fflags +genpts -avoid_negative_ts make_zero \"$outputPath\""

        val result = executeCommand(command, outputPath, onProgress)
        concatFile.delete()
        return result
    }

    /**
     * Extract audio from video (lossless, -c:a copy)
     */
    suspend fun extractAudio(
        context: Context,
        inputPath: String,
        outputPath: String,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        File(outputPath).parentFile?.mkdirs()

        val command = "-y -i \"$inputPath\" -vn -c:a copy \"$outputPath\""
        LogCollector.d("FFmpegService", "Command: $command")
        return executeCommand(command, outputPath, onProgress)
    }

    /**
     * Probe audio stream info from a video file using ffprobe
     */
    fun probeAudioInfo(inputPath: String): AudioInfo? {
        return try {
            val session = FFprobeKit.execute("-v quiet -select_streams a:0 -show_entries stream=codec_name,sample_rate,channel_layout -of csv=p=0 \"$inputPath\"")
            if (!ReturnCode.isSuccess(session.returnCode)) return null

            val output = session.output.trim()
            if (output.isEmpty()) return null

            // Output format: codec_name,sample_rate,channel_layout
            val parts = output.split(",")
            if (parts.isEmpty()) return null

            val codecName = parts.getOrNull(0)?.trim() ?: "unknown"
            val sampleRate = parts.getOrNull(1)?.trim() ?: "unknown"
            val channelLayout = parts.getOrNull(2)?.trim() ?: "unknown"

            // Map codec name to file extension
            val extension = codecToExtension(codecName)

            AudioInfo(
                codecName = codecName,
                sampleRate = sampleRate,
                channelLayout = channelLayout,
                extension = extension
            )
        } catch (e: Exception) {
            LogCollector.e("FFmpegService", "Probe failed: ${e.message}")
            null
        }
    }

    /**
     * Map audio codec name to file extension
     */
    private fun codecToExtension(codec: String): String {
        return when (codec.lowercase()) {
            "aac" -> "aac"
            "mp3float", "mp3" -> "mp3"
            "flac" -> "flac"
            "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le" -> "wav"
            "opus" -> "opus"
            "vorbis" -> "ogg"
            "ac3" -> "ac3"
            "eac3" -> "eac3"
            "dts" -> "dts"
            "truehd" -> "thd"
            "alac" -> "m4a"
            "wmav2", "wmapro" -> "wma"
            else -> "audio"
        }
    }
}
