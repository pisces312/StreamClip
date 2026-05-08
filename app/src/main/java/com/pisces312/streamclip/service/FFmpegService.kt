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
        val percent: Int = 0,
        val processedTimeMs: Long = 0,
        val totalTimeMs: Long = -1,
        val outputSizeBytes: Long = 0,
        val message: String = ""
    )

    data class LogLine(
        val text: String,
        val isError: Boolean = false
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
     * Get video duration in milliseconds using ffprobe
     * Returns -1 if failed
     */
    fun getDurationMs(inputPath: String): Long {
        return try {
            val session = FFprobeKit.execute("-v quiet -show_entries format=duration -of csv=p=0 \"$inputPath\"")
            if (!ReturnCode.isSuccess(session.returnCode)) return -1
            val output = session.output.trim()
            val seconds = output.toDoubleOrNull() ?: return -1
            (seconds * 1000).toLong()
        } catch (e: Exception) {
            LogCollector.e("FFmpegService", "Get duration failed: ${e.message}")
            -1
        }
    }

    /**
     * Execute FFmpeg command with ffmpeg-kit
     */
    suspend fun executeCommand(
        command: String,
        outputPath: String? = null,
        totalTimeMs: Long = -1,
        onProgress: ((Progress) -> Unit)? = null,
        onLog: ((LogLine) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            LogCollector.d("FFmpegService", "Executing: $command")
            val startTime = System.currentTimeMillis()

            val session = if (onProgress != null || onLog != null) {
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
                    onLog?.invoke(LogLine(log.message, false))
                }, StatisticsCallback { statistics ->
                    val time = statistics.time.toLong()
                    if (time > 0) {
                        val percent = if (totalTimeMs > 0) {
                            ((time.toDouble() / totalTimeMs) * 100).toInt().coerceIn(0, 100)
                        } else {
                            ((time / 1000.0) / 60 * 100).toInt().coerceIn(0, 100)
                        }

                        val elapsedMs = System.currentTimeMillis() - startTime
                        val estimatedRemainingMs = if (percent > 0 && percent < 100) {
                            (elapsedMs / percent.toDouble() * (100 - percent)).toLong()
                        } else {
                            -1
                        }

                        val outputSize = if (outputPath != null) {
                            try {
                                java.io.File(outputPath).length()
                            } catch (e: Exception) {
                                0L
                            }
                        } else 0L

                        onProgress?.invoke(Progress(
                            percent = percent,
                            processedTimeMs = time,
                            totalTimeMs = totalTimeMs,
                            outputSizeBytes = outputSize,
                            message = "Processing: ${time}ms"
                        ))
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
        return executeCommand(command, outputPath, onProgress = onProgress)
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

        val command = "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" -c copy -fflags +genpts -avoid_negative_ts make_zero -reset_timestamps 1 \"$outputPath\""

        val result = executeCommand(command, outputPath, onProgress = onProgress)
        concatFile.delete()
        return result
    }

    /**
     * Probe video location/GPS metadata using ffprobe
     * Returns location string like "+121.2345+031.6789/" or null
     */
    fun probeLocation(inputPath: String): String? {
        return try {
            // Get all metadata: format + streams
            val session = FFprobeKit.execute("-v quiet -show_format -show_streams \"$inputPath\"")
            if (!ReturnCode.isSuccess(session.returnCode)) return null

            val output = session.output
            // Log full metadata for debugging
            LogCollector.d("FFmpegService", "=== Full metadata for $inputPath ===")
            LogCollector.d("FFmpegService", output)
            LogCollector.d("FFmpegService", "=== End metadata ===")

            // Try multiple patterns for GPS location
            // ffprobe -show_format output format: TAG:key=value
            val patterns = listOf(
                // Standard location tag (TAG:location=value or TAG:location-eng=value)
                Regex("""TAG:location(?:-eng)?=(\S+)""", RegexOption.MULTILINE),
                // GPS coordinates in different formats
                Regex("""TAG:(?:gps|GPS)_?(?:location|position|coordinates)?=(\S+)""", RegexOption.MULTILINE),
                // com.apple.quicktime.location (MOV/MP4)
                Regex("""TAG:com\.apple\.quicktime\.location=(\S+)""", RegexOption.MULTILINE),
                // Any tag containing lat/long
                Regex("""TAG:(?:latitude|longitude|lat|long|lng)=([+-]?\d+\.?\d*)""", RegexOption.MULTILINE)
            )

            for (regex in patterns) {
                val match = regex.find(output)
                val value = match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it != "N/A" }
                if (value != null) {
                    LogCollector.d("FFmpegService", "Found location via pattern ${regex.pattern}: $value")
                    return value
                }
            }
            null
        } catch (e: Exception) {
            LogCollector.e("FFmpegService", "Probe location failed: ${e.message}")
            null
        }
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
        return executeCommand(command, outputPath, onProgress = onProgress)
    }

    /**
     * Probe video stream info from a video file using ffprobe
     */
    fun probeVideoInfo(inputPath: String): com.pisces312.streamclip.model.VideoInfo? {
        return try {
            val session = FFprobeKit.execute(
                "-v quiet -select_streams v:0 -show_entries stream=width,height,codec_name,r_frame_rate,pix_fmt -of csv=p=0 \"$inputPath\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) return null

            val output = session.output.trim()
            if (output.isEmpty()) return null

            val parts = output.split(",")
            if (parts.size < 5) return null

            val width = parts[0].trim().toIntOrNull() ?: 0
            val height = parts[1].trim().toIntOrNull() ?: 0
            val videoCodec = parts[2].trim()
            val frameRate = parts[3].trim()
            val pixelFormat = parts[4].trim()

            val audioSession = FFprobeKit.execute(
                "-v quiet -select_streams a:0 -show_entries stream=codec_name -of csv=p=0 \"$inputPath\""
            )
            val audioCodec = if (ReturnCode.isSuccess(audioSession.returnCode)) {
                audioSession.output.trim().ifEmpty { "none" }
            } else "none"

            // Probe rotation from side_data
            val rotationSession = FFprobeKit.execute(
                "-v quiet -select_streams v:0 -show_entries stream_side_data=rotation -of csv=p=0 \"$inputPath\""
            )
            val rotation = if (ReturnCode.isSuccess(rotationSession.returnCode)) {
                rotationSession.output.trim().toIntOrNull() ?: 0
            } else 0

            com.pisces312.streamclip.model.VideoInfo(
                path = inputPath,
                width = width,
                height = height,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                frameRate = frameRate,
                pixelFormat = pixelFormat,
                rotation = rotation
            )
        } catch (e: Exception) {
            LogCollector.e("FFmpegService", "Probe video info failed: ${e.message}")
            null
        }
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
