package com.pisces312.streamclip.service

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.model.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object FFmpegService {

    /**
     * All FFmpeg sessions started via [executeCommand], keyed by sessionId.
     * Value = start timestamp, used to pick the most recent one on cancel.
     *
     * Replaces the old single `currentSessionId` field: with concurrent sessions
     * (e.g. batch queue + a tab the user started manually) a single field was
     * overwritten by the newest session, and a completing session cleared the
     * id of a still-running one — both made cancelCurrentSession() miss.
     *
     * cancelCurrentSession() cancels the MOST RECENTLY STARTED session: the
     * single-task cancel buttons are pressed for the task the user just started
     * in that tab. Known limitation (same as the old behavior): if a batch task
     * started after the user's manual task, the batch session is the most recent
     * one. Batch-side cancellation is precise — it goes through coroutine job
     * cancellation + invokeOnCancellation, which targets its own session id.
     */
    private val activeSessions =
        java.util.concurrent.ConcurrentHashMap<Long, Long>()

    fun cancelCurrentSession() {
        val latest = activeSessions.entries.maxByOrNull { it.value }
        if (latest != null) {
            FFmpegKit.cancel(latest.key)
            activeSessions.remove(latest.key)
            LogCollector.i("FFmpegService", "Cancelled session ${latest.key}")
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

            // Field mapping lives in MediaProbeParser so it can be unit-tested on the host JVM.
            MediaProbeParser.parse(path, output)?.also {
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
                    activeSessions.remove(session.sessionId)
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
                    activeSessions.remove(session.sessionId)
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

            activeSessions[session.sessionId] = System.currentTimeMillis()

            continuation.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
                activeSessions.remove(session.sessionId)
            }
        }
    }

    /**
     * Session-tracked synchronous execution for callers that need the raw
     * [FFmpegSession] (log output, astats parsing) instead of a [Result].
     *
     * Uses executeAsync + a latch rather than FFmpegKit.execute() so the session
     * id is known up-front: the session is registered in activeSessions while
     * running, making cancelCurrentSession() work for these paths too.
     * Previously MetadataService / AudioEncoder / FFmpegWaveformLoader called
     * FFmpegKit.execute() directly — invisible to session tracking, and
     * AudioEncoder's own cancel() was dead code because it only captured the
     * session AFTER the blocking execute() returned.
     *
     * [onSessionStarted] fires while the session is running, so callers with
     * their own cancel path (AudioEncoder) can capture the live session.
     *
     * Blocking: call from a background thread only.
     */
    fun executeTrackedSync(
        command: String,
        onSessionStarted: ((FFmpegSession) -> Unit)? = null
    ): FFmpegSession {
        val latch = java.util.concurrent.CountDownLatch(1)
        var completed: FFmpegSession? = null
        val session = FFmpegKit.executeAsync(command, { s ->
            completed = s
            latch.countDown()
        })
        activeSessions[session.sessionId] = System.currentTimeMillis()
        onSessionStarted?.invoke(session)
        try {
            latch.await()
        } finally {
            activeSessions.remove(session.sessionId)
        }
        return completed ?: session
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
