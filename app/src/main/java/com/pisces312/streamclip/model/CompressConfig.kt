package com.pisces312.streamclip.model

data class CompressConfig(
    val encoder: String = "h264_mediacodec",  // h264_mediacodec, hevc_mediacodec, libx264, libx265
    val rateControl: String = "crf",          // crf, bitrate, cq
    val qualityValue: Int = 23,               // CRF 0-51, bitrate kbps, CQ 0-51
    val resolution: String = "original",      // original, 1080p, 720p, 480p
    val frameRate: String = "original",       // original, 30, 24
    val preset: String = "medium",            // ultrafast, superfast, veryfast, faster, fast, medium, slow, slower, veryslow
    val audioEncoder: String = "copy",        // copy, aac, mp3, flac
    val outputFormat: String = "mp4"          // mp4, mkv, webm
) {
    fun toFFmpegCommand(inputPath: String, outputPath: String): String {
        val cmd = StringBuilder("-i \"$inputPath\" ")
        
        // Video encoder
        cmd.append("-c:v $encoder ")
        
        // Rate control
        val isHardwareEncoder = encoder in listOf("h264_mediacodec", "hevc_mediacodec")
        when {
            isHardwareEncoder -> cmd.append("-b:v ${qualityValue}k ")
            rateControl == "crf" -> cmd.append("-crf $qualityValue ")
            rateControl == "bitrate" -> cmd.append("-b:v ${qualityValue}k ")
            rateControl == "cq" -> cmd.append("-cq $qualityValue -qmin $qualityValue -qmax ${qualityValue + 6} ")
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
        
        // Frame rate
        if (frameRate != "original") {
            cmd.append("-r $frameRate ")
        }
        
        // Preset (for software encoders)
        if (encoder in listOf("libx264", "libx265")) {
            cmd.append("-preset $preset ")
        }
        
        // Audio
        cmd.append("-c:a $audioEncoder ")
        
        // Format
        cmd.append("-f $outputFormat ")
        
        cmd.append("\"$outputPath\"")
        
        return cmd.toString()
    }
    
    companion object {
        val ENCODERS = listOf(
            "h264_mediacodec" to "H.264 硬件",
            "hevc_mediacodec" to "H.265 硬件",
            "libx264" to "H.264 软件",
            "libx265" to "H.265 软件"
        )
        
        val RATE_CONTROLS = listOf(
            "crf" to "CRF 质量",
            "bitrate" to "固定码率",
            "cq" to "CQ 恒定质量"
        )
        
        val RESOLUTIONS = listOf(
            "original" to "原始",
            "1080p" to "1080p",
            "720p" to "720p",
            "480p" to "480p"
        )
        
        val FRAME_RATES = listOf(
            "original" to "原始",
            "30" to "30fps",
            "24" to "24fps"
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
            "mkv" to "MKV",
            "webm" to "WebM"
        )
    }
}
