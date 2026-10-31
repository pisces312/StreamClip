package com.pisces312.streamclip.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colour-tag accessors and HDR detection of [MediaInfo].
 *
 * Compression no longer forces any colour option (see `CompressConfig`), so what the user sees in
 * the info card — and what `isHdr` decides for the encoder profile — comes straight from these
 * properties. They are the seam between the probe (container `colr`, via ffprobe) and the encode.
 */
class MediaInfoColorTest {

    private fun info(
        primaries: String = "",
        transfer: String = "",
        space: String = "",
        pixelFormat: String = "yuv420p"
    ) = MediaInfo(
        path = "/video.mp4",
        video = VideoStreamInfo(
            width = 1280,
            height = 720,
            codec = "hevc",
            frameRate = "30/1",
            pixelFormat = pixelFormat,
            colorPrimaries = primaries,
            colorTransfer = transfer,
            colorSpace = space
        )
    )

    @Test
    fun colour_accessors_mirror_the_video_stream() {
        // Values of the HONOR sample: container colr = bt470bg / bt709 / smpte170m.
        val info = info(primaries = "bt470bg", transfer = "bt709", space = "smpte170m")

        assertEquals("bt470bg", info.colorPrimaries)
        assertEquals("bt709", info.colorTransfer)
        assertEquals("smpte170m", info.colorSpace)
    }

    @Test
    fun colour_accessors_are_empty_without_a_video_stream() {
        val audioOnly = MediaInfo(path = "/audio.m4a", audio = AudioStreamInfo("aac", 44_100))

        assertEquals("", audioOnly.colorPrimaries)
        assertEquals("", audioOnly.colorTransfer)
        assertEquals("", audioOnly.colorSpace)
        assertFalse(audioOnly.isHdr)
        assertEquals("", audioOnly.hdrTag)
    }

    @Test
    fun hdr_transfers_are_recognised() {
        // PQ (HDR10) and HLG are the two transfers a phone camera writes for HDR video.
        assertTrue(info(transfer = "smpte2084").isHdr)
        assertTrue(info(transfer = "arib-std-b67").isHdr)
    }

    @Test
    fun sdr_transfers_are_not_hdr() {
        // The HONOR sample is 8-bit SDR: container says bt709, bitstream says sRGB.
        assertFalse(info(transfer = "bt709").isHdr)
        assertFalse(info(transfer = "iec61966-2-1").isHdr)
        assertFalse(info(transfer = "smpte170m").isHdr)
        assertFalse(info(transfer = "").isHdr)
    }

    @Test
    fun hdr_detection_depends_on_transfer_only_not_on_primaries() {
        // A container claiming bt2020 primaries with an SDR transfer is still SDR.
        assertFalse(info(primaries = "bt2020", transfer = "bt709").isHdr)
    }

    @Test
    fun ten_bit_detection_follows_the_pixel_format() {
        assertTrue(info(pixelFormat = "yuv420p10le").is10bit)
        assertTrue(info(pixelFormat = "p010le").is10bit)
        assertFalse(info(pixelFormat = "yuv420p").is10bit)
        assertFalse(info(pixelFormat = "yuvj420p").is10bit)
    }

    @Test
    fun hdr_tag_combines_depth_and_transfer() {
        // The leading space is deliberate: the tag is appended directly after the resolution.
        assertEquals(" [10-bit HDR]", info(pixelFormat = "yuv420p10le", transfer = "smpte2084").hdrTag)
        assertEquals(" [10-bit]", info(pixelFormat = "yuv420p10le", transfer = "bt709").hdrTag)
        assertEquals(" [HDR]", info(pixelFormat = "yuv420p", transfer = "arib-std-b67").hdrTag)
        assertEquals("", info(pixelFormat = "yuv420p", transfer = "bt709").hdrTag)
    }

    @Test
    fun mdta_tags_default_to_empty() = assertTrue(MediaInfo(path = "/video.mp4").mdtaTags.isEmpty())
}
