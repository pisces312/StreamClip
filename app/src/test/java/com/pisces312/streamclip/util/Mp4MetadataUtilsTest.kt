package com.pisces312.streamclip.util

import com.pisces312.streamclip.util.Mp4Fixture.mdat
import com.pisces312.streamclip.util.Mp4Fixture.metaItunes
import com.pisces312.streamclip.util.Mp4Fixture.metaMdta
import com.pisces312.streamclip.util.Mp4Fixture.moov
import com.pisces312.streamclip.util.Mp4Fixture.udta
import com.pisces312.streamclip.util.Mp4Fixture.xyz
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Host-JVM tests for [Mp4MetadataUtils].
 *
 * The fixtures mirror the two container shapes that matter in practice:
 *
 *  - HONOR (MagicOS): `moov/udta/©xyz` for GPS **plus** `moov/meta` (mdta) holding
 *    `com.honor.lens.model` / `com.honor.product.model`
 *  - Apple: `moov/meta` (mdta) holding `com.apple.quicktime.make/model/lens_model`
 *
 * Both are reproduced structurally; the payload bytes only need to survive verbatim.
 */
class Mp4MetadataUtilsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun copyCameraMetadata_appends_mdta_meta_and_keeps_gps_atom() {
        val meta = metaMdta(
            "com.honor.lens.model" to "back_main",
            "com.honor.product.model" to "BKQ-AN80"
        )
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(udta(xyz("+31.3398+121.5058/")), meta)
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(udta(xyz("+31.3398+121.5058/")))
        )

        val before = output.readBytes()
        val (_, moovSizeBefore) = moovOf(before)

        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))

        val after = output.readBytes()
        // moov grew by exactly the transplanted meta box, appended at the end of the file.
        assertEquals(before.size + meta.size, after.size)
        assertArrayEquals(meta, after.copyOfRange(after.size - meta.size, after.size))
        val (_, moovSizeAfter) = moovOf(after)
        assertEquals(moovSizeBefore + meta.size, moovSizeAfter)
        // The GPS atom ffmpeg wrote must remain intact — Android reads location from here.
        assertTrue(indexOf(after, xyz("+31.3398+121.5058/")) >= 0)
    }

    @Test
    fun copyCameraMetadata_is_idempotent() {
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(metaMdta("com.apple.quicktime.model" to "iPhone 15 Pro"))
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov()
        )

        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))
        val first = output.readBytes()
        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))
        assertArrayEquals(first, output.readBytes())
    }

    @Test
    fun copyCameraMetadata_accepts_meta_nested_in_udta() {
        // ffmpeg's own mdta writer nests `meta` inside `udta`; the reader must accept both forms.
        val meta = metaMdta("com.apple.quicktime.lens_model" to "back camera 6.765mm f/1.78")
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(udta(meta))
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(udta(xyz("+22.5+114.0/")))
        )

        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))
        assertArrayEquals(meta, output.readBytes().let {
            it.copyOfRange(it.size - meta.size, it.size)
        })
    }

    @Test
    fun copyCameraMetadata_skips_when_moov_is_not_last() {
        // faststart layout: growing moov would shift mdat and invalidate stco/co64 offsets.
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(metaMdta("com.honor.lens.model" to "back_main"))
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), moov(), mdat()
        )

        val before = output.readBytes()
        assertFalse(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun copyCameraMetadata_noop_when_source_has_no_meta() {
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(udta(xyz("+22.5+114.0/")))
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov()
        )

        val before = output.readBytes()
        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun copyCameraMetadata_skips_itunes_meta() {
        // An `mdir` meta carries title/artist, which ffmpeg already round-trips via udta;
        // transplanting it would duplicate keys.
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(metaItunes("holiday"))
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov()
        )

        val before = output.readBytes()
        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    // --- readMdtaTags (info card) ---

    @Test
    fun readMdtaTags_lists_every_key_of_the_camera_table() {
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(
                metaMdta(
                    "com.honor.lens.model" to "back_main",
                    "com.honor.product.model" to "BKQ-AN80"
                )
            )
        )

        val tags = Mp4MetadataUtils.readMdtaTags(source.path)

        // Order follows the keys table, which is what the info card renders.
        assertEquals(listOf("com.honor.lens.model", "com.honor.product.model"), tags.keys.toList())
        assertEquals("back_main", tags["com.honor.lens.model"])
        assertEquals("BKQ-AN80", tags["com.honor.product.model"])
    }

    @Test
    fun readMdtaTags_accepts_meta_nested_in_udta() {
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(udta(metaMdta("com.apple.quicktime.lens_model" to "back camera 6.765mm f/1.78")))
        )

        val tags = Mp4MetadataUtils.readMdtaTags(source.path)

        assertEquals("back camera 6.765mm f/1.78", tags["com.apple.quicktime.lens_model"])
    }

    @Test
    fun readMdtaTags_is_empty_without_an_mdta_table() {
        // iTunes (mdir) tables and files with no meta box at all must both yield "nothing to show".
        val itunes = Mp4Fixture.write(
            File(temp.root, "itunes.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(metaItunes("holiday"))
        )
        val plain = Mp4Fixture.write(
            File(temp.root, "plain.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(udta(xyz("+22.5+114.0/")))
        )

        assertTrue(Mp4MetadataUtils.readMdtaTags(itunes.path).isEmpty())
        assertTrue(Mp4MetadataUtils.readMdtaTags(plain.path).isEmpty())
    }

    @Test
    fun readMdtaTags_sees_the_transplanted_table() {
        // The info card for the output file reads the same table the transplant wrote.
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(metaMdta("com.honor.lens.model" to "back_main"))
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov()
        )

        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))

        assertEquals(
            mapOf("com.honor.lens.model" to "back_main"),
            Mp4MetadataUtils.readMdtaTags(output.path)
        )
    }

    // --- camera metadata: the value shapes vendors actually write ---

    @Test
    fun readMdtaTags_keeps_the_full_apple_camera_key_set_in_order() {
        // The keys Apple writes on iPhone recordings; all of them matter to Photos/gallery apps.
        val apple = listOf(
            "com.apple.quicktime.make" to "Apple",
            "com.apple.quicktime.model" to "iPhone 15 Pro",
            "com.apple.quicktime.software" to "17.4.1",
            "com.apple.quicktime.lens_model" to "iPhone 15 Pro back triple camera 6.765mm f/1.78",
            "com.apple.quicktime.creationdate" to "2026-10-01T12:00:00+0800"
        )
        val source = Mp4Fixture.write(
            File(temp.root, "source.mov"),
            Mp4Fixture.ftyp(), mdat(),
            moov(metaMdta(*apple.toTypedArray()))
        )

        val tags = Mp4MetadataUtils.readMdtaTags(source.path)

        assertEquals(apple.map { it.first }, tags.keys.toList())
        apple.forEach { (key, value) -> assertEquals(key, value, tags[key]) }
    }

    @Test
    fun readMdtaTags_decodes_utf16_and_numeric_payloads() {
        // `data` boxes are typed: text is type 1/2, integers 21/22, float 23, and anything else
        // is opaque. A table mixing them must still render every key.
        val source = Mp4Fixture.write(
            File(temp.root, "typed.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(
                Mp4Fixture.metaMdta(
                    Mp4Fixture.MdtaEntry.text("com.honor.lens.model", "back_main"),
                    Mp4Fixture.MdtaEntry.utf16("com.apple.quicktime.model", "iPhone 15 Pro"),
                    Mp4Fixture.MdtaEntry.int("honor.facing", -1),
                    Mp4Fixture.MdtaEntry.unsigned("honor.iso", 1280),
                    Mp4Fixture.MdtaEntry.float("honor.fnumber", 1.78f)
                )
            )
        )

        val tags = Mp4MetadataUtils.readMdtaTags(source.path)

        assertEquals("back_main", tags["com.honor.lens.model"])
        assertEquals("iPhone 15 Pro", tags["com.apple.quicktime.model"])
        assertEquals("-1", tags["honor.facing"])
        assertEquals("1280", tags["honor.iso"])
        assertEquals("1.78", tags["honor.fnumber"])
    }

    @Test
    fun readMdtaTags_trims_the_nul_terminator_of_text_values() {
        // Both type 1 and type 2 payloads are NUL-padded in real files; the padding must not leak
        // into the info card.
        val source = Mp4Fixture.write(
            File(temp.root, "nul.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(
                Mp4Fixture.metaMdta(
                    Mp4Fixture.MdtaEntry.text("honor.a", "back_main"),
                    Mp4Fixture.MdtaEntry.utf16("honor.b", "BKQ-AN80")
                )
            )
        )

        val tags = Mp4MetadataUtils.readMdtaTags(source.path)

        assertEquals("back_main", tags["honor.a"])
        assertEquals("BKQ-AN80", tags["honor.b"])
    }

    @Test
    fun readMdtaTags_previews_binary_values_instead_of_dropping_them() {
        // Motion-photo / thumbnail payloads are opaque; showing a short hex preview keeps the key
        // visible without dumping kilobytes into the card.
        val thumb = ByteArray(20) { it.toByte() }
        val source = Mp4Fixture.write(
            File(temp.root, "binary.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(Mp4Fixture.metaMdta(Mp4Fixture.MdtaEntry.binary("com.apple.quicktime.thumb", thumb)))
        )

        val value = Mp4MetadataUtils.readMdtaTags(source.path)["com.apple.quicktime.thumb"]

        assertEquals("0x000102030405060708090a0b0c0d0e0f (20 bytes)", value)
    }

    @Test
    fun readMdtaTags_is_empty_when_the_keys_table_has_no_ilst() {
        val source = Mp4Fixture.write(
            File(temp.root, "keys-only.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(Mp4Fixture.metaKeysOnly("com.honor.lens.model"))
        )

        assertTrue(Mp4MetadataUtils.readMdtaTags(source.path).isEmpty())
    }

    @Test
    fun readMdtaTags_skips_indices_outside_the_keys_table() {
        // A malformed or truncated table must not shift values onto the wrong keys.
        val source = Mp4Fixture.write(
            File(temp.root, "bad-index.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(
                Mp4Fixture.metaMdtaEntries(
                    entries = listOf(
                        Mp4Fixture.MdtaEntry.text("honor.a", "back_main"),
                        Mp4Fixture.MdtaEntry.text("honor.b", "ghost")
                    ),
                    indices = listOf(1, 7)
                )
            )
        )

        val tags = Mp4MetadataUtils.readMdtaTags(source.path)

        assertEquals(mapOf("honor.a" to "back_main"), tags)
    }

    @Test
    fun readMdtaTags_survives_a_truncated_file() {
        // Reading a half-written output while the encoder is still running must not throw.
        val whole = Mp4Fixture.write(
            File(temp.root, "whole.mp4"),
            Mp4Fixture.ftyp(), mdat(),
            moov(metaMdta("com.honor.lens.model" to "back_main"))
        ).readBytes()
        val cut = File(temp.root, "cut.mp4").apply { writeBytes(whole.copyOf(whole.size / 2)) }

        assertTrue(Mp4MetadataUtils.readMdtaTags(cut.path).isEmpty())
    }

    @Test
    fun readMdtaTags_is_empty_for_a_file_without_moov() {
        val source = Mp4Fixture.write(File(temp.root, "no-moov.mp4"), Mp4Fixture.ftyp(), mdat())

        assertTrue(Mp4MetadataUtils.readMdtaTags(source.path).isEmpty())
    }

    // --- transplant edge cases ---

    @Test
    fun copyCameraMetadata_grows_a_64bit_moov_and_rewrites_the_largesize_field() {
        // >4 GiB recordings use the 64-bit box form; the appended meta must land inside the new
        // largesize, otherwise every later box is invisible to the parser.
        val meta = metaMdta("com.honor.lens.model" to "back_main")
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(meta)
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), Mp4Fixture.largeMoov()
        )

        val before = output.readBytes().size
        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))

        val after = output.readBytes()
        assertEquals(before + meta.size, after.size)
        // size32 == 1 keeps the 64-bit form: the muxer must not silently downgrade to 32-bit.
        assertEquals(1, u32At(after, moovOf(after).first))
        assertEquals("back_main", Mp4MetadataUtils.readMdtaTags(output.path)["com.honor.lens.model"])
    }

    @Test
    fun copyCameraMetadata_fails_when_the_destination_has_no_moov() {
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(metaMdta("com.honor.lens.model" to "back_main"))
        )
        val output = Mp4Fixture.write(File(temp.root, "output.mp4"), Mp4Fixture.ftyp(), mdat())

        val before = output.readBytes()
        assertFalse(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun copyCameraMetadata_fails_when_the_source_cannot_be_read() {
        val output = Mp4Fixture.write(File(temp.root, "output.mp4"), Mp4Fixture.ftyp(), mdat(), moov())

        val before = output.readBytes()
        assertFalse(Mp4MetadataUtils.copyCameraMetadata(File(temp.root, "absent.mp4").path, output.path))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun copyCameraMetadata_is_a_noop_when_source_and_destination_are_the_same_file() {
        // Compressing in place must never append the table onto itself.
        val file = Mp4Fixture.write(
            File(temp.root, "same.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(metaMdta("com.honor.lens.model" to "back_main"))
        )
        val before = file.readBytes()

        assertTrue(Mp4MetadataUtils.copyCameraMetadata(file.path, file.path))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun copyCameraMetadata_does_not_duplicate_into_a_destination_that_already_has_mdta() {
        // A second pass over an already-patched file (e.g. re-compressing the output) must not
        // grow the moov again.
        val meta = metaMdta("com.honor.lens.model" to "back_main")
        val source = Mp4Fixture.write(
            File(temp.root, "source.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(meta)
        )
        val output = Mp4Fixture.write(
            File(temp.root, "output.mp4"),
            Mp4Fixture.ftyp(), mdat(), moov(meta)
        )

        val before = output.readBytes()
        assertTrue(Mp4MetadataUtils.copyCameraMetadata(source.path, output.path))
        assertEquals(before.size, output.readBytes().size)
    }

    // --- helpers ---


    private fun moovOf(bytes: ByteArray): Pair<Int, Int> {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size = ((bytes[pos].toInt() and 0xFF) shl 24) or
                       ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                       ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                       (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            if (type == "moov") return pos to size
            if (size < 8) break
            pos += size
        }
        throw AssertionError("no moov box in fixture")
    }

    private fun u32At(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 24) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
            (bytes[pos + 3].toInt() and 0xFF)

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
