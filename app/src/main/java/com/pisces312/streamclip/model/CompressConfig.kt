package com.pisces312.streamclip.model

data class CompressConfig(
    val encoder: String = "h264_mediacodec",
    val bitrate: Int = 2000,           // 硬编码码率 (kbps)
    val crf: Int = 23,                 // 软编码 CRF (0-51)
    val resolution: String = "original",
    val speed: String = "balanced",    // 硬编码速度
    val preset: String = "medium",     // 软编码预设
    val audioEncoder: String = "copy",
    val outputFormat: String = "mp4",
    val isHardware: Boolean = true,
    val copyMetadata: Boolean = true   // Copy all metadata from source
) {
    fun toFFmpegCommand(inputPath: String, outputPath: String): String {
        val cmd = StringBuilder("-i \"$inputPath\" ")
        
        // Copy all metadata first (before encoder settings)
        if (copyMetadata) {
            cmd.append("-map_metadata 0 ")
            cmd.append("-movflags use_metadata_tags ")
        }
        
        // Video encoder
        cmd.append("-c:v $encoder ")
        
        // Rate control
        if (isHardware) {
            cmd.append("-b:v ${bitrate}k ")
            // MediaCodec speed/quality hint
            when (speed) {
                "speed" -> cmd.append("-tune zerolatency ")
                "quality" -> cmd.append("-cq 18 ")
                else -> {} // balanced: no extra flag
            }
        } else {
            cmd.append("-crf $crf ")
            cmd.append("-preset $preset ")
        }
        
        // Resolution
        if (resolution != "original") {
            val height = when (resolution) {
                "1080p" -> 1080
                "720p" -> 720
                "480p" -> 480
                else -> -1
            }
            if (height > 0) cmd.append("-vf scale=-2:$height ")
        }
        
        // Audio
        cmd.append("-c:a $audioEncoder ")

        // Format: use MOV format to preserve GPS metadata (moov/udta/xyz atom)
        // Android MediaMetadataRetriever reads xyz atom, not loci
        cmd.append("-f mov ")

        cmd.append("\"$outputPath\"")

        return cmd.toString()
    }
    
    companion object {
        // Hardware encoders
        val HW_ENCODERS = listOf(
            "h264_mediacodec" to "H.264 硬件",
            "hevc_mediacodec" to "H.265 硬件"
        )
        
        // Software encoders
        val SW_ENCODERS = listOf(
            "libx264" to "H.264 软件",
            "libx265" to "H.265 软件"
        )
        
        val BITRATES = listOf(
            500 to "500 Kbps",
            1000 to "1 Mbps",
            2000 to "2 Mbps",
            4000 to "4 Mbps",
            8000 to "8 Mbps",
            12000 to "12 Mbps"
        )
        
        val SPEEDS = listOf(
            "speed" to "速度优先",
            "balanced" to "平衡",
            "quality" to "质量优先"
        )
        
        val RESOLUTIONS = listOf(
            "original" to "原始",
            "1080p" to "1080p",
            "720p" to "720p",
            "480p" to "480p"
        )
        
        val PRESETS = listOf(
            "ultrafast" to "极快",
            "superfast" to "很快",
            "veryfast" to "非常快",
            "faster" to "更快",
            "fast" to "快",
            "medium" to "中等",
            "slow" to "慢",
            "slower" to "更慢",
            "veryslow" to "极慢"
        )
        
        val AUDIO_ENCODERS = listOf(
            "copy" to "复制原音频",
            "aac" to "AAC",
            "mp3" to "MP3",
            "flac" to "FLAC"
        )
        
        val FORMATS = listOf(
            "mp4" to "MP4",
            "mkv" to "MKV"
        )
        
        // Help texts
        val HELP_TEXTS = mapOf(
            "encoder" to "编码器将视频压缩为更小文件。硬件编码速度快但质量略低，软件编码质量好但速度慢。",
            "bitrate" to "码率决定每秒视频数据量。越高画质越好文件越大。建议：短视频2-4Mbps，长视频1-2Mbps。",
            "crf" to "CRF (Constant Rate Factor) 控制质量。0=无损，23=默认，51=最差。值越小文件越大画质越好。",
            "speed" to "硬件编码的速度质量平衡。速度优先适合快速处理，质量优先适合最终输出。",
            "preset" to "软件编码的预设速度。极快到极慢共10档，越慢压缩率越高文件越小。",
            "resolution" to "输出视频分辨率。原始保持原尺寸，降低分辨率可大幅减小文件。",
            "audio" to "音频编码。复制原音频不重新编码最快，AAC兼容性最好。"
        )
    }
}
