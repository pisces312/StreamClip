package com.pisces312.streamclip.util

import com.pisces312.streamclip.util.Mp4Fixture.audioTrak
import com.pisces312.streamclip.util.Mp4Fixture.colrNclc
import com.pisces312.streamclip.util.Mp4Fixture.colrNclx
import com.pisces312.streamclip.util.Mp4Fixture.largeMoov
import com.pisces312.streamclip.util.Mp4Fixture.mdat
import com.pisces312.streamclip.util.Mp4Fixture.moov
import com.pisces312.streamclip.util.Mp4Fixture.videoTrak
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Host-JVM tests for [Mp4MetadataUtils.copyColorTag].
 *
 * The real-world shape being reproduced: a HONOR recording whose container `colr` says
 * `nclx` 5/1/6 (bt470bg / bt709 / smpte170m, full range) while its HEVC VUI says sRGB. The mov
 * muxer writes `colr` from the bitstream, so the compressed file ended up as `nclc` 1/13/1 and
 * every tool trusting the container saw a different clip than the original.
 *
 * The transplant must therefore replace the box in place, grow its ancestors, and leave the
 * bitstream untouched.
 */
class Mp4ColorTagTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** What the source recording carries. */
    private val sourceColor = colrNclx(
        primaries = 5, // bt470bg
        transfer = 1,  // bt709
        matrix = 6,    // smpte170m
        fullRange = true
    )

    /** What ffmpeg's muxer wrote from the bitstream VUI. */
    private val muxerColor = colrNclc(
        primaries = 1, // bt709
        transfer = 13, // iec61966-2-1 (sRGB)
        matrix = 1     // bt709
    )

    @Test
    fun replaces_the_muxer_written_colr_with_the_source_one() {
        val output = fileWith("output.mp4", videoTrak(colr = muxerColor))
        val source = fileWith("source.mp4", videoTrak(colr = sourceColor))
        val before = output.readBytes()

        assertTrue(Mp4MetadataUtils.copyColorTag(source.path, output.path))

        val after = output.readBytes()
        // nclx carries a range byte, nclc does not: the file grows by exactly one byte.
        assertEquals(before.size + (sourceColor.size - muxerColor.size), after.size)
        assertArrayEquals(sourceColor, videoColor(after))
        assertChain(after)
    }

    @Test
    fun every_ancestor_of_the_colr_box_grows_with_it() {
        val output = fileWith("output.mp4", videoTrak(colr = muxerColor))
        val source = fileWith("source.mp4", videoTrak(colr = sourceColor))
        val delta = sourceColor.size - muxerColor.size

        val moovBefore = moovSize(output.readBytes())
        assertTrue(Mp4MetadataUtils.copyColorTag(source.path, output.path))
        val after = output.readBytes()

        // stsd/stbl/minf/mdia/trak/moov must all reflect the new size, otherwise the walkers in
        // every player (and in this class) lose sync after the patched box.
        assertEquals(moovBefore + delta, moovSize(after))
        assertChain(after)
    }

    @Test
    fun is_idempotent() {
        val output = fileWith("output.mp4", videoTrak(colr = muxerColor))
        val source = fileWith("source.mp4", videoTrak(colr = sourceColor))

        assertTrue(Mp4MetadataUtils.copyColorTag(source.path, output.path))
        val once = output.readBytes()
        assertTrue(Mp4MetadataUtils.copyColorTag(source.path, output.path))

        assertArrayEquals(once, output.readBytes())
    }

    @Test
    fun inserts_the_box_when_the_muxer_wrote_none() {
        // No colour info in codecpar at all → ffmpeg omits colr entirely. The source's tag still
        // has to be carried over, inserted right after the sample entry's fixed fields.
        val output = fileWith("output.mp4", videoTrak(colr = null))
        val source = fileWith("source.mp4", videoTrak(colr = sourceColor))
        val before = output.readBytes().size

        assertTrue(Mp4MetadataUtils.copyColorTag(source.path, output.path))

        val after = output.readBytes()
        assertEquals(before + sourceColor.size, after.size)
        assertArrayEquals(sourceColor, videoColor(after))
        assertChain(after)
    }

    @Test
    fun noop_when_the_source_has_no_colour_tags() {
        val output = fileWith("output.mp4", videoTrak(colr = muxerColor))
        val source = fileWith("source.mp4", videoTrak(colr = null))

        val before = output.readBytes()
        assertTrue(Mp4MetadataUtils.copyColorTag(source.path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun patches_the_video_track_and_leaves_the_audio_track_alone() {
        // Both tracks own a `stsd`; only the `vide` handler may be touched.
        val audioColor = colrNclc(primaries = 1, transfer = 1, matrix = 5)
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(audioTrak(colr = audioColor), videoTrak(colr = muxerColor))
        )
        val source = fileWith("source.mp4", videoTrak(colr = sourceColor))

        assertTrue(Mp4MetadataUtils.copyColorTag(source.path, output.path))

        val bytes = output.readBytes()
        assertArrayEquals(sourceColor, videoColor(bytes))
        assertTrue("audio colr must be untouched", indexOf(bytes, audioColor) >= 0)
        assertChain(bytes)
    }

    @Test
    fun fails_when_the_destination_has_no_video_track() {
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(audioTrak(colr = muxerColor))
        )
        val source = fileWith("source.mp4", videoTrak(colr = sourceColor))

        val before = output.readBytes()
        assertFalse(Mp4MetadataUtils.copyColorTag(source.path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun fails_when_the_destination_moov_is_not_last() {
        // faststart layout: growing moov would shift mdat and invalidate every chunk offset.
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), moov(videoTrak(colr = muxerColor)), mdat()
        )
        val source = fileWith("source.mp4", videoTrak(colr = sourceColor))

        val before = output.readBytes()
        assertFalse(Mp4MetadataUtils.copyColorTag(source.path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun fails_when_the_source_cannot_be_read() {
        val output = fileWith("output.mp4", videoTrak(colr = muxerColor))

        val before = output.readBytes()
        assertFalse(Mp4MetadataUtils.copyColorTag(File(temp.root, "absent.mp4").path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun keeps_the_64bit_form_of_a_large_moov() {
        // >4 GiB recordings use the largesize encoding; downgrading it to 32 bits would hide every
        // box after moov.
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), largeMoov(videoTrak(colr = muxerColor))
        )
        val source = fileWith("source.mp4", videoTrak(colr = sourceColor))
        val before = output.readBytes()

        assertTrue(Mp4MetadataUtils.copyColorTag(source.path, output.path))

        val after = output.readBytes()
        assertEquals(before.size + (sourceColor.size - muxerColor.size), after.size)
        assertEquals("moov must stay in the 64-bit form", 1, size32Of(after, "moov"))
        assertArrayEquals(sourceColor, videoColor(after))
        assertChain(after)
    }

    @Test
    fun noop_when_source_and_destination_are_the_same_file() {
        val file = fileWith("same.mp4", videoTrak(colr = muxerColor))
        val before = file.readBytes()

        assertTrue(Mp4MetadataUtils.copyColorTag(file.path, file.path))
        assertArrayEquals(before, file.readBytes())
    }

    // --- helpers ---

    private fun fileWith(name: String, trak: ByteArray): File = Mp4Fixture.write(
        File(temp.root, name),
        Mp4Fixture.ftyp(), mdat(), moov(trak)
    )

    /** The video sample entry's `colr` payload, or null when absent. */
    private fun videoColor(bytes: ByteArray): ByteArray? {
        val moov = topLevel(bytes, "moov") ?: return null
        for (trak in children(bytes, Span(bodyStartOf(bytes, moov), moov.end))) {
            if (typeOf(bytes, trak) != "trak") continue
            val mdia = child(bytes, trak, "mdia") ?: continue
            val hdlr = child(bytes, mdia, "hdlr") ?: continue
            val handler = String(bytes, hdlr.start + 8 + 8, 4, Charsets.ISO_8859_1)
            if (handler != "vide") continue
            val minf = child(bytes, mdia, "minf") ?: continue
            val stbl = child(bytes, minf, "stbl") ?: continue
            val stsd = child(bytes, stbl, "stsd") ?: continue
            val entry = children(bytes, Span(bodyStartOf(bytes, stsd), stsd.end)).firstOrNull()
                ?: continue
            val colr = child(bytes, entry, "colr") ?: return null
            return bytes.copyOfRange(colr.start, colr.end)
        }
        return null
    }

    /** Every box in the file, recursively, must land exactly on its parent's end. */
    private fun assertChain(bytes: ByteArray) {
        walk(bytes, 0, bytes.size)
    }

    private fun walk(bytes: ByteArray, start: Int, end: Int) {
        var pos = start
        while (pos + 8 <= end) {
            val size32 = u32At(bytes, pos)
            val header = if (size32 == 1L) 16 else 8
            val size = if (size32 == 1L) u64At(bytes, pos + 8) else size32
            val type = typeOf(bytes, pos)
            assertTrue("$type at $pos has bogus size $size", size >= header)
            assertTrue("$type at $pos overruns parent end $end", pos + size <= end)

            val bodyStart = (pos + header).toInt()
            when (type) {
                "moov", "trak", "mdia", "minf", "stbl" -> walk(bytes, bodyStart, (pos + size).toInt())
                "stsd" -> walk(bytes, bodyStart + 8, (pos + size).toInt()) // FullBox + entry_count
                "hvc1", "hev1", "avc1" ->
                    walk(bytes, bodyStart + Mp4Fixture.VISUAL_SAMPLE_ENTRY_HEADER, (pos + size).toInt())
                "mp4a" ->
                    walk(bytes, bodyStart + Mp4Fixture.AUDIO_SAMPLE_ENTRY_HEADER, (pos + size).toInt())
            }
            pos += size.toInt()
        }
        assertEquals("children must end exactly at the parent's end", end, pos)
    }

    private class Span(val start: Int, val end: Int)

    private fun topLevel(bytes: ByteArray, target: String): Span? =
        children(bytes, Span(0, bytes.size)).firstOrNull { typeOf(bytes, it) == target }
            ?.let { Span(it.start, it.end) }

    private fun child(bytes: ByteArray, parent: Span, target: String): Span? =
        children(bytes, Span(bodyStartOf(bytes, parent), parent.end))
            .firstOrNull { typeOf(bytes, it) == target }

    /** Where a box's children start, i.e. past its header and any fixed fields. */
    private fun bodyStartOf(bytes: ByteArray, span: Span): Int {
        val header = if (u32At(bytes, span.start) == 1L) 16 else 8
        return when (typeOf(bytes, span)) {
            "stsd" -> span.start + header + 8 // FullBox version/flags + entry_count
            "hvc1", "hev1", "avc1" -> span.start + header + Mp4Fixture.VISUAL_SAMPLE_ENTRY_HEADER
            "mp4a" -> span.start + header + Mp4Fixture.AUDIO_SAMPLE_ENTRY_HEADER
            else -> span.start + header
        }
    }

    private fun children(bytes: ByteArray, span: Span): List<Span> {
        val out = mutableListOf<Span>()
        var pos = span.start
        while (pos + 8 <= span.end) {
            val size32 = u32At(bytes, pos)
            val header = if (size32 == 1L) 16 else 8
            val size = if (size32 == 1L) u64At(bytes, pos + 8) else size32
            if (size < header || pos + size > span.end) break
            out.add(Span(pos, (pos + size).toInt()))
            pos += size.toInt()
        }
        return out
    }

    private fun moovSize(bytes: ByteArray): Long {
        val moov = topLevel(bytes, "moov")
        assertNotNull(moov)
        return (moov!!.end - moov.start).toLong()
    }

    private fun typeOf(bytes: ByteArray, pos: Int): String =
        String(bytes, pos + 4, 4, Charsets.ISO_8859_1)

    private fun typeOf(bytes: ByteArray, span: Span): String = typeOf(bytes, span.start)

    /** The raw 32-bit size field of the first top-level box called [type]. */
    private fun size32Of(bytes: ByteArray, type: String): Int {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size32 = u32At(bytes, pos)
            val header = if (size32 == 1L) 16 else 8
            val size = if (size32 == 1L) u64At(bytes, pos + 8) else size32
            if (typeOf(bytes, pos) == type) return size32.toInt()
            if (size < header) break
            pos += size.toInt()
        }
        throw AssertionError("no $type box")
    }

    private fun u32At(bytes: ByteArray, pos: Int): Long =
        ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
            (bytes[pos + 3].toLong() and 0xFF)

    private fun u64At(bytes: ByteArray, pos: Int): Long =
        (u32At(bytes, pos) shl 32) or u32At(bytes, pos + 4)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
