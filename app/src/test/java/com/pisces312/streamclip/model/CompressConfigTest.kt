package com.pisces312.streamclip.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the command shape produced by [CompressConfig.toFFmpegCommand].
 *
 * The colour block (`-color_primaries` / `-color_trc` / `-colorspace` / `-color_range`) was removed
 * on purpose: measured against a real HONOR recording those options were either input-side no-ops
 * or rewrote only the matrix, leaving the container contradicting the bitstream VUI. Encoding with
 * and without the block produced byte-identical files, so the removal is behaviour-preserving and
 * must not be "helpfully" reintroduced.
 */
class CompressConfigTest {

    private fun command(config: CompressConfig, transfer: String = "bt709"): String =
        config.toFFmpegCommand("/in.mp4", "/out.mp4", 1920, 1080, transfer)

    @Test
    fun no_colour_tags_are_forced() {
        val cmd = command(CompressConfig(encoder = "libx264", isHardware = false))

        listOf("-color_primaries", "-color_trc", "-colorspace", "-color_range").forEach {
            assertFalse("$it must not be forced, got: $cmd", cmd.contains(it))
        }
    }

    @Test
    fun hdr_detection_still_switches_the_encoder_profile() {
        val hardware = command(
            CompressConfig(encoder = "hevc_mediacodec", isHardware = true),
            transfer = "smpte2084"
        )
        assertTrue(hardware.contains("-profile:v main10"))
        assertTrue(hardware.contains("-metadata:s:v:0 hdr10=1"))

        val software = command(
            CompressConfig(encoder = "libx265", isHardware = false),
            transfer = "arib-std-b67"
        )
        assertTrue(software.contains("-pix_fmt yuv420p10le"))
    }

    @Test
    fun mov_container_and_metadata_copy_are_kept() {
        val cmd = command(CompressConfig())

        assertTrue(cmd.contains("-noautorotate"))
        assertTrue(cmd.contains("-map_metadata 0"))
        assertTrue(cmd.contains("-f mov"))
    }
}
