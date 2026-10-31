package com.pisces312.streamclip.util

import java.io.RandomAccessFile

/**
 * Transplants camera / lens metadata from a source recording into a re-encoded output.
 *
 * ## Why this is needed
 *
 * Vendors store camera metadata as QuickTime *mdta* keys inside `moov/meta` (a `keys` table
 * plus an `ilst` index), not as classic 4-character `udta` atoms:
 *
 *  - HONOR/MagicOS: `com.honor.lens.model`, `com.honor.product.model`
 *  - Apple: `com.apple.quicktime.make` / `.model` / `.lens_model` / `.location.ISO6709`
 *
 * FFmpeg's mov muxer cannot write those keys on the normal path: `mov_write_udta_tag()` only
 * emits a hard-coded whitelist (`©nam`, `©mak`, `©mod`, `©xyz`, ...) when the output is
 * `MODE_MOV` without `use_metadata_tags`, and `-map_metadata 0` merely copies the keys into the
 * format dictionary where the muxer then ignores them. The only muxer switch that emits mdta
 * (`-movflags +use_metadata_tags`) takes the *other* branch of `mov_write_udta_tag()`, which
 * drops `©xyz` — the atom Android's MediaMetadataRetriever reads for location — and additionally
 * writes container bookkeeping keys (`major_brand`, `minor_version`, `compatible_brands`) into
 * the mdta table as duplicated values.
 *
 * So the container is patched after encoding instead: the source `meta` box is copied verbatim
 * and appended to the output `moov`. This keeps both representations intact (`©xyz` from
 * ffmpeg's own udta writing, mdta from the transplant) and leaves the bitstream untouched.
 *
 * ## Safety precondition
 *
 * Appending grows `moov`, which is only harmless while `moov` is the last top-level box: then
 * nothing after it moves and `stco`/`co64` chunk offsets stay valid. FFmpeg writes `moov` last
 * unless `-movflags +faststart` is used, which this project never does. When the precondition
 * does not hold the method bails out and logs, rather than risk a file with broken offsets.
 */
object Mp4MetadataUtils {

    private const val TAG = "Mp4MetadataUtils"

    /** A camera/lens mdta table is a few hundred bytes; more than this means a mis-parse. */
    private const val MAX_META_SIZE = 1L * 1024 * 1024

    /** `colr` is normally 18–19 bytes; an embedded ICC profile (`prof`) is a few KiB at most. */
    private const val MAX_COLR_SIZE = 256L * 1024

    /**
     * VisualSampleEntry layout before its first child box: 8 bytes (reserved + data reference
     * index) plus 70 bytes of fixed fields (pre_defined, resolution, frame count, compressor
     * name, depth).
     */
    private const val VISUAL_SAMPLE_ENTRY_HEADER = 78L

    /** Sample entry types ffmpeg can emit for video; anything else is not a visual entry. */
    private val VISUAL_SAMPLE_ENTRIES = setOf(
        "hvc1", "hev1", "avc1", "avc3", "dvh1", "dvhe", "mp4v", "vp08", "vp09", "av01"
    )

    /** Binary mdta values (thumbnails, still-image flags) are previewed, not dumped whole. */
    private const val MAX_BINARY_PREVIEW = 16

    /**
     * Copy the source's mdta metadata (`moov/meta` or `moov/udta/meta`) into [destPath].
     *
     * @return true when the metadata was copied, was already present, or the source carries no
     *         mdta table (nothing to preserve); false when the transplant failed. Callers should
     *         treat a failure as non-fatal — the encoded file itself is still valid.
     */
    fun copyCameraMetadata(sourcePath: String, destPath: String): Boolean {
        if (sourcePath == destPath) return true

        val meta = try {
            readMdtaMetaBox(sourcePath)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to read source metadata: ${e.message}")
            return false
        }

        if (meta == null) {
            LogCollector.d(TAG, "Source carries no mdta metadata, nothing to copy")
            return true
        }

        return try {
            appendMetaBox(destPath, meta)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to transplant metadata: ${e.message}")
            false
        }
    }

    // --- colour tags (stsd/colr) ---

    /**
     * Copy the source's `stsd` colour box (`colr`) into [destPath]'s video sample entry.
     *
     * The muxer derives `colr` from the encoder context, which ffmpeg fills from the **first
     * decoded frame** — i.e. from the bitstream VUI, not from the source container. A recording
     * whose container and VUI disagree (the HONOR sample writes bt470bg/smpte170m into `colr`
     * while its HEVC VUI says bt709/sRGB) therefore comes out with different
     * 色彩空间/色域/传输 than the original, and every tool that trusts `colr` sees a different
     * clip. Copying the box makes the output say exactly what the input said.
     *
     * The bitstream is untouched: re-encoding already produced VUI values of its own, and
     * rewriting those would change how players interpret the pixels. This only aligns the
     * container with the source, matching what "preserve metadata" means for the other boxes.
     *
     * @return true when the tag was copied, was already identical, or the source has no `colr`
     *         (nothing to preserve); false when the destination could not be patched safely.
     */
    fun copyColorTag(sourcePath: String, destPath: String): Boolean {
        if (sourcePath == destPath) return true

        val colr = try {
            readVideoColor(sourcePath)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to read source colour tags: ${e.message}")
            return false
        }
        if (colr == null) {
            LogCollector.d(TAG, "Source carries no colr box, nothing to copy")
            return true
        }

        return try {
            patchVideoColor(destPath, colr)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to transplant colour tags: ${e.message}")
            false
        }
    }

    private fun readVideoColor(path: String): ByteArray? {
        RandomAccessFile(path, "r").use { file ->
            val colr = findVideoColorSlot(file)?.colr ?: return null
            if (colr.size > MAX_COLR_SIZE) {
                LogCollector.w(TAG, "Ignoring oversized colr box (${colr.size} bytes)")
                return null
            }
            val raw = ByteArray(colr.size.toInt())
            file.seek(colr.start)
            file.readFully(raw)
            return raw
        }
    }

    /**
     * Replace (or insert) the video sample entry's `colr` inside the destination moov.
     *
     * The moov is rebuilt in memory and written back at its original offset. Every ancestor of
     * the splice point — `stsd`, `stbl`, `minf`, `mdia`, `trak`, `moov` — starts *before* it, so
     * their offsets stay valid and only their size fields need the delta. Nothing after `moov`
     * exists (checked), so chunk offsets into `mdat` are unaffected.
     */
    private fun patchVideoColor(destPath: String, colr: ByteArray): Boolean {
        RandomAccessFile(destPath, "rw").use { file ->
            val fileSize = file.length()
            val moov = findBox(file, 0, fileSize, "moov")
            if (moov == null) {
                LogCollector.w(TAG, "Destination has no moov box")
                return false
            }
            if (moov.end != fileSize) {
                LogCollector.w(TAG, "moov is not the last box; skipping colour patch to keep chunk offsets valid")
                return false
            }

            val slot = findVideoColorSlot(file)
            if (slot == null) {
                LogCollector.w(TAG, "Destination has no video sample entry")
                return false
            }

            val old = slot.colr
            if (old != null && old.size == colr.size.toLong() && sameBytes(file, old, colr)) {
                LogCollector.d(TAG, "Colour tags already match the source")
                return true
            }

            val spliceStart = old?.start ?: slot.insertAt
            val spliceEnd = old?.end ?: slot.insertAt
            val delta = colr.size - (spliceEnd - spliceStart)
            val newMoovSize = moov.size + delta
            if (delta > 0 && newMoovSize > 0xFFFFFFFFL) {
                LogCollector.w(TAG, "moov would exceed 32-bit box size")
                return false
            }

            val original = ByteArray(moov.size.toInt())
            file.seek(moov.start)
            file.readFully(original)

            val start = (spliceStart - moov.start).toInt()
            val end = (spliceEnd - moov.start).toInt()
            val patched = ByteArray(newMoovSize.toInt())
            System.arraycopy(original, 0, patched, 0, start)
            System.arraycopy(colr, 0, patched, start, colr.size)
            System.arraycopy(original, end, patched, start + colr.size, original.size - end)

            for (box in slot.ancestors) {
                writeBoxSize(patched, (box.start - moov.start).toInt(), box.headerSize, box.size + delta)
            }
            writeBoxSize(patched, 0, moov.headerSize, newMoovSize)

            file.seek(moov.start)
            file.write(patched)
            file.setLength(moov.start + patched.size)

            LogCollector.i(
                TAG,
                "Transplanted ${colr.size}-byte colr box (${if (old == null) "inserted" else "replaced"}), moov delta ${delta}"
            )
            return true
        }
    }

    /**
     * The video track's `colr` box plus everything that has to grow with it.
     *
     * [ancestors] are the containing boxes in outer-to-inner order, ending with the sample entry
     * that holds `colr`; all of them start before the splice point, which is what keeps their
     * offsets stable while the moov is rebuilt.
     */
    private class ColorSlot(val colr: Box?, val ancestors: List<Box>, val insertAt: Long)

    private fun findVideoColorSlot(file: RandomAccessFile): ColorSlot? {
        val moov = findBox(file, 0, file.length(), "moov") ?: return null
        var pos = moov.bodyStart
        while (pos + 8 <= moov.end) {
            val trak = readBoxHeader(file, pos, moov.end) ?: return null
            if (trak.type == "trak") {
                slotOfVideoTrak(file, trak)?.let { return it }
            }
            if (trak.end <= pos) return null
            pos = trak.end
        }
        return null
    }

    /** Audio tracks have their own `stsd`; the handler type is the only reliable discriminator. */
    private fun slotOfVideoTrak(file: RandomAccessFile, trak: Box): ColorSlot? {
        val mdia = findBox(file, trak.bodyStart, trak.end, "mdia") ?: return null
        val hdlr = findBox(file, mdia.bodyStart, mdia.end, "hdlr") ?: return null
        if (handlerType(file, hdlr) != "vide") return null

        val minf = findBox(file, mdia.bodyStart, mdia.end, "minf") ?: return null
        val stbl = findBox(file, minf.bodyStart, minf.end, "stbl") ?: return null
        val stsd = findBox(file, stbl.bodyStart, stbl.end, "stsd") ?: return null
        // `stsd` is a FullBox: version/flags(4) + entry_count(4), then the sample entries.
        val entry = readBoxHeader(file, stsd.bodyStart + 8, stsd.end) ?: return null
        if (entry.type !in VISUAL_SAMPLE_ENTRIES) return null

        // VisualSampleEntry: 8 (reserved + data_reference_index) + 70 bytes of fixed fields,
        // then child boxes. Anything at that offset must be a real box header or we bail out
        // rather than splice into the middle of the fixed fields.
        val insertAt = entry.bodyStart + VISUAL_SAMPLE_ENTRY_HEADER
        if (insertAt > entry.end) return null

        return ColorSlot(
            colr = findBox(file, insertAt, entry.end, "colr"),
            // The sample entry itself grows too — it is `colr`'s direct parent, and leaving its
            // size behind makes every subsequent parse of the entry run off the end of the box.
            ancestors = listOf(entry, stsd, stbl, minf, mdia, trak),
            insertAt = insertAt
        )
    }

    private fun sameBytes(file: RandomAccessFile, box: Box, expected: ByteArray): Boolean {
        if (box.size != expected.size.toLong()) return false
        val actual = ByteArray(expected.size)
        file.seek(box.start)
        file.readFully(actual)
        return actual.contentEquals(expected)
    }

    /** Rewrite a box's size field in [buffer], honouring the 64-bit (`size == 1`) form. */
    private fun writeBoxSize(buffer: ByteArray, offset: Int, headerSize: Long, newSize: Long) {
        if (headerSize == 16L) {
            buffer[offset] = 0
            buffer[offset + 1] = 0
            buffer[offset + 2] = 0
            buffer[offset + 3] = 1
            for (i in 0 until 8) {
                buffer[offset + 8 + i] = ((newSize ushr (56 - 8 * i)) and 0xFF).toByte()
            }
        } else {
            for (i in 0 until 4) {
                buffer[offset + i] = ((newSize ushr (24 - 8 * i)) and 0xFF).toByte()
            }
        }
    }

    // --- reading the mdta table (for the info card) ---

    /**
     * Every `key = value` pair of [path]'s mdta table, in `keys`/`ilst` order.
     *
     * These are the "type-2" keys: vendor camera data (`com.honor.lens.model`,
     * `com.apple.quicktime.model`, ...) stored in `moov/meta`, **not** the classic `udta`
     * 4-character atoms. ffprobe surfaces only the subset its demuxer maps into the metadata
     * dictionary, so the boxes are parsed directly to show all of them.
     *
     * @return an empty map when the file has no mdta `meta` box, the box is an iTunes (`mdir`)
     *         table, or the structure is malformed — "nothing to show", never an error.
     */
    fun readMdtaTags(path: String): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        try {
            RandomAccessFile(path, "r").use { file ->
                val moov = findBox(file, 0, file.length(), "moov") ?: return emptyMap()
                val meta = findMetaBox(file, moov) ?: return emptyMap()
                if (meta.size > MAX_META_SIZE) return emptyMap()
                if (!isMdta(file, meta)) return emptyMap()

                val keys = readMdtaKeys(file, meta)
                if (keys.isEmpty()) return emptyMap()
                val ilst = findBox(file, meta.bodyStart + 4, meta.end, "ilst") ?: return emptyMap()

                var pos = ilst.bodyStart
                while (pos + 8 <= ilst.end) {
                    val entry = readBoxHeader(file, pos, ilst.end) ?: break
                    // An ilst child's box type *is* the 1-based index into the keys table.
                    val index = entry.typeRaw.toInt()
                    if (index in 1..keys.size) {
                        readEntryValue(file, entry)?.let { tags[keys[index - 1]] = it }
                    }
                    if (entry.end <= pos) break
                    pos = entry.end
                }
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to read mdta tags: ${e.message}")
        }
        return tags
    }

    /** Apple/HONOR put `meta` directly under `moov`; ffmpeg nests it inside `udta`. */
    private fun findMetaBox(file: RandomAccessFile, moov: Box): Box? {
        findBox(file, moov.bodyStart, moov.end, "meta")?.let { return it }
        val udta = findBox(file, moov.bodyStart, moov.end, "udta") ?: return null
        return findBox(file, udta.bodyStart, udta.end, "meta")
    }

    /** `keys` is a FullBox: version/flags(4), entry_count(4), then size-prefixed key names. */
    private fun readMdtaKeys(file: RandomAccessFile, meta: Box): List<String> {
        val keys = findBox(file, meta.bodyStart + 4, meta.end, "keys") ?: return emptyList()
        file.seek(keys.bodyStart)
        readUInt32(file) // version/flags
        val count = readUInt32(file).toInt()
        if (count <= 0) return emptyList()

        val result = ArrayList<String>(count)
        repeat(count) {
            if (file.filePointer + 8 > keys.end) return result
            val entrySize = readUInt32(file).toInt()
            readUInt32(file) // key namespace ('mdta' for camera tables)
            val nameLength = entrySize - 8
            if (nameLength <= 0 || file.filePointer + nameLength > keys.end) return result
            val name = ByteArray(nameLength)
            file.readFully(name)
            result.add(String(name, Charsets.UTF_8))
        }
        return result
    }

    /**
     * An `ilst` entry wraps exactly one `data` box: 4-byte well-known type, 4-byte locale, then
     * the payload. Text (1) and UTF-16 (2) decode as strings, integers/floats as numbers, and
     * everything else shows a short hex preview so nothing is silently dropped.
     */
    private fun readEntryValue(file: RandomAccessFile, entry: Box): String? {
        val data = findBox(file, entry.bodyStart, entry.end, "data") ?: return null
        if (data.bodyStart + 8 > data.end) return null
        file.seek(data.bodyStart)
        val wellKnownType = readUInt32(file)
        readUInt32(file) // locale/country code

        val payloadStart = data.bodyStart + 8
        val length = (data.end - payloadStart).toInt()
        if (length <= 0) return ""
        val payload = ByteArray(length)
        file.seek(payloadStart)
        file.readFully(payload)

        return when (wellKnownType) {
            1L -> String(payload, Charsets.UTF_8).trimEnd('\u0000')
            2L -> String(payload, Charsets.UTF_16BE).trimEnd('\u0000')
            21L -> readBigEndian(payload, signed = true)
            22L -> readBigEndian(payload, signed = false)
            23L -> if (payload.size == 4) {
                java.nio.ByteBuffer.wrap(payload).float.toString()
            } else null
            24L -> if (payload.size == 8) {
                java.nio.ByteBuffer.wrap(payload).double.toString()
            } else null
            else -> "0x" + payload.take(MAX_BINARY_PREVIEW).joinToString("") { "%02x".format(it) } +
                if (payload.size > MAX_BINARY_PREVIEW) " (${payload.size} bytes)" else ""
        }
    }

    private fun readBigEndian(bytes: ByteArray, signed: Boolean): String? {
        if (bytes.isEmpty() || bytes.size > 8) return null
        var value = 0L
        for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xFF)
        if (signed) {
            val shift = 64 - bytes.size * 8
            value = (value shl shift) shr shift
        }
        return value.toString()
    }

    // --- reading the source ---

    private fun readMdtaMetaBox(path: String): ByteArray? {
        RandomAccessFile(path, "r").use { file ->
            val limit = file.length()
            val moov = findBox(file, 0, limit, "moov") ?: return null
            val box = findMetaBox(file, moov) ?: return null
            if (box.size > MAX_META_SIZE) {
                LogCollector.w(TAG, "Ignoring oversized meta box (${box.size} bytes)")
                return null
            }
            // Only mdta tables are transplanted: an iTunes (`mdir`) ilst carries title/artist,
            // which ffmpeg already round-trips through udta, and copying it would duplicate keys.
            if (!isMdta(file, box)) {
                LogCollector.d(TAG, "Source meta box is not mdta, skipping")
                return null
            }

            val raw = ByteArray(box.size.toInt())
            file.seek(box.start)
            file.readFully(raw)
            return raw
        }
    }

    /** `meta` is a FullBox: 4 bytes version/flags precede its children. */
    private fun isMdta(file: RandomAccessFile, meta: Box): Boolean {
        var pos = meta.bodyStart + 4
        while (pos + 8 <= meta.end) {
            val child = readBoxHeader(file, pos, meta.end) ?: return false
            if (child.type == "hdlr") return handlerType(file, child) == "mdta"
            if (child.end <= pos) return false
            pos = child.end
        }
        return false
    }

    /** `hdlr` body: version/flags(4) + pre_defined(4) + handler_type(4). */
    private fun handlerType(file: RandomAccessFile, hdlr: Box): String? {
        if (hdlr.bodyStart + 12 > hdlr.end) return null
        file.seek(hdlr.bodyStart + 8)
        return readFourCC(file)
    }

    // --- writing the destination ---

    private fun appendMetaBox(destPath: String, meta: ByteArray): Boolean {
        RandomAccessFile(destPath, "rw").use { file ->
            val fileSize = file.length()
            val moov = findBox(file, 0, fileSize, "moov")
            if (moov == null) {
                LogCollector.w(TAG, "Destination has no moov box")
                return false
            }
            if (moov.end != fileSize) {
                LogCollector.w(TAG, "moov is not the last box; skipping transplant to keep chunk offsets valid")
                return false
            }
            if (findBox(file, moov.bodyStart, moov.end, "meta") != null) {
                LogCollector.d(TAG, "Destination already has a moov/meta box, skipping")
                return true
            }

            val newSize = moov.size + meta.size
            if (moov.headerSize == 8L && newSize > 0xFFFFFFFFL) {
                LogCollector.w(TAG, "moov would exceed 32-bit box size")
                return false
            }

            file.seek(moov.end)
            file.write(meta)

            file.seek(moov.start)
            if (moov.headerSize == 16L) {
                writeUInt32(file, 1L)
                writeFourCC(file, "moov")
                writeUInt64(file, newSize)
            } else {
                writeUInt32(file, newSize)
                writeFourCC(file, "moov")
            }

            LogCollector.i(TAG, "Transplanted ${meta.size} bytes of mdta metadata into moov")
            return true
        }
    }

    // --- box structure ---

    /**
     * A parsed box header. [typeRaw] keeps the four type bytes as a number as well as a string,
     * because `ilst` children encode their key index — not an ASCII fourcc — in that field.
     */
    private class Box(
        val type: String,
        val typeRaw: Long,
        val start: Long,
        val size: Long,
        val headerSize: Long
    ) {
        val bodyStart: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    /**
     * Walk the children of the container spanning [[start], [end]) and return the first box of
     * [type]. Supports the 32-bit, 64-bit (`size == 1`) and open-ended (`size == 0`) encodings.
     */
    private fun findBox(file: RandomAccessFile, start: Long, end: Long, type: String): Box? {
        var pos = start
        while (pos + 8 <= end) {
            val box = readBoxHeader(file, pos, end) ?: return null
            if (box.type == type) return box
            if (box.end <= pos) return null
            pos = box.end
        }
        return null
    }

    private fun readBoxHeader(file: RandomAccessFile, pos: Long, limit: Long): Box? {
        if (pos < 0 || pos + 8 > limit) return null
        file.seek(pos)
        val size32 = readUInt32(file)
        val typeBytes = ByteArray(4)
        file.readFully(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)
        val typeRaw = ((typeBytes[0].toLong() and 0xFF) shl 24) or
                      ((typeBytes[1].toLong() and 0xFF) shl 16) or
                      ((typeBytes[2].toLong() and 0xFF) shl 8) or
                      (typeBytes[3].toLong() and 0xFF)

        var headerSize = 8L
        val size = when {
            size32 == 1L -> {
                if (pos + 16 > limit) return null
                headerSize = 16L
                readUInt64(file)
            }
            size32 == 0L -> limit - pos
            size32 < 8L -> return null
            else -> size32
        }
        if (size < headerSize || pos + size > limit) return null
        return Box(type, typeRaw, pos, size, headerSize)
    }

    // --- binary I/O helpers ---

    private fun readUInt32(file: RandomAccessFile): Long {
        val b = ByteArray(4)
        file.readFully(b)
        return ((b[0].toLong() and 0xFF) shl 24) or
               ((b[1].toLong() and 0xFF) shl 16) or
               ((b[2].toLong() and 0xFF) shl 8) or
               (b[3].toLong() and 0xFF)
    }

    private fun readUInt64(file: RandomAccessFile): Long {
        val high = readUInt32(file)
        val low = readUInt32(file)
        return (high shl 32) or low
    }

    private fun readFourCC(file: RandomAccessFile): String {
        val b = ByteArray(4)
        file.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun writeFourCC(file: RandomAccessFile, value: String) {
        file.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeUInt32(file: RandomAccessFile, value: Long) {
        file.write(((value ushr 24) and 0xFF).toInt())
        file.write(((value ushr 16) and 0xFF).toInt())
        file.write(((value ushr 8) and 0xFF).toInt())
        file.write((value and 0xFF).toInt())
    }

    private fun writeUInt64(file: RandomAccessFile, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            file.write(((value ushr shift) and 0xFF).toInt())
        }
    }
}
