package com.pisces312.streamclip.audio

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mode D: FFmpeg-based waveform loader.
 *
 * Uses FFmpeg's astats filter to extract per-frame RMS/peak amplitude data,
 * which is then converted to frame gains for WaveformProcessor.
 *
 * Alternative approach: uses volumedetect or showwavespic, but astats gives
 * the most structured numeric output.
 *
 * Fallback: if astats fails, uses a simpler approach with ebur128 or
 * raw PCM piped through a minimal decode.
 */
class FFmpegWaveformLoader {

    companion object {
        private const val TAG = "FFmpegWaveformLoader"
        private const val TARGET_FRAME_MS = 50.0
        private const val MAX_FRAMES = 5000
    }

    data class Result(
        val success: Boolean,
        val frameGains: IntArray,
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Int,
        val errorMessage: String? = null
    )

    /**
     * Load waveform data using FFmpeg.
     *
     * Strategy: Use FFmpeg to decode audio and pipe raw PCM through astats
     * filter with frame metadata output. Parse the stderr/log output to
     * extract per-frame peak amplitudes.
     *
     * If that fails, fallback to a simpler approach: decode to raw PCM
     * at reduced sample rate and compute peaks in Kotlin.
     */
    fun loadWaveform(filePath: String): Result {
        // Step 1: Probe media info
        val probeResult = probeAudio(filePath)
        if (probeResult == null) {
            return Result(false, IntArray(0), 0, 0, 0, "FFprobe failed")
        }

        val (sampleRate, channels, durationMs) = probeResult
        val numFrames = (durationMs / TARGET_FRAME_MS).toInt()
            .coerceIn(1, MAX_FRAMES)

        // Step 2: Try astats approach first
        val astatsResult = tryAStats(filePath, numFrames, sampleRate, channels, durationMs)
        if (astatsResult.success) {
            return astatsResult
        }

        // Step 3: Fallback - decode to low-rate PCM and compute peaks
        Log.w(TAG, "astats approach failed, trying PCM fallback")
        return tryPCMFallback(filePath, numFrames, sampleRate, channels, durationMs)
    }

    private data class ProbeInfo(val sampleRate: Int, val channels: Int, val durationMs: Int)

    private fun probeAudio(filePath: String): ProbeInfo? {
        return try {
            val session = FFprobeKit.execute(
                "-v quiet -print_format json -show_streams -show_format \"$filePath\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) return null

            val json = JSONObject(session.output.trim())
            val streams = json.optJSONArray("streams") ?: return null
            var sampleRate = 44100
            var channels = 2

            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                if (stream.optString("codec_type") == "audio") {
                    sampleRate = stream.optInt("sample_rate", 44100)
                    channels = stream.optInt("channels", 2)
                    break
                }
            }

            val format = json.optJSONObject("format")
            val durationSec = format?.optString("duration", "0")?.toDoubleOrNull() ?: 0.0
            val durationMs = (durationSec * 1000).toInt()

            ProbeInfo(sampleRate, channels, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "probeAudio failed: ${e.message}")
            null
        }
    }

    /**
     * Approach 1: Use FFmpeg astats filter with metadata output.
     * This outputs per-frame peak/RMS values that we can parse.
     */
    private fun tryAStats(
        filePath: String,
        numFrames: Int,
        sampleRate: Int,
        channels: Int,
        durationMs: Int
    ): Result {
        return try {
            // Use astats with frame metadata output
            // Each frame = TARGET_FRAME_MS of audio
            val frameSize = (sampleRate * TARGET_FRAME_MS / 1000).toInt()
            val cmd = "-i \"$filePath\" -af \"astats=metadata=1:reset=${frameSize},ametadata=print:key=lavfi.astats.Overall.Peak_level:file=-\" -f null -"

            val session = FFmpegKit.execute(cmd)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                return Result(false, IntArray(0), sampleRate, channels, durationMs,
                    "FFmpeg astats failed: rc=${session.returnCode}")
            }

            // Parse the output for peak values
            val logs = session.allLogsAsString ?: ""
            val frameGains = parseIntArrayFromAStats(logs, numFrames)

            if (frameGains.isEmpty()) {
                return Result(false, IntArray(0), sampleRate, channels, durationMs,
                    "No peak data parsed from astats output")
            }

            Log.i(TAG, "astats: parsed ${frameGains.size} frames")
            Result(true, frameGains, sampleRate, channels, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "tryAStats failed: ${e.message}")
            Result(false, IntArray(0), sampleRate, channels, durationMs, e.message)
        }
    }

    /**
     * Parse peak amplitude values from astats metadata output.
     * Output format lines like:
     * lavfi.astats.Overall.Peak_level=-12.34
     */
    private fun parseIntArrayFromAStats(logs: String, expectedFrames: Int): IntArray {
        val gains = mutableListOf<Int>()
        val regex = Regex("""lavfi\.astats\.Overall\.Peak_level=([-\d.]+)""")
        var currentFramePeaks = mutableListOf<Double>()

        for (line in logs.lines()) {
            val match = regex.find(line)
            if (match != null) {
                val dbValue = match.groupValues[1].toDoubleOrNull()
                if (dbValue != null && !dbValue.isInfinite() && !dbValue.isNaN()) {
                    // Convert dB to linear amplitude: amp = 10^(dB/20)
                    // Then to 0-32768 scale, then sqrt for gain
                    val amplitude = Math.pow(10.0, dbValue / 20.0) * 32768
                    currentFramePeaks.add(amplitude)
                }
            }

            // Frame boundary: when we see a new frame start or end of output
            if (line.contains("pts_time:") || line.contains("frame:") || currentFramePeaks.size >= 1) {
                if (currentFramePeaks.isNotEmpty()) {
                    val maxAmp = currentFramePeaks.max()
                    gains.add(Math.sqrt(maxAmp).toInt())
                    currentFramePeaks.clear()
                }
            }
        }

        // Handle remaining
        if (currentFramePeaks.isNotEmpty()) {
            val maxAmp = currentFramePeaks.max()
            gains.add(Math.sqrt(maxAmp).toInt())
        }

        return gains.toIntArray()
    }

    /**
     * Approach 2 (fallback): Decode audio to low-sample-rate raw PCM using FFmpeg,
     * then compute frame peaks in Kotlin.
     * This is more reliable than astats parsing but slightly slower.
     */
    private fun tryPCMFallback(
        filePath: String,
        numFrames: Int,
        sampleRate: Int,
        channels: Int,
        durationMs: Int
    ): Result {
        return try {
            // Decode to raw PCM at reduced rate for fast processing
            // Use 8000 Hz mono - enough for waveform visualization
            val targetRate = 8000
            val tempFile = File.createTempFile("ffwaveform_", ".pcm")
            tempFile.deleteOnExit()

            val cmd = "-i \"$filePath\" -ar $targetRate -ac 1 -f s16le -y \"${tempFile.absolutePath}\""

            val session = FFmpegKit.execute(cmd)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                tempFile.delete()
                return Result(false, IntArray(0), sampleRate, channels, durationMs,
                    "FFmpeg PCM decode failed: rc=${session.returnCode}")
            }

            // Read PCM data and compute frame gains
            val pcmData = tempFile.readBytes()
            tempFile.delete()

            if (pcmData.size < 2) {
                return Result(false, IntArray(0), sampleRate, channels, durationMs, "Empty PCM output")
            }

            // Each sample is 2 bytes (16-bit LE)
            val numPCMSamples = pcmData.size / 2
            val samplesPerFrame = numPCMSamples / numFrames.coerceAtLeast(1)

            if (samplesPerFrame <= 0) {
                return Result(false, IntArray(0), sampleRate, channels, durationMs, "Too few samples")
            }

            val frameGains = IntArray(numFrames)
            val byteBuffer = ByteBuffer.wrap(pcmData)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

            for (frameIdx in 0 until numFrames) {
                var maxVal = 0
                val startSample = frameIdx * samplesPerFrame
                val endSample = ((frameIdx + 1) * samplesPerFrame).coerceAtMost(numPCMSamples)

                for (i in startSample until endSample) {
                    val sample = Math.abs(byteBuffer.getShort(i * 2).toInt())
                    if (sample > maxVal) maxVal = sample
                }

                // Scale up from 8000Hz mono to original amplitude range
                // and apply sqrt like the original processor
                frameGains[frameIdx] = Math.sqrt(maxVal.toDouble()).toInt()
            }

            Log.i(TAG, "PCM fallback: ${numFrames} frames from ${numPCMSamples} samples")
            Result(true, frameGains, sampleRate, channels, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "tryPCMFallback failed: ${e.message}")
            Result(false, IntArray(0), sampleRate, channels, durationMs, e.message)
        }
    }
}
