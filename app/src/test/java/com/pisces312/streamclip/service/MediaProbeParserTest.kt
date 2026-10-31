package com.pisces312.streamclip.service

import com.pisces312.streamclip.util.Mp4Fixture
import com.pisces312.streamclip.util.Mp4Fixture.mdat
import com.pisces312.streamclip.util.Mp4Fixture.metaMdta
import com.pisces312.streamclip.util.Mp4Fixture.moov
import com.pisces312.streamclip.util.Mp4Fixture.udta
import com.pisces312.streamclip.util.Mp4Fixture.xyz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Host-JVM tests for [MediaProbeParser].
 *
 * The JSON below is copied from a real `ffprobe -print_format json -show_format -show_streams`
 * run on `VID_20261031_112937.mp4` (HONOR BKQ-AN80), trimmed to the fields the app reads. Keeping
 * the real key names is the point: ffprobe *omits* unknown fields instead of failing, so a typo in
 * a key name silently yields "" — which is how `color_space` was read as `colorspace` for a long
 * time and "色彩空间" never appeared in the info card.
 */
class MediaProbeParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun reads_the_three_colour_tags_of_a_vendor_recording() {
        val info = parse(HONOR_STREAM_JSON)!!

        // Container `colr` of this file says bt470bg/smpte170m, the HEVC VUI says bt709/sRGB.
        // ffprobe reports the container values; all three must survive the mapping.
        assertEquals("bt470bg", info.colorPrimaries)
        assertEquals("bt709", info.colorTransfer)
        assertEquals("smpte170m", info.colorSpace)
        assertEquals("yuvj420p", info.pixelFormat)
    }

    @Test
    fun color_space_key_is_the_one_ffprobe_emits() {
        // Regression guard: reading the `colorspace` spelling yields an empty string, quietly
        // dropping the "色彩空间" row. Both spellings in one payload make the distinction explicit.
        val info = parse(
            """
            {"streams":[{"codec_type":"video","codec_name":"hevc","width":64,"height":64,
             "color_space":"bt709","colorspace":"MUST_NOT_BE_READ"}]}
            """.trimIndent()
        )!!

        assertEquals("bt709", info.colorSpace)
    }

    @Test
    fun unknown_colour_values_collapse_to_empty() {
        // ffprobe emits "unknown" for fields present in the schema but unset in the file.
        val info = parse(
            """
            {"streams":[{"codec_type":"video","codec_name":"h264","width":64,"height":64,
             "color_primaries":"unknown","color_transfer":"unknown","color_space":"unknown"}]}
            """.trimIndent()
        )!!

        assertEquals("", info.colorPrimaries)
        assertEquals("", info.colorTransfer)
        assertEquals("", info.colorSpace)
        assertEquals("", info.video!!.colorSpace)
    }

    @Test
    fun missing_colour_fields_collapse_to_empty() {
        val info = parse(
            """{"streams":[{"codec_type":"video","codec_name":"h264","width":64,"height":64}]}"""
        )!!

        assertEquals("", info.colorSpace)
        assertEquals("", info.colorPrimaries)
        assertEquals("", info.colorTransfer)
    }

    @Test
    fun rotation_comes_from_side_data_list() {
        // Shape taken from `ffprobe` on a file re-muxed with -display_rotation 90.
        val info = parse(
            """
            {"streams":[{"codec_type":"video","codec_name":"hevc","width":1280,"height":720,
             "side_data_list":[
               {"side_data_type":"Display Matrix","displaymatrix":"\n00000000:","rotation":90}
             ]}]}
            """.trimIndent()
        )!!

        assertEquals(90, info.rotation)
        // Display dimensions swap for a quarter turn.
        assertEquals(720, info.displayWidth)
        assertEquals(1280, info.displayHeight)
    }

    @Test
    fun negative_rotation_is_normalised() {
        // Android records portrait video as rotation = -90 in the tkhd matrix.
        val info = parse(
            """
            {"streams":[{"codec_type":"video","codec_name":"hevc","width":1280,"height":720,
             "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]}]}
            """.trimIndent()
        )!!

        assertEquals(-90, info.rotation)
        assertEquals(270, info.normalizedRotation)
        assertEquals(720, info.displayWidth)
    }

    @Test
    fun first_video_and_first_audio_stream_win() {
        val info = parse(
            """
            {"streams":[
              {"codec_type":"audio","codec_name":"aac","sample_rate":"48000","bit_rate":"128000",
               "channel_layout":"stereo"},
              {"codec_type":"video","codec_name":"hevc","width":1920,"height":1080,
               "pix_fmt":"yuv420p","bit_rate":"8000000","r_frame_rate":"30000/1001"},
              {"codec_type":"video","codec_name":"mjpeg","width":160,"height":90}
            ]}
            """.trimIndent()
        )!!

        assertEquals("hevc", info.videoCodec)
        assertEquals(1920, info.width)
        assertEquals("30000/1001", info.frameRate)
        assertEquals(8_000_000L, info.videoBitrate)
        assertEquals("aac", info.audioCodec)
        assertEquals(48_000, info.audioSampleRate)
        assertEquals("stereo", info.audio!!.channelLayout)
    }

    @Test
    fun audio_only_file_has_no_video() {
        val info = parse(
            """{"streams":[{"codec_type":"audio","codec_name":"aac","sample_rate":"44100"}]}"""
        )!!

        assertNull(info.video)
        assertEquals("", info.colorSpace)
        assertEquals(0, info.width)
    }

    @Test
    fun payload_without_a_streams_array_is_rejected() {
        // ffprobe writing an error object instead of a probe result must not become a MediaInfo.
        assertNull(MediaProbeParser.parse("/nonexistent.mp4", """{"format":{}}"""))
    }

    @Test
    fun duration_is_converted_from_seconds_to_millis() {
        val info = parse(
            """
            {"format":{"duration":"11.599500","format_name":"mov,mp4,m4a,3gp,3g2,mj2"},
             "streams":[]}
            """.trimIndent()
        )!!

        assertEquals(11_599L, info.durationMs)
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", info.formatName)
    }

    @Test
    fun format_tags_reach_the_convenience_accessors() {
        val info = parse(
            """
            {"format":{"tags":{"creation_time":"2026-10-31T03:29:37.000000Z",
                               "location":"+31.3398+121.5058/",
                               "com.honor.product.model":"BKQ-AN80"}},
             "streams":[]}
            """.trimIndent()
        )!!

        assertEquals("2026-10-31T03:29:37.000000Z", info.creationTime)
        assertEquals("+31.3398+121.5058/", info.location)
    }

    @Test
    fun mdta_table_is_read_from_the_container_alongside_the_json() {
        // ffprobe only surfaces the mdta keys its demuxer maps, so the parser re-reads the boxes.
        val file = Mp4Fixture.write(
            File(temp.root, "honor.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(
                udta(xyz("+31.3398+121.5058/")),
                metaMdta(
                    "com.honor.lens.model" to "back_main",
                    "com.honor.product.model" to "BKQ-AN80"
                )
            )
        )

        val info = parse(HONOR_STREAM_JSON, path = file.path)!!

        assertEquals("back_main", info.mdtaTags["com.honor.lens.model"])
        assertEquals("BKQ-AN80", info.mdtaTags["com.honor.product.model"])
        // Location still comes from udta/©xyz, which ffprobe maps into format tags.
        assertEquals("+31.3398+121.5058/", info.location)
    }

    @Test
    fun missing_file_yields_no_mdta_tags_instead_of_failing() {
        // The card is still worth showing when the container cannot be re-read.
        val info = parse(HONOR_STREAM_JSON, path = File(temp.root, "absent.mp4").path)!!

        assertTrue(info.mdtaTags.isEmpty())
    }

    private fun parse(json: String, path: String = "/nonexistent.mp4") =
        MediaProbeParser.parse(path, json)

    private companion object {
        /** Captured from `ffprobe` on VID_20261031_112937.mp4, trimmed to the read fields. */
        val HONOR_STREAM_JSON = """
            {
                "streams": [
                    {
                        "index": 1,
                        "codec_name": "hevc",
                        "codec_type": "video",
                        "width": 1280,
                        "height": 720,
                        "pix_fmt": "yuvj420p",
                        "color_range": "pc",
                        "color_space": "smpte170m",
                        "color_transfer": "bt709",
                        "color_primaries": "bt470bg",
                        "r_frame_rate": "30/1",
                        "duration": "11.599500",
                        "bit_rate": "11774840",
                        "tags": {
                            "creation_time": "2026-10-31T03:29:37.000000Z",
                            "handler_name": "VideoHandle"
                        }
                    }
                ],
                "format": {
                    "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                    "duration": "11.599500",
                    "tags": {
                        "creation_time": "2026-10-31T03:29:37.000000Z",
                        "location": "+31.3398+121.5058/",
                        "com.honor.lens.model": "back_main",
                        "com.honor.product.model": "BKQ-AN80"
                    }
                }
            }
        """.trimIndent()
    }
}
