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
    val audioSampleRate: String = "copy", // 音频采样率（默认复制，避免 swresample native crash）
    val isHardware: Boolean = true,
    val copyMetadata: Boolean = true   // Copy all metadata from source
) : java.io.Serializable {
    /**
     * @param colorSpace color_space from probe (e.g. "bt709"), empty for SDR
     * @param colorPrimaries color_primaries from probe (e.g. "bt709"), empty for SDR
     * @param colorTransfer color_transfer from probe (e.g. "bt709"/"arib-std-b67"/"smpte2084"), empty for SDR
     */
    fun toFFmpegCommand(
        inputPath: String,
        outputPath: String,
        sourceWidth: Int = 1920,
        sourceHeight: Int = 1080,
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

        // Write color metadata to MOV container (nclx box).
        // hevc_mediacodec does NOT write color info to bitstream VUI — only the container nclx box gets set.
        // Players (including Android MediaCodec decoder) read nclx box, so these flags are necessary.
        // Note: hevc_metadata BSF was tested to also write VUI, but it causes playback issues (video freezes).
        // As a result, ffprobe on Android (ffmpeg-kit 6.0, only reads VUI) shows bt470bg for compressed videos.
        // Desktop ffprobe (reads both VUI and nclx box) shows the correct value.
        if (colorPrimaries.isNotEmpty()) cmd.append("-color_primaries $colorPrimaries ")
        if (colorTransfer.isNotEmpty()) cmd.append("-color_trc $colorTransfer ")
        if (colorSpace.isNotEmpty()) cmd.append("-colorspace $colorSpace ")
        cmd.append("-color_range tv ")

        if (isHdr) {

            if (isHardware) {
                cmd.append("-profile:v main10 ")
                cmd.append("-metadata:s:v:0 hdr10=1 ")
            } else {
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
            val res = RESOLUTIONS.find { it.id == resolution }
            if (res != null) {
                // Use proportional scaling based on source dimensions
                val scaleFactor = when {
                    sourceWidth >= sourceHeight -> {
                        // Landscape source: scale based on width
                        sourceWidth.toFloat() / res.width
                    }
                    else -> {
                        // Portrait source: scale based on height (which is the longer side)
                        sourceHeight.toFloat() / res.width
                    }
                }
                filters.add("scale=iw/${scaleFactor}:ih/${scaleFactor}")
            }
            // Clear rotation metadata when resizing to avoid orientation confusion
            cmd.append("-metadata:s:v:0 rotate=0 ")
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
            if (audioBitrate != "copy") {
                cmd.append("-b:a ${audioBitrate}k ")
            }
            if (audioSampleRate != "copy") {
                cmd.append("-ar $audioSampleRate ")
            }
        }

        // Note: hevc_metadata BSF to write VUI was tested but causes playback issues
        // (video freezes on one frame). Only container-level nclx box flags are used.
        // On Android, ffprobe reads bitstream VUI (empty → bt470bg default),
        // while desktop ffprobe reads both VUI and nclx box → correct values.

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
        
        // Resolution data: name, width, height, display name
        data class ResolutionOption(
            val id: String,
            val label: String,
            val width: Int,
            val height: Int
        ) {
            val pixelCount: Int get() = width * height
            val isLandscape: Boolean get() = width >= height
            val aspectRatio: String get() = simplifyRatio(width, height)

            fun getDisplayLabel(isSourceLandscape: Boolean): String {
                val (w, h) = if (isSourceLandscape) Pair(width, height) else Pair(height, width)
                val orientation = if (isSourceLandscape) "横屏" else "竖屏"
                return "$label (${w}×${h} $orientation $aspectRatio)"
            }

            private fun simplifyRatio(w: Int, h: Int): String {
                val gcd = gcd(w, h)
                val rw = w / gcd
                val rh = h / gcd
                return when {
                    rw == 16 && rh == 9 -> "16:9"
                    rw == 4 && rh == 3 -> "4:3"
                    rw == 1 && rh == 1 -> "1:1"
                    rw == 21 && rh == 9 -> "21:9"
                    else -> "${rw}:${rh}"
                }
            }

            private fun gcd(a: Int, b: Int): Int {
                var x = a
                var y = b
                while (y != 0) {
                    val t = y
                    y = x % y
                    x = t
                }
                return x
            }
        }

        val RESOLUTIONS = listOf(
            ResolutionOption("4kuhd", "4K UHD", 3840, 2160),
            ResolutionOption("2kqhd", "2K QHD", 2560, 1440),
            ResolutionOption("1080p", "1080p FHD", 1920, 1080),
            ResolutionOption("720p", "720p HD", 1280, 720),
            ResolutionOption("480p", "480p SD", 854, 480)
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
            "copy" to "复制",
            "aac" to "AAC",
            "libmp3lame" to "MP3",
            "flac" to "FLAC"
        )

        val AUDIO_BITRATES = listOf(
            "copy" to "复制",
            "64" to "64 kbps",
            "96" to "96 kbps",
            "128" to "128 kbps",
            "192" to "192 kbps",
            "256" to "256 kbps",
            "320" to "320 kbps"
        )

        val AUDIO_SAMPLE_RATES = listOf(
            "copy" to "复制",
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
            "encoder" to "cfg_help_encoder",
            "bitrate" to "cfg_help_bitrate",
            "crf" to "cfg_help_crf",
            "preset" to "cfg_help_preset",
            "framerate" to "cfg_help_framerate",
            "resolution" to "cfg_help_resolution",
            "audio" to "cfg_help_audio",
            "audioBitrate" to "cfg_help_audio_bitrate",
            "audioSampleRate" to "cfg_help_audio_sample_rate"
        )
    }
}
