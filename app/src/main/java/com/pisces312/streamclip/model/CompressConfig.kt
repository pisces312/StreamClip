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
    val rotation: Int = -1,            // 旋转角度 (-1=保持不变, 0/90/180/270)，通过修改 MP4 tkhd display matrix 实现无损旋转
    val isHardware: Boolean = true,
    val copyMetadata: Boolean = true   // Copy all metadata from source
) : java.io.Serializable {
    /**
     * @param colorTransfer `color_transfer` from probe (`bt709`, `arib-std-b67`, `smpte2084`, ...),
     *        empty when unknown. Only used to detect HDR — see the note inside about why no colour
     *        tag is written to the output.
     */
    fun toFFmpegCommand(
        inputPath: String,
        outputPath: String,
        sourceWidth: Int = 1920,
        sourceHeight: Int = 1080,
        colorTransfer: String = ""
    ): String {
        val cmd = StringBuilder("-y ")

        // Prevent FFmpeg from auto-rotating pixels during decode.
        // Without this, FFmpeg rotates pixels based on source tkhd, but then
        // Mp4RotationUtils writes a NEW rotation matrix — causing double rotation
        // (pixels already rotated + tkhd rotation = squished display).
        // With -noautorotate, pixels stay in original orientation and tkhd controls display.
        cmd.append("-noautorotate ")

        cmd.append("-i \"$inputPath\" ")

        // Copy all metadata from source (global + per-stream/chapter)
        if (copyMetadata) {
            cmd.append("-map_metadata 0 ")
        }

        // Rotation is handled post-encoding by directly modifying the MP4 tkhd display matrix.
        // FFmpeg 6.0+ (ffmpeg-kit 8.1) does not support -metadata rotate or -display_rotation for output.
        // See Mp4RotationUtils.setRotation() called after ffmpeg completes.

        val isHdr = colorTransfer == "arib-std-b67" || colorTransfer == "smpte2084"

        // Colour tags are deliberately NOT forced here.
        //
        // The old block passed -color_primaries / -color_trc / -colorspace / -color_range tv, but
        // none of them did what it looked like:
        //   - `-color_primaries` and `-color_trc` are input-side options; measured against a real
        //     recording they never reached the encoder context, so neither VUI nor `colr` changed.
        //   - `-colorspace` does reach the encoder, but writing only the matrix leaves primaries
        //     and transfer coming from the bitstream — one file with two sources of truth.
        //   - `-color_range tv` is a no-op for full-range sources (`yuvj420p`); output stays `pc`,
        //     which is what the pixel data actually is.
        // Encoding with and without the whole block produces byte-identical files (md5 equal),
        // so removing it is behaviour-preserving.
        //
        // ffmpeg fills the encoder context from the first decoded frame, so the output carries
        // whatever the source *bitstream* signalled (VUI). Vendor recordings can disagree with
        // their own container: the HONOR sample writes bt470bg/smpte170m into `colr` while its
        // HEVC VUI says bt709/sRGB, and decoders only honour the VUI. Preserving the bitstream
        // keeps playback identical — see AGENTS.md "陷阱 9".
        // If colour tags ever must be forced, use
        // `-vf setparams=color_primaries=…:color_trc=…:colorspace=…:range=…` instead.

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
            val scaleFactor = SCALE_FACTORS.find { it.id == resolution }
            if (scaleFactor != null) {
                // 确保缩放后宽高为偶数（HEVC/H264 编码器要求）
                filters.add("scale=trunc(iw/${scaleFactor.factor}/2)*2:trunc(ih/${scaleFactor.factor}/2)*2")
            }
        }

        if (filters.isNotEmpty()) {
            cmd.append("-vf ${filters.joinToString(",")} ")
        }

        // Frame rate
        if (frameRate != "original") {
            cmd.append("-r $frameRate ")
        }
        
        // Audio
        if (audioEncoder == "none") {
            cmd.append("-an ")
        } else {
            cmd.append("-c:a $audioEncoder ")
            if (audioEncoder != "copy") {
                if (audioBitrate != "copy") {
                    cmd.append("-b:a ${audioBitrate}k ")
                }
                if (audioSampleRate != "copy") {
                    cmd.append("-ar $audioSampleRate ")
                }
            }
        }

        // Note: hevc_metadata BSF was tested to force VUI into the bitstream but causes playback
        // issues (video freezes on one frame), so it is not used.

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
        
        // Scale factor options for resolution reduction
        data class ScaleFactor(
            val id: String,
            val factor: Float,
            val label: String
        )

        val SCALE_FACTORS = listOf(
            ScaleFactor("scale_1_5", 1.5f, "缩小 1.5×"),
            ScaleFactor("scale_2_25", 2.25f, "缩小 2.25×"),
            ScaleFactor("scale_3_0", 3.0f, "缩小 3.0×")
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
            "none" to "无",
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

        // Rotation options (tkhd display matrix, zero-loss)
        val ROTATIONS = listOf(
            -1 to "保持不变",
            0 to "0°",
            90 to "90°",
            180 to "180°",
            270 to "270°"
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
            "audioSampleRate" to "cfg_help_audio_sample_rate",
            "rotation" to "cfg_help_rotation"
        )
    }
}
