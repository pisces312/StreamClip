package com.pisces312.streamclip.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Host-JVM regression tests for [Mp4RotationUtils].
 *
 * Fixtures are synthesised in code by [Mp4Fixture], which reproduces the container shapes of the
 * two real recordings that exposed the rotation bugs (see "回归测试" in AGENTS.md). No video files
 * are needed: the class under test never looks inside `mdat`, only at box headers, the `tkhd`
 * display matrix and the `hdlr` handler type.
 *
 * Every container shape that matters (32-bit / 64-bit / zero-sized `mdat`, `moov` before and
 * after `mdat`, version-1 `tkhd`, handler-type vs volume heuristics) is covered by mock header
 * fixtures, so the suite needs no sample video files on disk.
 */
class Mp4RotationUtilsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // --- copyRotation ---

    @Test
    fun copyRotation_restores_matrix_when_mdat_uses_64bit_size() {
        // Regression: an `mdat` with size field == 1 (64-bit largesize) placed before `moov`
        // aborted the box walk at the mdat header, so copyRotation silently did nothing and the
        // output played unrotated — a portrait clip appearing rotated 90°.
        val source = cameraRecording()
        val output = encodedOutput(1280, 720)

        assertTrue(Mp4RotationUtils.copyRotation(source.path, output.path))

        val track = readVideoTrack(output)
        assertArrayEquals(Mp4Fixture.quarterTurn(), readMatrix(output, track.matrixOffset))
        // tkhd dimensions must stay at the coded size, otherwise Android derives a squashed
        // display size from them and the gallery thumbnail comes out landscape.
        assertEquals(1280, track.width)
        assertEquals(720, track.height)
    }

    @Test
    fun copyRotation_copies_translation_verbatim_instead_of_rebuilding_it() {
        // Both real fixtures carry the same orientation but differ in tx (2160 << 16 vs 0).
        // Rebuilding the matrix from an angle normalises that difference away, so compare with
        // the exact source bytes.
        val source = wechatExport4k()
        val output = encodedOutput(3840, 2160)

        assertTrue(Mp4RotationUtils.copyRotation(source.path, output.path))

        val expected = readMatrix(source, readVideoTrack(source).matrixOffset)
        val actual = readMatrix(output, readVideoTrack(output).matrixOffset)
        assertArrayEquals(expected, actual)
        assertEquals(2160 shl 16, actual[6])
    }

    @Test
    fun copyRotation_scales_translation_when_compression_changes_resolution() {
        val source = wechatExport4k()
        val output = encodedOutput(1280, 720)

        assertTrue(Mp4RotationUtils.copyRotation(source.path, output.path))

        val matrix = readMatrix(output, readVideoTrack(output).matrixOffset)
        // 90°/270°: the destination X axis maps to the source Y axis, so tx scales with 720/2160.
        assertEquals(720 shl 16, matrix[6])
        assertEquals(0, matrix[7])
    }

    @Test
    fun copyRotation_scales_ty_for_quarter_turn_the_other_way() {
        val source = writeFile(
            "src-270.mp4",
            Mp4Fixture.ftyp(),
            Mp4Fixture.moov(
                Mp4Fixture.trak(
                    "vide",
                    Mp4Fixture.tkhd(1, 900, Mp4Fixture.threeQuarterTurn(1280), 1280, 720)
                )
            ),
            Mp4Fixture.mdat()
        )
        val output = encodedOutput(640, 360)

        assertTrue(Mp4RotationUtils.copyRotation(source.path, output.path))

        val matrix = readMatrix(output, readVideoTrack(output).matrixOffset)
        assertEquals(0, matrix[6])
        assertEquals(640 shl 16, matrix[7])
    }

    @Test
    fun copyRotation_handles_mdat_with_size_zero() {
        val source = writeFile(
            "src-openended.mp4",
            Mp4Fixture.ftyp(),
            Mp4Fixture.moov(
                Mp4Fixture.trak("vide", Mp4Fixture.tkhd(1, 900, Mp4Fixture.quarterTurn(720 shl 16), 1280, 720))
            ),
            Mp4Fixture.openEndedMdat()
        )
        val output = encodedOutput(1280, 720)

        assertTrue(Mp4RotationUtils.copyRotation(source.path, output.path))
        assertArrayEquals(
            Mp4Fixture.quarterTurn(720 shl 16),
            readMatrix(output, readVideoTrack(output).matrixOffset)
        )
    }

    @Test
    fun copyRotation_is_noop_when_source_has_no_rotation() {
        val source = writeFile(
            "src-upright.mp4",
            Mp4Fixture.ftyp(),
            Mp4Fixture.moov(
                Mp4Fixture.trak("vide", Mp4Fixture.tkhd(1, 900, Mp4Fixture.identity(), 1920, 1080))
            ),
            Mp4Fixture.largeMdat()
        )
        val output = encodedOutput(1920, 1080)

        assertTrue(Mp4RotationUtils.copyRotation(source.path, output.path))
        assertArrayEquals(Mp4Fixture.identity(), readMatrix(output, readVideoTrack(output).matrixOffset))
    }

    @Test
    fun copyRotation_prefers_handler_type_over_volume_heuristic() {
        // Audio trak first, declaring dimensions and zero volume — the legacy
        // (volume == 0 && width > 0) heuristic alone would pick it. Only `hdlr == vide` can
        // identify the right track.
        val source = writeFile(
            "src-audio-first.mp4",
            Mp4Fixture.ftyp(),
            Mp4Fixture.moov(
                Mp4Fixture.trak("soun", Mp4Fixture.tkhd(1, 900, Mp4Fixture.identity(), 640, 480)),
                Mp4Fixture.trak("vide", Mp4Fixture.tkhd(2, 900, Mp4Fixture.quarterTurn(), 1280, 720))
            ),
            Mp4Fixture.largeMdat()
        )
        val output = encodedOutput(1280, 720)

        assertTrue(Mp4RotationUtils.copyRotation(source.path, output.path))
        assertArrayEquals(Mp4Fixture.quarterTurn(), readMatrix(output, readVideoTrack(output).matrixOffset))
    }

    @Test
    fun copyRotation_returns_false_when_source_is_missing() {
        val output = encodedOutput(1280, 720)
        assertFalse(Mp4RotationUtils.copyRotation(File(temp.root, "missing.mp4").path, output.path))
    }

    @Test
    fun copyRotation_returns_false_when_source_is_not_isobmff() {
        val junk = temp.newFile("junk.mp4").apply {
            writeBytes("this is not an mp4 file, not even close".toByteArray())
        }
        val output = encodedOutput(1280, 720)
        assertFalse(Mp4RotationUtils.copyRotation(junk.path, output.path))
    }

    // --- setRotation ---

    @Test
    fun setRotation_writes_expected_matrix_for_each_quarter_turn() {
        val cases = listOf(
            90 to Mp4Fixture.quarterTurn(tx = 720 shl 16),
            180 to Mp4Fixture.halfTurn(1280, 720),
            270 to Mp4Fixture.threeQuarterTurn(1280)
        )

        for ((degrees, expected) in cases) {
            val file = encodedOutput(1280, 720, name = "rotate-$degrees.mp4")

            assertTrue("setRotation($degrees) must succeed", Mp4RotationUtils.setRotation(file.path, degrees))

            val track = readVideoTrack(file)
            assertArrayEquals("matrix for $degrees°", expected, readMatrix(file, track.matrixOffset))
            assertEquals("tkhd width must stay at coded size ($degrees°)", 1280, track.width)
            assertEquals("tkhd height must stay at coded size ($degrees°)", 720, track.height)
        }
    }

    @Test
    fun setRotation_skips_negative_and_zero() {
        val file = encodedOutput(1280, 720)

        assertFalse(Mp4RotationUtils.setRotation(file.path, -1))
        assertTrue(Mp4RotationUtils.setRotation(file.path, 0))
        assertTrue(Mp4RotationUtils.setRotation(file.path, 360))
        assertArrayEquals(Mp4Fixture.identity(), readMatrix(file, readVideoTrack(file).matrixOffset))
    }

    @Test
    fun setRotation_supports_version1_tkhd() {
        // v1 tkhd (64-bit timestamps) shifts the matrix 12 bytes further into the box.
        val file = writeFile(
            "src-v1.mp4",
            Mp4Fixture.ftyp(),
            Mp4Fixture.moov(
                Mp4Fixture.trak(
                    "vide",
                    Mp4Fixture.tkhd(1, 900, Mp4Fixture.identity(), 1280, 720, version = 1)
                )
            ),
            Mp4Fixture.mdat()
        )

        assertTrue(Mp4RotationUtils.setRotation(file.path, 90))
        assertArrayEquals(
            Mp4Fixture.quarterTurn(tx = 720 shl 16),
            readMatrix(file, readVideoTrack(file).matrixOffset)
        )
    }

    @Test
    fun setRotation_returns_false_when_moov_is_absent() {
        val file = writeFile("broken.mp4", Mp4Fixture.ftyp(), Mp4Fixture.mdat(64))
        assertFalse(Mp4RotationUtils.setRotation(file.path, 90))
    }

    // --- fixture fidelity ---

    /**
     * Anchors [Mp4Fixture] to reality: these are the raw `tkhd` boxes lifted from the two
     * original recordings (bytes 156..247 of the 4K export, 2016087..2016178 of the 720p camera
     * clip). If the builder ever drifts from the layout ffmpeg/Android muxers write, the
     * synthetic fixtures stop being meaningful and this test says so.
     */
    @Test
    fun builder_reproduces_real_tkhd_bytes() {
        val fourK = with(Mp4Fixture) {
            tkhd(trackId = 1, duration = 1002, matrix = quarterTurn(tx = 2160 shl 16), width = 3840, height = 2160).toHex()
        }
        assertEquals(REAL_TKHD_4K, fourK)

        val camera = with(Mp4Fixture) {
            tkhd(trackId = 2, duration = 1534, matrix = quarterTurn(), width = 1280, height = 720).toHex()
        }
        assertEquals(REAL_TKHD_720P, camera)
    }

    // --- fixtures ---

    /** Shape of the WeChat export: `moov` first, 32-bit `mdat`, video trak first, `tx = height`. */
    private fun wechatExport4k(): File = writeFile(
        "src-4k.mp4",
        Mp4Fixture.ftyp(),
        Mp4Fixture.moov(
            Mp4Fixture.trak("vide", Mp4Fixture.tkhd(1, 1002, Mp4Fixture.quarterTurn(2160 shl 16), 3840, 2160)),
            Mp4Fixture.trak("soun", Mp4Fixture.tkhd(2, 1002, Mp4Fixture.identity(), 0, 0, Mp4Fixture.AUDIO_VOLUME))
        ),
        Mp4Fixture.free(),
        Mp4Fixture.mdat()
    )

    /** Shape of the camera clip: `mdat` (64-bit size) first, audio trak first, `tx = 0`. */
    private fun cameraRecording(): File = writeFile(
        "src-720p.mp4",
        Mp4Fixture.ftyp(),
        Mp4Fixture.free(),
        Mp4Fixture.largeMdat(),
        Mp4Fixture.moov(
            Mp4Fixture.trak("soun", Mp4Fixture.tkhd(1, 1534, Mp4Fixture.identity(), 0, 0, Mp4Fixture.AUDIO_VOLUME)),
            Mp4Fixture.trak("vide", Mp4Fixture.tkhd(2, 1534, Mp4Fixture.quarterTurn(), 1280, 720))
        )
    )

    /** What ffmpeg 8.1 produces after a re-encode: faststart layout, identity matrix. */
    private fun encodedOutput(width: Int, height: Int, name: String = "encoded.mp4"): File = writeFile(
        name,
        Mp4Fixture.ftyp(),
        Mp4Fixture.moov(
            Mp4Fixture.trak("vide", Mp4Fixture.tkhd(1, 1002, Mp4Fixture.identity(), width, height))
        ),
        Mp4Fixture.mdat()
    )

    private fun writeFile(name: String, vararg boxes: ByteArray): File =
        Mp4Fixture.write(temp.newFile(name), *boxes)

    // --- minimal independent ISOBMFF reader (kept separate from production code) ---

    private class Track(val matrixOffset: Long, val width: Int, val height: Int, val handler: String?)

    private class TkhdInfo(val matrixOffset: Long, val width: Int, val height: Int)

    private class Box(val type: String, val start: Long, val size: Long, val headerSize: Long) {
        val bodyStart: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    private fun readBox(raf: RandomAccessFile, pos: Long, limit: Long): Box? {
        if (pos + 8 > limit) return null
        raf.seek(pos)
        val head = ByteArray(8)
        raf.readFully(head)
        val sizeField = be32(head, 0)
        val type = String(head, 4, 4, Charsets.US_ASCII)

        var headerSize = 8L
        val size = when {
            sizeField == 1L -> {
                val ext = ByteArray(8)
                raf.readFully(ext)
                headerSize = 16
                be64(ext)
            }
            sizeField == 0L -> limit - pos
            sizeField < 8L -> return null
            else -> sizeField
        }
        if (size < headerSize || pos + size > limit) return null
        return Box(type, pos, size, headerSize)
    }

    private fun readVideoTrack(file: File): Track {
        RandomAccessFile(file, "r").use { raf ->
            val fileSize = raf.length()
            var pos = 0L
            while (pos + 8 <= fileSize) {
                val box = readBox(raf, pos, fileSize) ?: break
                if (box.type == "moov") {
                    val tracks = readTraks(raf, box)
                    return tracks.firstOrNull { it.handler == "vide" }
                        ?: tracks.firstOrNull { it.width > 0 && it.height > 0 }
                        ?: error("no video trak in ${file.name}")
                }
                pos = box.end
            }
        }
        error("moov not found in ${file.name}")
    }

    private fun readTraks(raf: RandomAccessFile, moov: Box): List<Track> {
        val tracks = mutableListOf<Track>()
        var pos = moov.bodyStart
        while (pos + 8 <= moov.end) {
            val box = readBox(raf, pos, moov.end) ?: break
            if (box.type == "trak") {
                var matrixOffset = -1L
                var width = 0
                var height = 0
                var handler: String? = null
                var cursor = box.bodyStart
                while (cursor + 8 <= box.end) {
                    val child = readBox(raf, cursor, box.end) ?: break
                    when (child.type) {
                        "tkhd" -> readTkhd(raf, child)?.let {
                            matrixOffset = it.matrixOffset
                            width = it.width
                            height = it.height
                        }
                        "mdia" -> handler = readHandler(raf, child) ?: handler
                    }
                    if (child.end <= cursor) break
                    cursor = child.end
                }
                if (matrixOffset >= 0) tracks.add(Track(matrixOffset, width, height, handler))
            }
            if (box.end <= pos) break
            pos = box.end
        }
        return tracks
    }

    private fun readHandler(raf: RandomAccessFile, mdia: Box): String? {
        var pos = mdia.bodyStart
        while (pos + 8 <= mdia.end) {
            val box = readBox(raf, pos, mdia.end) ?: break
            if (box.type == "hdlr" && box.bodyStart + 12 <= box.end) {
                raf.seek(box.bodyStart + 8)
                val type = ByteArray(4)
                raf.readFully(type)
                return String(type, Charsets.US_ASCII)
            }
            if (box.end <= pos) break
            pos = box.end
        }
        return null
    }

    private fun readTkhd(raf: RandomAccessFile, tkhd: Box): TkhdInfo? {
        raf.seek(tkhd.bodyStart)
        val version = raf.read()
        val matrixOffset = if (version == 1) tkhd.bodyStart + 52 else tkhd.bodyStart + 40
        if (matrixOffset + 36 > tkhd.end) return null

        raf.seek(matrixOffset + 36)
        val dims = ByteArray(8)
        raf.readFully(dims)
        val width = (be32(dims, 0) ushr 16).toInt()
        val height = (be32(dims, 4) ushr 16).toInt()

        return TkhdInfo(matrixOffset, width, height)
    }

    private fun readMatrix(file: File, offset: Long): IntArray {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val bytes = ByteArray(36)
            raf.readFully(bytes)
            return IntArray(9) { be32(bytes, it * 4).toInt() }
        }
    }

    private fun be32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
               ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
               ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
               (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun be64(bytes: ByteArray): Long {
        return (be32(bytes, 0) shl 32) or be32(bytes, 4)
    }

    private companion object {
        /** `tkhd` of the 4K WeChat export: 3840x2160, tx = 2160 << 16. */
        const val REAL_TKHD_4K =
            "0000005c746b68640000000300000000000000000000000100000000000003ea000000000000000000000000" +
            "00000000000000000001000000000000ffff000000000000000000000870000000000000400000000f000000" +
            "08700000"

        /** `tkhd` of the 720p camera clip: 1280x720, tx = 0. */
        const val REAL_TKHD_720P =
            "0000005c746b68640000000300000000000000000000000200000000000005fe000000000000000000000000" +
            "00000000000000000001000000000000ffff0000000000000000000000000000000000004000000005000000" +
            "02d00000"
    }
}
