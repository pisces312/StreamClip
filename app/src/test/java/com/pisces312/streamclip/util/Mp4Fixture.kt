package com.pisces312.streamclip.util

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Minimal ISO-BMFF writer used to synthesise MP4 fixtures in memory.
 *
 * [Mp4RotationUtils] only ever reads box headers, `tkhd` bytes and `hdlr` handler types, so
 * fixtures do not need real media payloads — the `mdat` body is filler. What *does* matter is
 * container shape, and every variant here mirrors a real recording that broke rotation:
 *
 *  - `moov` before `mdat` with a 32-bit `mdat` (WeChat export, 4K, `tx = height`)
 *  - `mdat` (64-bit size field) before `moov`, audio `trak` first (camera app, 720p, `tx = 0`)
 *
 * Fidelity against the originals is pinned by `Mp4RotationUtilsTest#builder_reproduces_real_tkhd_bytes`,
 * which compares [tkhd] output byte-for-byte with `tkhd` boxes captured from those two files.
 */
internal object Mp4Fixture {

    /** 1.0 in 16.16 fixed point (matrix elements 0..7). */
    const val ONE = 0x10000

    /** 1.0 in 2.30 fixed point (matrix element 8). */
    const val W_ONE = 0x40000000

    /** Audio track volume: 1.0 in 8.8 fixed point. */
    const val AUDIO_VOLUME = 0x0100

    // --- display matrices ---

    fun identity(): IntArray = intArrayOf(ONE, 0, 0, 0, ONE, 0, 0, 0, W_ONE)

    /**
     * 90° clockwise. This is the matrix behind ffprobe's `rotation: -90`, i.e. what both real
     * fixtures carry. `tx` is deliberately a parameter: some muxers write `tx = height`,
     * others write `tx = 0`, and the production code must copy it verbatim rather than
     * recompute it.
     */
    fun quarterTurn(tx: Int = 0): IntArray = intArrayOf(0, ONE, 0, -ONE, 0, 0, tx, 0, W_ONE)

    fun halfTurn(width: Int, height: Int): IntArray =
        intArrayOf(-ONE, 0, 0, 0, -ONE, 0, width shl 16, height shl 16, W_ONE)

    fun threeQuarterTurn(width: Int): IntArray =
        intArrayOf(0, -ONE, 0, ONE, 0, 0, 0, width shl 16, W_ONE)

    // --- boxes ---

    fun ftyp(): ByteArray = box("ftyp", ascii("isom"), u32(0x200), ascii("isomiso2mp41"))

    fun free(): ByteArray = box("free")

    /** `mdat` with a 32-bit size field (faststart layout, `moov` first). */
    fun mdat(payloadSize: Int = 32): ByteArray = box("mdat", ByteArray(payloadSize))

    /** `mdat` with the 64-bit form (size field == 1, `largesize` follows) — the regression trigger. */
    fun largeMdat(payloadSize: Int = 32): ByteArray = largeBox("mdat", ByteArray(payloadSize))

    /** `mdat` with size == 0: "extends to end of file", only legal as the last box. */
    fun openEndedMdat(payloadSize: Int = 32): ByteArray = u32(0) + ascii("mdat") + ByteArray(payloadSize)

    fun moov(vararg children: ByteArray): ByteArray = box("moov", mvhd(), *children)

    /**
     * `moov` using the 64-bit size form (`size == 1` + `largesize`). Appending to such a moov
     * must rewrite the 64-bit field, not the 32-bit one — a branch `appendMetaBox` has to get
     * right, and one filenames above 4 GiB actually take.
     */
    fun largeMoov(vararg children: ByteArray): ByteArray = largeBox("moov", mvhd(), *children)

    fun trak(handler: String, tkhd: ByteArray): ByteArray =
        box("trak", tkhd, box("mdia", hdlr(handler)))

    /** Bytes of fixed fields before the first child of a VisualSampleEntry (see ISO/IEC 14496-12). */
    const val VISUAL_SAMPLE_ENTRY_HEADER = 78

    /** AudioSampleEntry fixed fields: 8 (reserved + data ref) + 20. */
    const val AUDIO_SAMPLE_ENTRY_HEADER = 28

    /**
     * Video `trak` with a real `stsd` → sample entry chain, which is where `colr` lives.
     *
     * ffmpeg writes `colr` (and `hvcC`) as children of the entry, after
     * [VISUAL_SAMPLE_ENTRY_HEADER] bytes of fixed fields — the offset the patcher must use.
     */
    fun videoTrak(sampleEntry: String = "hvc1", colr: ByteArray? = null): ByteArray {
        val entry = box(
            sampleEntry,
            ByteArray(VISUAL_SAMPLE_ENTRY_HEADER),
            *listOfNotNull(colr).toTypedArray()
        )
        val stsd = box("stsd", ByteArray(4), u32(1), entry) // FullBox: version/flags + entry_count
        return box("trak", tkhd(1, 0, identity(), 1280, 720), box("mdia", hdlr("vide"), box("minf", box("stbl", stsd))))
    }

    /** Audio `trak`; carries its own `stsd`, so a handler-blind walk could patch the wrong track. */
    fun audioTrak(colr: ByteArray? = null): ByteArray {
        val entry = box(
            "mp4a",
            ByteArray(AUDIO_SAMPLE_ENTRY_HEADER),
            *listOfNotNull(colr).toTypedArray()
        )
        val stsd = box("stsd", ByteArray(4), u32(1), entry)
        return box("trak", tkhd(2, 0, identity(), 0, 0, volume = AUDIO_VOLUME), box("mdia", hdlr("soun"), box("minf", box("stbl", stsd))))
    }

    /** `colr` in the `nclx` form: 16-bit primaries / transfer / matrix plus a full-range flag. */
    fun colrNclx(primaries: Int, transfer: Int, matrix: Int, fullRange: Boolean): ByteArray = box(
        "colr",
        ascii("nclx"),
        u16(primaries),
        u16(transfer),
        u16(matrix),
        byteArrayOf(if (fullRange) 0x80.toByte() else 0x00)
    )

    /** `colr` in the older `nclc` form ffmpeg writes for `-f mov`: no range byte. */
    fun colrNclc(primaries: Int, transfer: Int, matrix: Int): ByteArray = box(
        "colr",
        ascii("nclc"),
        u16(primaries),
        u16(transfer),
        u16(matrix)
    )

    /**
     * `udta` container as written by Android camera apps; carries the `©xyz` GPS atom that
     * Android's MediaMetadataRetriever reads.
     */
    fun udta(vararg children: ByteArray): ByteArray = box("udta", *children)

    /** `©xyz` GPS atom: 2-byte length (string + NUL), then the ISO 6709 string and its NUL. */
    fun xyz(location: String): ByteArray {
        val payload = ascii(location) + byteArrayOf(0)
        return box("\u00a9xyz", u16(payload.size), payload)
    }

    /**
     * One entry of an mdta `ilst`: the key it points at plus the `data` box payload.
     *
     * [wellKnownType] is the QuickTime `data` type actually written by camera apps — 1 UTF-8 text,
     * 2 UTF-16BE text, 21/22 signed/unsigned integer, 23 float, 24 double, anything else opaque.
     */
    class MdtaEntry(val key: String, val wellKnownType: Int, val payload: ByteArray) {
        companion object {
            /** Type 1, NUL-terminated like Apple/HONOR write it. */
            fun text(key: String, value: String) =
                MdtaEntry(key, 1, value.toByteArray(Charsets.UTF_8) + byteArrayOf(0))

            fun utf16(key: String, value: String) =
                MdtaEntry(key, 2, value.toByteArray(Charsets.UTF_16BE) + byteArrayOf(0, 0))

            fun int(key: String, value: Int) =
                MdtaEntry(key, 21, java.nio.ByteBuffer.allocate(4).putInt(value).array())

            fun unsigned(key: String, value: Int) =
                MdtaEntry(key, 22, java.nio.ByteBuffer.allocate(4).putInt(value).array())

            fun float(key: String, value: Float) =
                MdtaEntry(key, 23, java.nio.ByteBuffer.allocate(4).putFloat(value).array())

            fun binary(key: String, bytes: ByteArray) = MdtaEntry(key, 0, bytes)
        }
    }

    /**
     * `meta` box in mdta form (`hdlr` handler type `mdta` + `keys` table + `ilst` index).
     *
     * This is where HONOR (`com.honor.lens.model`, `com.honor.product.model`) and Apple
     * (`com.apple.quicktime.make/model/lens_model`) keep camera and lens metadata — keys the mov
     * muxer's fixed `udta` whitelist cannot express.
     */
    fun metaMdta(vararg entries: Pair<String, String>): ByteArray =
        metaMdtaEntries(entries.map { MdtaEntry(it.first, 1, ascii(it.second)) })

    fun metaMdta(vararg entries: MdtaEntry): ByteArray = metaMdtaEntries(entries.toList())

    /**
     * @param indices key index written into each `ilst` child; defaults to 1-based order.
     *        Pass values out of range to model a table whose index and key list disagree.
     */
    fun metaMdtaEntries(
        entries: List<MdtaEntry>,
        indices: List<Int> = entries.indices.map { it + 1 }
    ): ByteArray {
        val ilstBody = ByteArrayOutputStream().apply {
            entries.forEachIndexed { index, entry ->
                val data = box("data", u32(entry.wellKnownType), u32(0), entry.payload)
                write(u32(8 + data.size))
                write(u32(indices[index])) // 1-based key index, not an ASCII fourcc
                write(data)
            }
        }.toByteArray()

        return metaWith(keysBody(entries.map { it.key }), box("ilst", ilstBody))
    }

    /** mdta `meta` carrying a `keys` table but no `ilst` — nothing to resolve, nothing to show. */
    fun metaKeysOnly(vararg keys: String): ByteArray = metaWith(keysBody(keys.toList()), null)

    private fun metaWith(keys: ByteArray, ilst: ByteArray?): ByteArray =
        if (ilst == null) box("meta", ByteArray(4), hdlr("mdta"), box("keys", keys))
        else box("meta", ByteArray(4), hdlr("mdta"), box("keys", keys), ilst)

    private fun keysBody(keys: List<String>): ByteArray = ByteArrayOutputStream().apply {
        write(ByteArray(4)) // version/flags
        write(u32(keys.size))
        keys.forEach { key ->
            val name = ascii(key)
            write(u32(name.size + 8))
            write(ascii("mdta"))
            write(name)
        }
    }.toByteArray()

    /** `meta` box in iTunes form (`hdlr` handler type `mdir`) — must not be transplanted. */
    fun metaItunes(title: String): ByteArray = box(
        "meta",
        byteArrayOf(0, 0, 0, 0),
        hdlr("mdir"),
        box("ilst", box("\u00a9nam", box("data", u32(1), u32(0), ascii(title))))
    )

    fun hdlr(handler: String): ByteArray =
        box("hdlr", byteArrayOf(0, 0, 0, 0) + ByteArray(4) + ascii(handler) + ByteArray(13))

    /**
     * `tkhd` box, laid out exactly like ffmpeg/Android muxers do.
     *
     * v0: matrix starts at body + 40, box is 92 bytes; v1 (64-bit timestamps) shifts both by
     * 12 bytes, so matrix starts at body + 52 and the box is 104 bytes.
     */
    fun tkhd(
        trackId: Int,
        duration: Int,
        matrix: IntArray,
        width: Int,
        height: Int,
        volume: Int = 0,
        version: Int = 0
    ): ByteArray {
        require(matrix.size == 9) { "tkhd matrix must have 9 elements" }

        val body = ByteArrayOutputStream()
        body.write(version)
        body.write(u24(0x000003)) // flags: enabled | in movie
        if (version == 1) {
            body.write(u64(0L))
            body.write(u64(0L))
            body.write(u32(trackId))
            body.write(u32(0))
            body.write(u64(duration.toLong()))
        } else {
            body.write(u32(0))
            body.write(u32(0))
            body.write(u32(trackId))
            body.write(u32(0))
            body.write(u32(duration))
        }
        body.write(ByteArray(8)) // reserved
        body.write(u16(0)) // layer
        body.write(u16(0)) // alternate group
        body.write(u16(volume))
        body.write(u16(0)) // reserved
        matrix.forEach { body.write(i32(it)) }
        body.write(u32(width shl 16))
        body.write(u32(height shl 16))

        return box("tkhd", body.toByteArray())
    }

    // --- assembly ---

    fun bytes(vararg boxes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        boxes.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun write(target: File, vararg boxes: ByteArray): File {
        target.parentFile?.mkdirs()
        target.writeBytes(bytes(*boxes))
        return target
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // --- primitives ---

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = bytes(*parts)
        return u32(8 + body.size) + ascii(type) + body
    }

    private fun largeBox(type: String, vararg parts: ByteArray): ByteArray {
        val body = bytes(*parts)
        return u32(1) + ascii(type) + u64(16L + body.size) + body
    }

    private fun mvhd(): ByteArray = box("mvhd", byteArrayOf(0, 0, 0, 0) + ByteArray(96))

    // ISO-8859-1 (not US-ASCII) so atom types that use the 0xA9 prefix — ©xyz, ©nam, ©mak —
    // round-trip as the single byte muxers actually write instead of becoming '?'.
    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)

    private fun u16(value: Int): ByteArray =
        byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u24(value: Int): ByteArray =
        byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun i32(value: Int): ByteArray = u32(value) // two's complement: same bits

    private fun u64(value: Long): ByteArray = ByteArray(8) { index ->
        (value ushr (56 - 8 * index)).toByte()
    }
}
