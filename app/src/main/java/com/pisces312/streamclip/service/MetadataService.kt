package com.pisces312.streamclip.service

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pisces312.streamclip.model.VideoMetadata
import com.pisces312.streamclip.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MetadataService {

    /**
     * Read metadata from a video file using probeMediaInfo.
     */
    suspend fun readMetadata(path: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            val info = FFmpegService.probeMediaInfo(path)
                ?: return@withContext Result.failure("FFprobe failed")

            val metadata = info.toVideoMetadata()
            LogCollector.d("MetadataService", "Read metadata: title=${metadata.title}, creationTime=${metadata.creationTime}")

            Result.success(metadata)
        } catch (e: Exception) {
            LogCollector.e("MetadataService", "Read metadata failed: ${e.message}")
            Result.failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Save metadata to a new video file using FFmpeg.
     * Uses -c copy for lossless modification.
     */
    suspend fun saveMetadata(
        inputPath: String,
        outputPath: String,
        metadata: VideoMetadata,
        original: VideoMetadata
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val changedArgs = metadata.buildMetadataArgs(original)
            if (changedArgs.isEmpty()) {
                return@withContext Result.failure("No changes to save")
            }

            val command = buildString {
                append("-i \"$inputPath\"")
                append(" -map_metadata 0")
                changedArgs.forEach { append(" $it") }
                append(" -c copy \"$outputPath\"")
            }

            LogCollector.d("MetadataService", "Executing: $command")

            val session = FFmpegKit.execute(command)

            if (!ReturnCode.isSuccess(session.returnCode)) {
                return@withContext Result.failure("FFmpeg failed: ${session.allLogsAsString}")
            }

            LogCollector.d("MetadataService", "Metadata saved to $outputPath")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e("MetadataService", "Save metadata failed: ${e.message}")
            Result.failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Generate output path for metadata-edited file.
     * Appends "_meta" before the extension.
     */
    fun generateOutputPath(inputPath: String): String {
        val lastDot = inputPath.lastIndexOf('.')
        return if (lastDot > 0) {
            "${inputPath.substring(0, lastDot)}_meta${inputPath.substring(lastDot)}"
        } else {
            "${inputPath}_meta"
        }
    }

    data class Result<T>(
        val success: Boolean,
        val data: T? = null,
        val error: String? = null
    ) {
        companion object {
            fun <T> success(data: T): Result<T> = Result(true, data)
            fun <T> failure(error: String): Result<T> = Result(false, null, error)
        }
    }
}
