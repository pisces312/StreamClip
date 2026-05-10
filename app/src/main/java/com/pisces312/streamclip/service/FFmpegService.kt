package com.pisces312.streamclip.service

import android.content.Context
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.StatisticsCallback
import com.pisces312.streamclip.util.LogCollector
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object FFmpegService {

    @Volatile
    private var currentSessionId: Long = -1

    fun cancelCurrentSession() {
        val id = currentSessionId
        if (id != -1L) {
            FFmpegKit.cancel(id)
            currentSessionId = -1
            LogCollector.d("FFmpegService", "Cancelled session $id")
        }
    }

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
                    currentSessionId = -1
                    val returnCode = session.returnCode
                    val success = ReturnCode.isSuccess(returnCode)
                    val error = if (success) null else (session.output.takeIf { it.isNotEmpty() } ?: "Unknown error")

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
                        // 只有在有总时长时才计算有效百分比，否则返回 -1 表示未知
                        val percent = if (totalTimeMs > 0) {
                            ((time.toDouble() / totalTimeMs) * 100).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }

                        val elapsedMs = System.currentTimeMillis() - startTime
                        // 预估剩余时间：只有百分比有效时才计算
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
                    currentSessionId = -1
                    val returnCode = session.returnCode
                    val success = ReturnCode.isSuccess(returnCode)
                    val error = if (success) null else (session.output.takeIf { it.isNotEmpty() } ?: "Unknown error")

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

            currentSessionId = session.sessionId

            continuation.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
                currentSessionId = -1
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
            append("-map_metadata 0 ")
            append("-ss $startSec ")
            append("-t $durationSec ")
            append("-c copy ")
            append("-avoid_negative_ts make_zero ")
            append("-fflags +genpts ")
            append("-f mov ")
            append("\"$outputPath\"")
        }

        LogCollector.d("FFmpegService", "Command: $command")
        return executeCommand(command, outputPath, onProgress = onProgress)
    }

    /**
     * Extract format-level metadata tags to a sidecar file for later application.
     * Uses ffprobe to dump tags, then writes KEY=VALUE lines.
     */
    private fun extractMetadataToFile(inputPath: String, metadataFile: File): Boolean {
        return try {
            val session = FFprobeKit.execute("-v quiet -show_format \"$inputPath\"")
            if (!ReturnCode.isSuccess(session.returnCode)) return false

            val tags = mutableListOf<String>()
            var inTags = false
            for (line in session.output.lines()) {
                if (line == "[FORMAT_TAGS]") {
                    inTags = true
                    continue
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inTags = false
                    continue
                }
                if (inTags && line.contains('=')) {
                    tags.add(line)
                }
            }

            if (tags.isEmpty()) return false
            metadataFile.writeText(tags.joinToString("\n"))
            true
        } catch (e: Exception) {
            LogCollector.e("FFmpegService", "Extract metadata failed: ${e.message}")
            false
        }
    }

    /**
     * Merge videos using concat demuxer (lossless, no re-encode).
     * Preserves metadata (GPS etc.) from the first video.
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

        // Apply metadata from first video to merged output
        if (result.success) {
            val metadataFile = File.createTempFile("metadata", ".txt", context.cacheDir)
            try {
                if (extractMetadataToFile(inputPaths[0], metadataFile)) {
                    val metadataCmd = "-y -i \"$outputPath\" -map_metadata 0 -i \"${metadataFile.absolutePath}\" -map_metadata 1 -c copy -f mov \"$outputPath.tmp\""
                    val metadataResult = executeCommand(metadataCmd, "$outputPath.tmp")
                    if (metadataResult.success) {
                        java.io.File("$outputPath.tmp").renameTo(java.io.File(outputPath))
                    }
                }
            } catch (e: Exception) {
                LogCollector.e("FFmpegService", "Apply metadata failed: ${e.message}")
            } finally {
                metadataFile.delete()
            }
        }

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
            // Video stream: use JSON for reliable field extraction
            val session = FFprobeKit.execute(
                "-v quiet -select_streams v:0 -show_entries stream=width,height,codec_name,r_frame_rate,pix_fmt,bit_rate,color_primaries,color_transfer,colorspace,color_range -of json \"$inputPath\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) return null
            val output = session.output.trim()
            if (output.isEmpty()) return null

            val json = org.json.JSONObject(output)
            val streams = json.optJSONArray("streams") ?: return null
            if (streams.length() == 0) return null
            val stream = streams.getJSONObject(0)

            val width = stream.optInt("width", 0)
            val height = stream.optInt("height", 0)
            val videoCodec = stream.optString("codec_name", "")
            val frameRate = stream.optString("r_frame_rate", "")
            val pixelFormat = stream.optString("pix_fmt", "")
            val videoBitrate = stream.optString("bit_rate", "0").toLongOrNull() ?: 0L

            // Use ffprobe for color info (reads bitstream VUI).
            // MediaMetadataRetriever reads container nclx box but returns incorrect values
            // (e.g. BT.601 instead of BT.709) for some videos, so we don't use it.
            // Note: for hevc_mediacodec compressed videos, ffprobe returns bt470bg (no VUI written).
            // This is a known limitation — desktop ffprobe reads both VUI and nclx box, so it shows correctly.
            val colorPrimaries = stream.optString("color_primaries", "").takeIf { it.isNotEmpty() && it != "unknown" } ?: ""
            val colorTransfer = stream.optString("color_transfer", "").takeIf { it.isNotEmpty() && it != "unknown" } ?: ""
            val colorSpace = stream.optString("colorspace", "").takeIf { it.isNotEmpty() && it != "unknown" } ?: ""
            LogCollector.d("FFmpegService", "Color from ffprobe: primaries=$colorPrimaries, transfer=$colorTransfer, space=$colorSpace")

            // Audio stream: use JSON for reliable field extraction
            val audioSession = FFprobeKit.execute(
                "-v quiet -select_streams a:0 -show_entries stream=codec_name,sample_rate,bit_rate -of json \"$inputPath\""
            )
            val audioCodec: String
            var audioSampleRate = 0
            var audioBitrate = 0L
            if (ReturnCode.isSuccess(audioSession.returnCode)) {
                val audioJson = org.json.JSONObject(audioSession.output.trim())
                val audioStreams = audioJson.optJSONArray("streams")
                if (audioStreams != null && audioStreams.length() > 0) {
                    val audioStream = audioStreams.getJSONObject(0)
                    audioCodec = audioStream.optString("codec_name", "none")
                    audioSampleRate = audioStream.optInt("sample_rate", 0)
                    audioBitrate = audioStream.optString("bit_rate", "0").toLongOrNull() ?: 0L
                } else {
                    audioCodec = "none"
                }
            } else {
                audioCodec = "none"
            }

            // Rotation from side_data
            val rotationSession = FFprobeKit.execute(
                "-v quiet -select_streams v:0 -show_entries stream_side_data=rotation -of csv=p=0 \"$inputPath\""
            )
            val rotation = if (ReturnCode.isSuccess(rotationSession.returnCode)) {
                rotationSession.output.trim().toIntOrNull() ?: 0
            } else 0

            // Creation time from format tags
            val formatSession = FFprobeKit.execute(
                "-v quiet -show_entries format_tags=creation_time -of csv=p=0 \"$inputPath\""
            )
            val creationTime = if (ReturnCode.isSuccess(formatSession.returnCode)) {
                formatSession.output.trim().ifEmpty { "" }
            } else ""

            // GPS location (reuse existing probeLocation)
            val location = probeLocation(inputPath) ?: ""

            // File creation time: prefer shooting date if available, fallback to filesystem
            val fileCreationTime = if (creationTime.isNotEmpty()) {
                creationTime
            } else {
                try {
                    val p = java.nio.file.Paths.get(inputPath)
                    val fileTime = java.nio.file.Files.getAttribute(p, "creationTime") as? java.nio.file.attribute.FileTime
                    fileTime?.let {
                        java.time.Instant.ofEpochMilli(it.toMillis())
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    } ?: ""
                } catch (e: Exception) { "" }
            }

            com.pisces312.streamclip.model.VideoInfo(
                path = inputPath,
                width = width,
                height = height,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                frameRate = frameRate,
                pixelFormat = pixelFormat,
                rotation = rotation,
                videoBitrate = videoBitrate,
                audioSampleRate = audioSampleRate,
                audioBitrate = audioBitrate,
                creationTime = creationTime,
                fileCreationTime = fileCreationTime,
                location = location,
                colorSpace = colorSpace,
                colorPrimaries = colorPrimaries,
                colorTransfer = colorTransfer
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
