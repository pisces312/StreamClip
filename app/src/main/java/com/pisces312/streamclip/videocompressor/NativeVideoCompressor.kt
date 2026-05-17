package com.pisces312.streamclip.videocompressor

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class NativeCompressConfig(
    val mimeType: String = "video/hevc",
    val encoderName: String? = null,
    val targetWidth: Int = 0,
    val targetHeight: Int = 0,
    val bitrateKbps: Int = 0,
    val frameRate: Int = 30,
    val iFrameInterval: Int = 10
)

data class EncoderInfo(
    val name: String,
    val mimeType: String,
    val isHardware: Boolean
) {
    override fun toString(): String {
        val type = if (isHardware) "硬件" else "软件"
        val codec = when (mimeType) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265"
            else -> mimeType
        }
        return "$codec ($type) - $name"
    }
}

object NativeVideoCompressor {

    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"

    fun listAvailableEncoders(): List<EncoderInfo> {
        val encoders = mutableListOf<EncoderInfo>()
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            for (type in codecInfo.supportedTypes) {
                if (type == MIME_AVC || type == MIME_HEVC) {
                    val isHw = isHardwareEncoder(codecInfo.name)
                    encoders.add(EncoderInfo(codecInfo.name, type, isHw))
                }
            }
        }
        return encoders
    }

    fun getDefaultHevcEncoder(): EncoderInfo? {
        return listAvailableEncoders()
            .firstOrNull { it.mimeType == MIME_HEVC && it.isHardware }
            ?: listAvailableEncoders().firstOrNull { it.mimeType == MIME_HEVC }
    }

    private fun isHardwareEncoder(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("omx") || lower.contains("c2") || lower.contains("hw")
    }

    suspend fun compressVideo(
        inputPath: String,
        outputPath: String,
        config: NativeCompressConfig,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            val listener = object : VideoController.CompressProgressListener {
                override fun onProgress(percent: Float) {
                    if (coroutineContext.isActive) {
                        onProgress(percent)
                    }
                }
            }
            val success = VideoController.getInstance().convertVideo(
                inputPath,
                outputPath,
                VideoController.COMPRESS_QUALITY_MEDIUM,
                listener,
                config.mimeType,
                config.targetWidth,
                config.targetHeight,
                config.bitrateKbps * 1000,
                config.frameRate,
                config.iFrameInterval,
                config.encoderName
            )
            if (!coroutineContext.isActive) {
                return@withContext Result.failure(Exception("Cancelled"))
            }
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Native compression failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
