package com.pisces312.streamclip.model

data class CompressConfig(
    val encoder: String = "h264_mediacodec",
    val bitrate: Int = 2000,           // 硬编码码率 (kbps)
    val crf: Int = 23,                 // 软编码 CRF (0-51)
    val resolution: String = "original",
    val frameRate: String = "original", // 帧率
    val preset: String = "medium",     // 软编码预设
    val audioEncoder: String = "copy",
    val audioBitrate: String = "128",  // 音频码率 (kbps)
    val audioSampleRate: String = "original", // 音频采样率
    val isHardware: Boolean = true,
    val copyMetadata: Boolean = true   // Copy all metadata from source
) : java.io.Serializable {
    /**
     * @param colorSpace color_space from probe (e.g. "bt2020nc"), empty for SDR
     * @param colorPrimaries color_primaries from probe (e.g. "bt2020"), empty for SDR
     * @param colorTransfer color_transfer from probe (e.g. "arib-std-b67"/"smpte2084"), empty for SDR
     */
    fun toFFmpegCommand(
        inputPath: String,
        outputPath: String,
        colorSpace: String = "",
        colorPrimaries: String = "",
        colorTransfer: String = ""
    ): String {
        val cmd = StringBuilder("-y -i \"$inputPath\" ")

        // Copy all metadata first (before encoder settings)
        if (copyMetadata) {
            cmd.append("-map_metadata 0 ")
        }

        val isHdr = colorTransfer == "arib-std-b67" || colorTransfer == "smpte2084"

        // HDR handling — do NOT remove these parameters for HDR sources.
        // Without them, hardware encoder (hevc_mediacodec) will produce washed-out colors
        // because it silently converts 10-bit HDR to 8-bit SDR without proper tonemapping.
        // For SDR sources, FFmpeg infers correct defaults — no explicit flags needed.
        if (isHdr) {
            // Container-level color metadata (players read these to apply correct color rendering)
            if (colorSpace.isNotEmpty()) cmd.append("-colorspace $colorSpace ")
            if (colorPrimaries.isNotEmpty()) cmd.append("-color_primaries $colorPrimaries ")
            if (colorTransfer.isNotEmpty()) cmd.append("-color_trc $colorTransfer ")
            cmd.append("-color_range tv ")

            if (isHardware) {
                // hevc_mediacodec defaults to Main profile (8-bit); Main10 is required for 10-bit HDR
                cmd.append("-profile:v main10 ")
                // Tag stream as HDR10 for player detection
                cmd.append("-metadata:s:v:0 hdr10=1 ")
                // Write HDR metadata into HEVC bitstream (bsf values: primaries=9→BT.2020,
                // transfer=18→HLG/16→PQ, matrix=9→BT.2020nc)
                val tcValue = if (colorTransfer == "arib-std-b67") 18 else 16
                cmd.append("-bsf:v hevc_metadata=video_full_range_flag=0:colour_primaries=9:transfer_characteristics=$tcValue:matrix_coefficients=9 ")
            } else {
                // Software encoder (libx265/libx264) needs explicit 10-bit pixel format
                cmd.append("-pix_fmt yuv420p10le ")
            }
        }

        // Video encoder
        cmd.append("-c:v $encoder ")

        // Rate control
        if (isHardware) {
            cmd.append("-b:v ${bitrate}k ")
        } else {
            cmd.append("-crf $crf ")
            cmd.append("-preset $preset ")
        }

        // Resolution
        val filters = mutableListOf<String>()
        if (resolution != "original") {
            val height = when (resolution) {
                "1080p" -> 1080
                "720p" -> 720
                "480p" -> 480
                else -> -1
            }
            if (height > 0) filters.add("scale=-2:$height")
        }

        if (filters.isNotEmpty()) {
            cmd.append("-vf ${filters.joinToString(",")} ")
        }

        // Frame rate
        if (frameRate != "original") {
            cmd.append("-r $frameRate ")
        }
        
        // Audio
        cmd.append("-c:a $audioEncoder ")
        if (audioEncoder != "copy") {
            if (audioBitrate != "original") {
                cmd.append("-b:a ${audioBitrate}k ")
            }
            if (audioSampleRate != "original") {
                cmd.append("-ar $audioSampleRate ")
            }
        }

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
            "libmp3lame" to "MP3",
            "flac" to "FLAC"
        )

        val AUDIO_BITRATES = listOf(
            "original" to "原始",
            "64" to "64 kbps",
            "96" to "96 kbps",
            "128" to "128 kbps",
            "192" to "192 kbps",
            "256" to "256 kbps"
        )

        val AUDIO_SAMPLE_RATES = listOf(
            "original" to "原始",
            "22050" to "22050 Hz",
            "44100" to "44100 Hz",
            "48000" to "48000 Hz"
        )

        val FRAME_RATES = listOf(
            "original" to "原始",
            "24" to "24 fps",
            "25" to "25 fps",
            "30" to "30 fps",
            "60" to "60 fps"
        )

        // Help texts
        val HELP_TEXTS = mapOf(
            "encoder" to "编码器将视频压缩为更小文件。硬件编码速度快但质量略低，软件编码质量好但速度慢。",
            "bitrate" to "码率决定每秒视频数据量。越高画质越好文件越大。建议：短视频2-4Mbps，长视频1-2Mbps。",
            "crf" to "CRF (Constant Rate Factor) 控制质量。0=无损，23=默认，51=最差。值越小文件越大画质越好。",
            "preset" to "软件编码的预设速度。极快到极慢共9档，越慢压缩率越高文件越小。",
            "framerate" to "帧率控制每秒画面帧数。原始保持原帧率，降低帧率可减小文件但影响流畅度。",
            "resolution" to "输出视频分辨率。原始保持原尺寸，降低分辨率可大幅减小文件。",
            "audio" to "音频编码。复制原音频不重新编码最快，AAC兼容性最好。",
            "audioBitrate" to "音频码率。越高音质越好文件越大。128k为常用值。",
            "audioSampleRate" to "音频采样率。原始保持原采样率，降低可减小文件。"
        )
    }
}
