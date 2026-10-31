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

    fun trak(handler: String, tkhd: ByteArray): ByteArray =
        box("trak", tkhd, box("mdia", hdlr(handler)))

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

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

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
