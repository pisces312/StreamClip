package com.pisces312.streamclip.model

data class VideoInfo(
    val path: String,
    val width: Int,
    val height: Int,
    val videoCodec: String,
    val audioCodec: String,
    val frameRate: String,
    val pixelFormat: String,
    val rotation: Int,
    val videoBitrate: Long = 0,      // bits per second
    val audioSampleRate: Int = 0,    // Hz
    val audioBitrate: Long = 0,      // bits per second
    val creationTime: String = "",   // e.g. "2024-03-15 10:30:00"
    val location: String = ""        // e.g. "+121.2345+031.6789/"
) {
    val resolution: String get() = "${width}x${height}"
    val videoBitrateKbps: String get() = if (videoBitrate > 0) "${videoBitrate / 1000}kbps" else "N/A"
    val audioSampleRateStr: String get() = if (audioSampleRate > 0) "${audioSampleRate}Hz" else "N/A"
    val audioBitrateKbps: String get() = if (audioBitrate > 0) "${audioBitrate / 1000}kbps" else "N/A"
    val creationTimeStr: String get() = creationTime.ifEmpty { "N/A" }
    val locationStr: String get() = location.ifEmpty { "N/A" }

    fun isCompatibleWith(other: VideoInfo): Boolean {
        return width == other.width &&
                height == other.height &&
                videoCodec == other.videoCodec &&
                audioCodec == other.audioCodec &&
                frameRate == other.frameRate &&
                pixelFormat == other.pixelFormat &&
                rotation == other.rotation
    }

    fun getIncompatibleFields(other: VideoInfo): List<String> {
        val fields = mutableListOf<String>()
        if (width != other.width || height != other.height) fields.add("分辨率")
        if (videoCodec != other.videoCodec) fields.add("视频编码")
        if (audioCodec != other.audioCodec) fields.add("音频编码")
        if (frameRate != other.frameRate) fields.add("帧率")
        if (pixelFormat != other.pixelFormat) fields.add("像素格式")
        if (rotation != other.rotation) fields.add("旋转方向")
        return fields
    }
}
