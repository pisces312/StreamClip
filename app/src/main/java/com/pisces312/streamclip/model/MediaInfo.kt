package com.pisces312.streamclip.model

import org.json.JSONObject

data class MediaInfo(
    val path: String,
    val durationMs: Long = -1,          // -1 if unknown
    val formatName: String = "",
    val formatTags: JSONObject = JSONObject(),

    // First video stream (null if no video)
    val video: VideoStreamInfo? = null,

    // First audio stream (null if no audio)
    val audio: AudioStreamInfo? = null
) {
    /** Duration in seconds, -1.0 if unknown */
    val durationSec: Double get() = if (durationMs >= 0) durationMs / 1000.0 else -1.0

    // Convenience accessors for common video fields (return defaults if no video)
    val width: Int get() = video?.width ?: 0
    val height: Int get() = video?.height ?: 0
    val videoCodec: String get() = video?.codec ?: ""
    val audioCodec: String get() = audio?.codec ?: ""
    val frameRate: String get() = video?.frameRate ?: ""
    val pixelFormat: String get() = video?.pixelFormat ?: ""
    val rotation: Int get() = video?.rotation ?: 0
    val videoBitrate: Long get() = video?.bitRate ?: 0
    val audioSampleRate: Int get() = audio?.sampleRate ?: 0
    val audioBitrate: Long get() = audio?.bitRate ?: 0

    // Convenience accessors for tags
    val creationTime: String get() = formatTags.optString("creation_time", "")
    val location: String get() = formatTags.optString("location", "")
        .ifEmpty { formatTags.optString("location-eng", "") }

    // Formatted helpers
    val resolution: String get() = if (width > 0 && height > 0) "${width}x${height}" else "N/A"
    val videoBitrateKbps: String get() = if (videoBitrate > 0) "${videoBitrate / 1000}kbps" else "N/A"
    val audioSampleRateStr: String get() = if (audioSampleRate > 0) "${audioSampleRate}Hz" else "N/A"
    val audioBitrateKbps: String get() = if (audioBitrate > 0) "${audioBitrate / 1000}kbps" else "N/A"

    // File creation time: prefer tag, fallback to filesystem
    val fileCreationTime: String by lazy {
        val ct = creationTime
        if (ct.isNotEmpty()) ct else ""
    }

    // Color info
    val colorSpace: String get() = video?.colorSpace ?: ""
    val colorPrimaries: String get() = video?.colorPrimaries ?: ""
    val colorTransfer: String get() = video?.colorTransfer ?: ""

    // HDR detection
    val is10bit: Boolean get() = video?.pixelFormat?.contains("10") == true
    val isHdr: Boolean get() = video?.colorTransfer == "arib-std-b67" || video?.colorTransfer == "smpte2084"
    val hdrTag: String get() = when {
        is10bit && isHdr -> " [10-bit HDR]"
        is10bit -> " [10-bit]"
        isHdr -> " [HDR]"
        else -> ""
    }

    // Compatibility check for merging
    fun isCompatibleWith(other: MediaInfo): Boolean {
        return width == other.width &&
                height == other.height &&
                videoCodec == other.videoCodec &&
                audioCodec == other.audioCodec &&
                frameRate == other.frameRate &&
                pixelFormat == other.pixelFormat &&
                rotation == other.rotation
    }

    fun getIncompatibleFields(other: MediaInfo): List<String> {
        val fields = mutableListOf<String>()
        if (width != other.width || height != other.height) fields.add("分辨率")
        if (videoCodec != other.videoCodec) fields.add("视频编码")
        if (audioCodec != other.audioCodec) fields.add("音频编码")
        if (frameRate != other.frameRate) fields.add("帧率")
        if (pixelFormat != other.pixelFormat) fields.add("像素格式")
        if (rotation != other.rotation) fields.add("旋转方向")
        return fields
    }

    // Audio extension mapping
    val audioExtension: String get() = codecToExtension(audio?.codec ?: "")

    private fun codecToExtension(codec: String): String = when (codec.lowercase()) {
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

    /** Build VideoMetadata from format tags for metadata editing */
    fun toVideoMetadata(): VideoMetadata = VideoMetadata.fromTags(formatTags)
}

data class VideoStreamInfo(
    val width: Int,
    val height: Int,
    val codec: String,
    val frameRate: String,
    val pixelFormat: String,
    val bitRate: Long = 0,
    val rotation: Int = 0,
    val colorPrimaries: String = "",
    val colorTransfer: String = "",
    val colorSpace: String = ""
)

data class AudioStreamInfo(
    val codec: String,
    val sampleRate: Int = 0,
    val bitRate: Long = 0,
    val channelLayout: String = ""
)
