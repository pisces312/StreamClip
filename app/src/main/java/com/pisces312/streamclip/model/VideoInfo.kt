package com.pisces312.streamclip.model

data class VideoInfo(
    val path: String,
    val width: Int,
    val height: Int,
    val videoCodec: String,
    val audioCodec: String,
    val frameRate: String,
    val pixelFormat: String,
    val rotation: Int
) {
    val resolution: String get() = "${width}x${height}"

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
