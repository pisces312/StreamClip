package com.pisces312.streamclip.util

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility to set rotation in MP4/MOV files by directly modifying the tkhd box's display matrix.
 *
 * FFmpeg 6.0+ (used by ffmpeg-kit 8.1) no longer writes rotation via `-metadata:s:v:0 rotate=N`.
 * The `-display_rotation` option is an input-side option that doesn't propagate to output with `-c copy`.
 * The only reliable way to set rotation without re-encoding is to modify the tkhd box directly.
 *
 * This works by:
 * 1. Parsing the MP4 box structure to find the video track's tkhd box
 * 2. Writing the appropriate 3x3 display matrix (16.16 fixed point)
 * 3. Leaving tkhd width/height at the coded (unrotated) size — orientation lives only
 *    in the matrix, matching what Apple/iPhone and ffmpeg's own muxer produce
 *
 * Supported formats: MP4, MOV (ISOBMFF)
 * Supported rotations: 0°, 90° CW, 180°, 270° CW
 */
object Mp4RotationUtils {

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
            val raf = RandomAccessFile(filePath, "rw")
            raf.use { file ->
                val videoTkhdOffset = findVideoTkhdOffset(file)
                    ?: throw IllegalStateException("Video tkhd box not found")

                // Coded (unrotated) frame size from tkhd; only used to compute the
                // matrix translation so the rotated frame lands in the positive quadrant.
                val whOffset = videoTkhdOffset + 36
                file.seek(whOffset.toLong())
                val width = (readUInt32(file) shr 16).toInt()
                val height = (readUInt32(file) shr 16).toInt()

                val matrix = buildRotationMatrix(normalizedDegrees, width, height)

                // Write matrix (starts at videoTkhdOffset). tkhd width/height are left
                // untouched on purpose — see buildRotationMatrix().
                file.seek(videoTkhdOffset.toLong())
                for (value in matrix) {
                    writeInt32(file, value)
                }
            }
            true
        } catch (e: Exception) {
            LogCollector.e("Mp4RotationUtils", "Failed to set rotation: ${e.message}")
            false
        }
    }

    /**
     * Copy the rotation (tkhd display matrix) from the source file's video track to the
     * dest file's video track, preserving display orientation exactly.
     *
     * Why this is needed: FFmpeg 8.1 (ffmpeg-kit) only propagates stream side data
     * (including the display matrix) for stream copy (`-c copy`, streamcopy_init() in
     * ffmpeg_mux_init.c). When video is re-encoded with -noautorotate, the output loses
     * the display matrix entirely and plays in raw pixel orientation. So "keep original
     * rotation" must be implemented by copying the source rotation after encoding.
     *
     * The source matrix 2x2 part is matched against the known axis-aligned rotation
     * patterns of [buildRotationMatrix]; the matched pattern is rewritten into dest using
     * dest's own coded dimensions for the translation terms (tkhd width/height untouched).
     * This byte-pattern matching avoids the sign-convention trap between ffprobe's
     * "rotation" value (av_display_rotation_get = -atan2(m[1], m[0]), e.g. -90) and this
     * class's clockwise degrees (the same matrix is "90" here) — normalizing -90 to 270
     * would produce a matrix rotated 180° from the source.
     *
     * @return true if rotation was copied, or the source has no rotation (nothing to do);
     *         false on failure or unsupported source matrix (flip / arbitrary angle)
     */
    fun copyRotation(sourcePath: String, destPath: String): Boolean {
        return try {
            val srcDegrees = RandomAccessFile(sourcePath, "r").use { file ->
                val matrixOffset = findVideoTkhdOffset(file)
                    ?: throw IllegalStateException("Source video tkhd box not found")
                file.seek(matrixOffset.toLong())
                val matrix = IntArray(9) { readUInt32(file).toInt() }
                classifyRotation(matrix)
                    ?: throw IllegalStateException("Unsupported source display matrix: ${matrix.joinToString()}")
            }
            if (srcDegrees == 0) return true // source has no rotation; fresh encode is already identity
            setRotation(destPath, srcDegrees)
        } catch (e: Exception) {
            LogCollector.e("Mp4RotationUtils", "Failed to copy rotation: ${e.message}")
            false
        }
    }

    /**
     * Match a 3x3 display matrix (16.16 fixed point, [a,b,u, c,d,v, tx,ty,w] layout)
     * against the four axis-aligned rotation patterns used by [buildRotationMatrix].
     *
     * @return 0/90/180/270 in this class's convention, or null if the matrix is not
     *         axis-aligned (flip or arbitrary angle) and cannot be reproduced
     */
    private fun classifyRotation(m: IntArray): Int? {
        val one = 0x10000
        val a = m[0]; val b = m[1]; val c = m[3]; val d = m[4]
        return when {
            a == one && d == one && b == 0 && c == 0 -> 0
            a == -one && d == -one && b == 0 && c == 0 -> 180
            a == 0 && d == 0 && b == one && c == -one -> 90
            a == 0 && d == 0 && b == -one && c == one -> 270
            else -> null
        }
    }

    /**
     * Find the offset of the display matrix in the video track's tkhd box.
     * Returns the byte offset of the first matrix element, or null if not found.
     */
    private fun findVideoTkhdOffset(file: RandomAccessFile): Long? {
        val fileSize = file.length()
        var pos = 0L

        // Parse top-level boxes to find moov
        while (pos + 8 <= fileSize) {
            file.seek(pos)
            val size = readUInt32(file)
            val type = readFourCC(file)
            if (size < 8) break

            val actualSize: Long
            val headerSize: Long
            if (size == 1L) {
                // 64-bit extended size
                actualSize = readUInt64(file)
                headerSize = 16
            } else if (size == 0L) {
                actualSize = fileSize - pos
                headerSize = 8
            } else {
                actualSize = size
                headerSize = 8
            }

            if (type == "moov") {
                val moovEnd = pos + actualSize
                val matrixOffset = findVideoTkhdInMoov(file, pos + headerSize, moovEnd)
                if (matrixOffset != null) return matrixOffset
            }

            pos += actualSize
        }
        return null
    }

    /**
     * Search moov children for trak > tkhd, find the video track (volume == 0, width > 0, height > 0).
     * Returns the absolute offset of the display matrix start.
     */
    private fun findVideoTkhdInMoov(file: RandomAccessFile, moovStart: Long, moovEnd: Long): Long? {
        var pos = moovStart
        while (pos + 8 <= moovEnd) {
            file.seek(pos)
            val size = readUInt32(file)
            val type = readFourCC(file)
            if (size < 8 || pos + size > moovEnd) break

            if (type == "trak") {
                val trakEnd = pos + size
                val result = findVideoTkhdInTrak(file, pos + 8, trakEnd)
                if (result != null) return result
            }

            pos += size
        }
        return null
    }

    /**
     * Search trak children for tkhd, return matrix offset if this is the video track.
     */
    private fun findVideoTkhdInTrak(file: RandomAccessFile, trakStart: Long, trakEnd: Long): Long? {
        var pos = trakStart
        while (pos + 8 <= trakEnd) {
            file.seek(pos)
            val size = readUInt32(file)
            val type = readFourCC(file)
            if (size < 8 || pos + size > trakEnd) break

            if (type == "tkhd") {
                val tkhdBodyStart = pos + 8
                file.seek(tkhdBodyStart)
                val version = file.readByte().toInt() and 0xFF

                // Skip version(1) + flags(3)
                file.seek(tkhdBodyStart + 4)

                if (version == 0) {
                    // creation(4) + modification(4) + track_id(4) + reserved(4) + duration(4) = 20
                    // + reserved(8) = 28
                    // + layer(2) + alt_group(2) = 32
                    // + volume(2) + reserved(2) = 36
                    // matrix starts at offset 40 from tkhd body
                    file.skipBytes(32)
                    val volume = readUInt16(file) // volume at offset 36
                    file.skipBytes(2) // reserved (offset 38-39)

                    val matrixOffset = tkhdBodyStart + 40

                    // Read width/height to verify this is a video track
                    file.seek(matrixOffset + 36)
                    val width = (readUInt32(file) shr 16).toInt()
                    val height = (readUInt32(file) shr 16).toInt()

                    if (volume == 0 && width > 0 && height > 0) {
                        return matrixOffset
                    }
                } else if (version == 1) {
                    // creation(8) + modification(8) + track_id(4) + reserved(4) + duration(8) = 32
                    // + reserved(8) = 40
                    // + layer(2) + alt_group(2) = 44
                    // + volume(2) + reserved(2) = 48
                    // matrix starts at offset 52 from tkhd body
                    file.skipBytes(44)
                    val volume = readUInt16(file) // volume at offset 48
                    file.skipBytes(2) // reserved (offset 50-51)

                    val matrixOffset = tkhdBodyStart + 52

                    file.seek(matrixOffset + 36)
                    val width = (readUInt32(file) shr 16).toInt()
                    val height = (readUInt32(file) shr 16).toInt()

                    if (volume == 0 && width > 0 && height > 0) {
                        return matrixOffset
                    }
                }
            }

            pos += size
        }
        return null
    }

    /**
     * Build a 3x3 display matrix for the given rotation, in 16.16 fixed point
     * (except the last element, which is 2.30).
     *
     * [width]/[height] are the coded (unrotated) frame dimensions; they only feed the
     * translation terms.
     *
     * IMPORTANT: tkhd width/height must NOT be swapped for 90°/270°. Reference files
     * (iPhone recordings, ffmpeg's own muxer) keep tkhd w/h at the coded size and carry
     * orientation purely in this matrix. Android's MPEG4Extractor stores tkhd w/h as
     * kKeyDisplayWidth/Height without transposing them, so a pre-swapped value makes
     * thumbnail generators scale the decoded frame into a transposed box — a portrait
     * video ends up with a stretched, landscape-looking thumbnail.
     */
    private fun buildRotationMatrix(degrees: Int, width: Int, height: Int): IntArray {
        return when (degrees) {
            90 -> intArrayOf(
                // 90° CW: | 0  1  0 |    | -1  0  0 |    | h  0  1 |
                0, 0x10000, 0, -0x10000, 0, 0, height shl 16, 0, 0x40000000
            )
            180 -> intArrayOf(
                // 180°: | -1  0  0 |    | 0  -1  0 |    | w  h  1 |
                -0x10000, 0, 0, 0, -0x10000, 0, width shl 16, height shl 16, 0x40000000
            )
            270 -> intArrayOf(
                // 270° CW: | 0  -1  0 |    | 1  0  0 |    | 0  w  1 |
                0, -0x10000, 0, 0x10000, 0, 0, 0, width shl 16, 0x40000000
            )
            else -> intArrayOf(
                0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000
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
