package com.pisces312.streamclip.util

import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * Utility to set or copy rotation in MP4/MOV files by directly rewriting the video track's
 * tkhd display matrix.
 *
 * FFmpeg 6.0+ (used by ffmpeg-kit 8.1) no longer writes rotation via `-metadata:s:v:0 rotate=N`,
 * and `-display_rotation` is an input-side option that does not propagate to a re-encoded
 * output. So orientation has to be handled by editing the container directly.
 *
 * Layout facts this class relies on:
 *  1. Orientation lives only in the 3x3 display matrix; tkhd width/height must stay at the
 *     coded (unrotated) size. Android's MPEG4Extractor stores tkhd w/h verbatim as
 *     kKeyDisplayWidth/Height, so pre-swapping them makes thumbnail generators scale the
 *     frame into a transposed box (portrait video with a squashed landscape thumbnail).
 *  2. tkhd matrices are not interchangeable across muxers: the 2x2 part is the same for a
 *     given rotation, but the translation terms (tx/ty) differ (e.g. some Android muxers
 *     write tx = 0 where ffmpeg writes tx = height). Copying the source matrix verbatim is
 *     therefore the only way to reproduce the source orientation exactly.
 *
 * Supported formats: MP4, MOV (ISOBMFF), including 64-bit box sizes (size field == 1).
 */
object Mp4RotationUtils {

    private const val TAG = "Mp4RotationUtils"

    /** 1.0 in 16.16 fixed point (matrix elements 0..7). */
    private const val ONE = 0x10000

    /** 1.0 in 2.30 fixed point (matrix element 8). */
    private const val W_ONE = 0x40000000

    /**
     * Set rotation on an MP4/MOV file's video track.
     *
     * @param filePath Path to the MP4/MOV file
     * @param degrees Rotation in degrees (0, 90, 180, 270 clockwise, or -1 to skip)
     * @return true if rotation was applied successfully, false if skipped or failed
     */
    fun setRotation(filePath: String, degrees: Int): Boolean {
        if (degrees < 0) return false
        val normalizedDegrees = ((degrees % 360) + 360) % 360
        if (normalizedDegrees == 0) return true // 0° = identity, no change needed

        return try {
            RandomAccessFile(filePath, "rw").use { file ->
                val track = findVideoTrack(file)
                    ?: throw IllegalStateException("Video tkhd box not found")

                val matrix = buildRotationMatrix(normalizedDegrees, track.width, track.height)
                file.seek(track.matrixOffset)
                for (value in matrix) {
                    writeInt32(file, value)
                }
            }
            true
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to set rotation: ${e.message}")
            false
        }
    }

    /**
     * Copy the rotation (tkhd display matrix) from the source file's video track to the
     * destination file's video track.
     *
     * Why this is needed: FFmpeg 8.1 (ffmpeg-kit) only propagates stream side data
     * (including the display matrix) for stream copy (`-c copy`, streamcopy_init() in
     * ffmpeg_mux_init.c). When video is re-encoded with -noautorotate the output loses the
     * display matrix entirely and plays in raw pixel orientation, so "keep original
     * rotation" must be implemented by copying the source matrix after encoding.
     *
     * The matrix is copied verbatim (all nine 32-bit fields), which sidesteps the sign
     * convention trap between ffprobe's "rotation" value (av_display_rotation_get =
     * -atan2(m[1], m[0]), e.g. -90) and this class's clockwise degrees (the same matrix is
     * "90" here) — normalizing -90 to 270 would produce a matrix rotated 180° from the
     * source. Only the translation terms are rescaled, and only when the destination was
     * encoded at a different size than the source (compression with resolution change).
     *
     * @return true if rotation was copied, or the source has no rotation (nothing to do);
     *         false on failure
     */
    fun copyRotation(sourcePath: String, destPath: String): Boolean {
        val source = try {
            RandomAccessFile(sourcePath, "r").use { file ->
                val track = findVideoTrack(file)
                    ?: throw IllegalStateException("Source video tkhd box not found")
                CopiedRotation(
                    matrix = readMatrix(file, track.matrixOffset),
                    width = track.width,
                    height = track.height
                )
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to copy rotation: ${e.message}")
            return false
        }

        // Identity source = no rotation recorded; a fresh encode is already identity.
        if (isIdentityMatrix(source.matrix)) return true

        return try {
            RandomAccessFile(destPath, "rw").use { file ->
                val track = findVideoTrack(file)
                    ?: throw IllegalStateException("Destination video tkhd box not found")

                val matrix = scaleTranslation(
                    source.matrix, source.width, source.height, track.width, track.height
                )
                file.seek(track.matrixOffset)
                for (value in matrix) {
                    writeInt32(file, value)
                }
            }
            true
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to copy rotation: ${e.message}")
            false
        }
    }

    private class CopiedRotation(val matrix: IntArray, val width: Int, val height: Int)

    // --- Box structure ---

    private class BoxHeader(
        val type: String,
        val start: Long,
        val size: Long,
        val headerSize: Long
    ) {
        val bodyStart: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    private class TrackInfo(
        val matrixOffset: Long,
        val width: Int,
        val height: Int,
        val volume: Int,
        val handler: String?
    ) {
        /** Legacy heuristic used before handler types were available. */
        val looksLikeVideo: Boolean get() = volume == 0 && width > 0 && height > 0
    }

    /**
     * Read a box header at [pos], supporting the three ISO-BMFF size encodings:
     *  - size == 1  : 64-bit `largesize` follows the 4-byte type (header is 16 bytes)
     *  - size == 0  : box extends to [limit]
     *  - otherwise  : 32-bit size (header is 8 bytes)
     *
     * The 64-bit form must be handled *before* rejecting small sizes: a 64-bit box starts
     * with the value 1 in its size field, which looks like an invalid size otherwise. Some
     * Android muxers / repair tools emit `mdat` this way even for modest files.
     */
    private fun readBoxHeader(file: RandomAccessFile, pos: Long, limit: Long): BoxHeader? {
        if (pos < 0 || pos + 8 > limit) return null
        file.seek(pos)
        val size32 = readUInt32(file)
        val type = readFourCC(file)

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
        if (size < headerSize) return null
        if (size < 0 || pos + size > limit) return null
        return BoxHeader(type, pos, size, headerSize)
    }

    /**
     * Locate the video track's tkhd display matrix.
     *
     * Walks top-level boxes to `moov` (skipping `mdat` / `free` without assuming any order),
     * then inspects each `trak`. Video tracks are identified by the `hdlr` handler type
     * `vide`; files without a readable handler fall back to the old heuristic
     * (volume == 0 with non-zero tkhd dimensions).
     */
    private fun findVideoTrack(file: RandomAccessFile): TrackInfo? {
        val fileSize = file.length()
        var pos = 0L
        while (pos + 8 <= fileSize) {
            val box = readBoxHeader(file, pos, fileSize) ?: return null
            if (box.type == "moov") return findVideoTrackInMoov(file, box)
            if (box.end <= pos) return null
            pos = box.end
        }
        return null
    }

    private fun findVideoTrackInMoov(file: RandomAccessFile, moov: BoxHeader): TrackInfo? {
        var fallback: TrackInfo? = null
        var pos = moov.bodyStart
        while (pos + 8 <= moov.end) {
            val box = readBoxHeader(file, pos, moov.end) ?: break
            if (box.type == "trak") {
                val track = readTrak(file, box)
                if (track != null) {
                    if (track.handler == "vide") return track
                    if (fallback == null && track.looksLikeVideo) fallback = track
                }
            }
            if (box.end <= pos) break
            pos = box.end
        }
        return fallback
    }

    private fun readTrak(file: RandomAccessFile, trak: BoxHeader): TrackInfo? {
        var matrixOffset = -1L
        var width = 0
        var height = 0
        var volume = -1
        var handler: String? = null

        var pos = trak.bodyStart
        while (pos + 8 <= trak.end) {
            val box = readBoxHeader(file, pos, trak.end) ?: break
            when (box.type) {
                "tkhd" -> {
                    val tkhd = readTkhd(file, box)
                    if (tkhd != null) {
                        matrixOffset = tkhd.matrixOffset
                        width = tkhd.width
                        height = tkhd.height
                        volume = tkhd.volume
                    }
                }
                "mdia" -> handler = findHandlerType(file, box) ?: handler
            }
            if (box.end <= pos) break
            pos = box.end
        }

        if (matrixOffset < 0) return null
        return TrackInfo(matrixOffset, width, height, volume, handler)
    }

    private class TkhdInfo(val matrixOffset: Long, val width: Int, val height: Int, val volume: Int)

    private fun readTkhd(file: RandomAccessFile, tkhd: BoxHeader): TkhdInfo? {
        val body = tkhd.bodyStart
        file.seek(body)
        val version = file.readByte().toInt() and 0xFF

        // version(1) + flags(3), then creation/modification/trackId/reserved/duration
        // (20 bytes for v0, 32 for v1), reserved(8), layer(2), alternateGroup(2), volume(2),
        // reserved(2) → matrix starts at body + 40 (v0) or body + 52 (v1).
        val volumeOffset: Long
        val matrixOffset: Long
        if (version == 1) {
            volumeOffset = body + 48
            matrixOffset = body + 52
        } else {
            volumeOffset = body + 36
            matrixOffset = body + 40
        }

        if (matrixOffset + 36 > tkhd.end) return null

        file.seek(volumeOffset)
        val volume = readUInt16(file)

        // tkhd width/height are 16.16 fixed point at matrixOffset + 36
        file.seek(matrixOffset + 36)
        val width = (readUInt32(file) shr 16).toInt()
        val height = (readUInt32(file) shr 16).toInt()

        return TkhdInfo(matrixOffset, width, height, volume)
    }

    /** Handler type ('vide', 'soun', ...) inside mdia > hdlr. */
    private fun findHandlerType(file: RandomAccessFile, mdia: BoxHeader): String? {
        var pos = mdia.bodyStart
        while (pos + 8 <= mdia.end) {
            val box = readBoxHeader(file, pos, mdia.end) ?: break
            if (box.type == "hdlr") {
                // version(1) + flags(3) + preDefined(4) + handlerType(4)
                if (box.bodyStart + 12 > box.end) return null
                file.seek(box.bodyStart + 8)
                return readFourCC(file)
            }
            if (box.end <= pos) break
            pos = box.end
        }
        return null
    }

    private fun readMatrix(file: RandomAccessFile, offset: Long): IntArray {
        file.seek(offset)
        return IntArray(9) { readUInt32(file).toInt() }
    }

    private fun isIdentityMatrix(m: IntArray): Boolean {
        return m[0] == ONE && m[1] == 0 && m[3] == 0 && m[4] == ONE && m[6] == 0 && m[7] == 0
    }

    /**
     * Rescale the translation terms when the destination was encoded at a different size
     * than the source. For 90°/270° the destination X axis maps to the source Y axis (and
     * vice versa), so the scale factors are swapped relative to the 0°/180° case.
     */
    private fun scaleTranslation(
        m: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        destWidth: Int,
        destHeight: Int
    ): IntArray {
        val out = m.copyOf()
        if (sourceWidth <= 0 || sourceHeight <= 0 || destWidth <= 0 || destHeight <= 0) return out
        if (sourceWidth == destWidth && sourceHeight == destHeight) return out

        val scaleX = destWidth.toDouble() / sourceWidth
        val scaleY = destHeight.toDouble() / sourceHeight
        val quarterTurn = m[0] == 0 && m[4] == 0
        val txScale = if (quarterTurn) scaleY else scaleX
        val tyScale = if (quarterTurn) scaleX else scaleY

        out[6] = (m[6] * txScale).roundToInt()
        out[7] = (m[7] * tyScale).roundToInt()
        return out
    }

    /**
     * Build a 3x3 display matrix for the given clockwise rotation, in 16.16 fixed point
     * (except the last element, which is 2.30).
     *
     * [width]/[height] are the coded (unrotated) frame dimensions; they only feed the
     * translation terms. tkhd width/height are deliberately left untouched by [setRotation]
     * — see the class comment.
     */
    private fun buildRotationMatrix(degrees: Int, width: Int, height: Int): IntArray {
        return when (degrees) {
            90 -> intArrayOf(
                // 90° CW: | 0  1  0 |    | -1  0  0 |    | h  0  1 |
                0, ONE, 0, -ONE, 0, 0, height shl 16, 0, W_ONE
            )
            180 -> intArrayOf(
                // 180°: | -1  0  0 |    | 0  -1  0 |    | w  h  1 |
                -ONE, 0, 0, 0, -ONE, 0, width shl 16, height shl 16, W_ONE
            )
            270 -> intArrayOf(
                // 270° CW: | 0  -1  0 |    | 1  0  0 |    | 0  w  1 |
                0, -ONE, 0, ONE, 0, 0, 0, width shl 16, W_ONE
            )
            else -> intArrayOf(
                ONE, 0, 0, 0, ONE, 0, 0, 0, W_ONE
            )
        }
    }

    // --- Binary I/O helpers ---

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

    private fun readUInt16(file: RandomAccessFile): Int {
        val b = ByteArray(2)
        file.readFully(b)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    private fun readFourCC(file: RandomAccessFile): String {
        val b = ByteArray(4)
        file.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun writeInt32(file: RandomAccessFile, value: Int) {
        file.write((value ushr 24) and 0xFF)
        file.write((value ushr 16) and 0xFF)
        file.write((value ushr 8) and 0xFF)
        file.write(value and 0xFF)
    }
}
