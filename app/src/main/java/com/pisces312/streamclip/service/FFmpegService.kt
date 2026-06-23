package com.pisces312.streamclip.service

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.model.AudioStreamInfo
import com.pisces312.streamclip.model.MediaInfo
import com.pisces312.streamclip.model.VideoStreamInfo
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
            LogCollector.i("FFmpegService", "Cancelled session $id")
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
     * Probe all media info in a single ffprobe JSON call.
     * Returns MediaInfo with video/audio streams, format tags, duration, etc.
     */
    fun probeMediaInfo(path: String): MediaInfo? {
        return try {
            val session = FFprobeKit.execute(
                "-v quiet -print_format json -show_format -show_streams \"$path\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                LogCollector.e("FFmpegService", "probeMediaInfo: ffprobe failed, rc=${session.returnCode}")
                return null
            }

            val output = session.output.trim()
            if (output.isEmpty()) {
                LogCollector.e("FFmpegService", "probeMediaInfo: empty output")
                return null
            }

            val json = JSONObject(output)

            // Parse format
            val format = json.optJSONObject("format")
            val durationMs = format?.optString("duration", "-1")?.toDoubleOrNull()
                ?.let { (it * 1000).toLong() } ?: -1L
            val formatName = format?.optString("format_name", "") ?: ""
            val tags = format?.optJSONObject("tags") ?: JSONObject()

            // Parse streams
            val streams = json.optJSONArray("streams") ?: return null
            var videoStream: VideoStreamInfo? = null
            var audioStream: AudioStreamInfo? = null

            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val codecType = s.optString("codec_type", "")

                if (codecType == "video" && videoStream == null) {
                    val cp = s.optString("color_primaries", "")
                        .takeIf { it.isNotEmpty() && it != "unknown" } ?: ""
                    val ct = s.optString("color_transfer", "")
                        .takeIf { it.isNotEmpty() && it != "unknown" } ?: ""
                    val cs = s.optString("colorspace", "")
                        .takeIf { it.isNotEmpty() && it != "unknown" } ?: ""

                    // Rotation from side_data (display matrix in tkhd)
                    var rotation = 0
                    val sideDataList = s.optJSONArray("side_data_list")
                    if (sideDataList != null) {
                        for (j in 0 until sideDataList.length()) {
                            val sd = sideDataList.getJSONObject(j)
                            if (sd.has("rotation")) {
                                rotation = sd.optInt("rotation", 0)
                                break
                            }
                        }
                    }

                    videoStream = VideoStreamInfo(
                        width = s.optInt("width", 0),
                        height = s.optInt("height", 0),
                        codec = s.optString("codec_name", ""),
                        frameRate = s.optString("r_frame_rate", ""),
                        pixelFormat = s.optString("pix_fmt", ""),
                        bitRate = s.optString("bit_rate", "0").toLongOrNull() ?: 0L,
                        rotation = rotation,
                        colorPrimaries = cp,
                        colorTransfer = ct,
                        colorSpace = cs
                    )
                } else if (codecType == "audio" && audioStream == null) {
                    audioStream = AudioStreamInfo(
                        codec = s.optString("codec_name", ""),
                        sampleRate = s.optInt("sample_rate", 0),
                        bitRate = s.optString("bit_rate", "0").toLongOrNull() ?: 0L,
                        channelLayout = s.optString("channel_layout", "")
                    )
                }
            }

            MediaInfo(
                path = path,
                durationMs = durationMs,
                formatName = formatName,
                formatTags = tags,
                video = videoStream,
                audio = audioStream
            ).also {
                LogCollector.i("FFmpegService", "probeMediaInfo: ${it.videoCodec} ${it.resolution} ${it.audioCodec} dur=${it.durationMs}ms")
            }
        } catch (e: Exception) {
            LogCollector.e("FFmpegService", "probeMediaInfo failed: ${e.message}")
            null
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
            LogCollector.i("FFmpegService", "Executing: $command")
            val startTime = System.currentTimeMillis()

            val session = if (onProgress != null || onLog != null) {
                FFmpegKit.executeAsync(command, { session ->
                    currentSessionId = -1
                    val returnCode = session.returnCode
                    val success = ReturnCode.isSuccess(returnCode)
                    val error = if (success) null else (session.output.takeIf { it.isNotEmpty() } ?: "Unknown error")

                    LogCollector.i("FFmpegService", "Completed: success=$success, code=$returnCode, error=$error")

                    continuation.resume(
                        Result(
                            success = success,
                            outputPath = outputPath,
                            error = error
                        )
                    )
                }, { log ->
                    LogCollector.i("FFmpegService", log.message)
                    onLog?.invoke(LogLine(log.message, false))
                }, StatisticsCallback { statistics ->
                    val time = statistics.time.toLong()
                    if (time > 0) {
                        val percent = if (totalTimeMs > 0) {
                            ((time.toDouble() / totalTimeMs) * 100).toInt().coerceIn(0, 100)
                        } else {
                            -1
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
                    currentSessionId = -1
                    val returnCode = session.returnCode
                    val success = ReturnCode.isSuccess(returnCode)
                    val error = if (success) null else (session.output.takeIf { it.isNotEmpty() } ?: "Unknown error")

                    LogCollector.i("FFmpegService", "Completed: success=$success, code=$returnCode, error=$error")

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

        LogCollector.i("FFmpegService", "Command: $command")
        return executeCommand(command, outputPath, onProgress = onProgress)
    }

    /**
     * Write format tags to a sidecar file for ffmpeg -map_metadata.
     * Uses probeMediaInfo JSON result.
     */
    private fun extractTagsToFile(inputPath: String, metadataFile: File): Boolean {
        return try {
            val info = probeMediaInfo(inputPath) ?: return false
            val tags = info.formatTags
            if (tags.length() == 0) return false
            val lines = tags.keys().asSequence().map { key -> "$key=${tags.getString(key)}" }.toList()
            if (lines.isEmpty()) return false
            metadataFile.writeText(lines.joinToString("\n"))
            true
        } catch (e: Exception) {
            LogCollector.e("FFmpegService", "Extract tags failed: ${e.message}")
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
            return Result(false, error = "MERGE_NEED_2")
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
                if (extractTagsToFile(inputPaths[0], metadataFile)) {
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
        LogCollector.i("FFmpegService", "Command: $command")
        return executeCommand(command, outputPath, onProgress = onProgress)
    }

    /**
     * Compress video with hardware or software encoder
     */
    suspend fun compressVideo(
        context: Context,
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        videoBitrate: Int,
        audioBitrate: Int,
        useHwEncoder: Boolean = true,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        File(outputPath).parentFile?.mkdirs()

        val command = buildString {
            append("-y -i \"$inputPath\"")
            append(" -map_metadata 0")

            if (useHwEncoder) {
                append(" -c:v hevc_mediacodec -vendor.hevc-mediacodec.bitrate-mode 1")
                append(" -b:v ${videoBitrate}k -maxrate ${videoBitrate}k")
                append(" -bufsize ${(videoBitrate * 2)}k")
            } else {
                append(" -c:v libx265")
                append(" -b:v ${videoBitrate}k -maxrate ${videoBitrate}k")
                append(" -bufsize ${(videoBitrate * 2)}k")
                append(" -preset fast -tune ssim")
            }

            append(" -vf \"scale=$width:$height:flags=lanczos\"")
            append(" -c:a aac -b:a ${audioBitrate}k")
            append(" -movflags +faststart")
            append(" -tag:v hvc1")
            append(" \"$outputPath\"")
        }

        LogCollector.i("FFmpegService", "Command: $command")
        val totalTimeMs = probeMediaInfo(inputPath)?.durationMs ?: -1L
        return executeCommand(command, outputPath, totalTimeMs, onProgress = onProgress)
    }

    /**
     * Compress audio (re-encode to lower bitrate)
     */
    suspend fun compressAudio(
        context: Context,
        inputPath: String,
        outputPath: String,
        audioBitrate: Int,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        File(outputPath).parentFile?.mkdirs()

        val command = buildString {
            append("-y -i \"$inputPath\"")
            append(" -map_metadata 0")
            append(" -c:a aac -b:a ${audioBitrate}k")
            append(" -vn")
            append(" \"$outputPath\"")
        }

        LogCollector.i("FFmpegService", "Command: $command")
        val totalTimeMs = probeMediaInfo(inputPath)?.durationMs ?: -1L
        return executeCommand(command, outputPath, totalTimeMs, onProgress = onProgress)
    }
}
