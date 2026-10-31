package com.pisces312.streamclip.service

import com.pisces312.streamclip.model.AudioStreamInfo
import com.pisces312.streamclip.model.MediaInfo
import com.pisces312.streamclip.model.VideoStreamInfo
import com.pisces312.streamclip.util.Mp4MetadataUtils
import org.json.JSONObject

/**
 * Turns the JSON from `ffprobe -print_format json -show_format -show_streams` into [MediaInfo].
 *
 * Split out of [FFmpegService.probeMediaInfo] so the field mapping is testable on the host JVM:
 * FFmpegService itself cannot be loaded there because it drives ffmpeg-kit sessions.
 *
 * The mapping is worth a dedicated test because ffprobe reports a missing field by simply
 * omitting it — a wrong key name therefore yields an empty string instead of an error, and the
 * corresponding row in the info card silently disappears. That is exactly what happened to
 * `color_space` (read as `colorspace` for a long time).
 */
internal object MediaProbeParser {

    /**
     * @param path file the JSON was produced from — re-read for the mdta (`moov/meta`) table,
     *        which ffprobe cannot report in full.
     * @return null when the payload carries no `streams` array, i.e. ffprobe did not really
     *         succeed. A file with neither video nor audio still parses into an empty MediaInfo.
     */
    fun parse(path: String, output: String): MediaInfo? {
        val json = JSONObject(output)

        val format = json.optJSONObject("format")
        val durationMs = format?.optString("duration", "-1")?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() } ?: -1L
        val formatName = format?.optString("format_name", "") ?: ""
        val tags = format?.optJSONObject("tags") ?: JSONObject()

        val streams = json.optJSONArray("streams") ?: return null
        var videoStream: VideoStreamInfo? = null
        var audioStream: AudioStreamInfo? = null

        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            when (s.optString("codec_type", "")) {
                "video" -> if (videoStream == null) {
                    videoStream = VideoStreamInfo(
                        width = s.optInt("width", 0),
                        height = s.optInt("height", 0),
                        codec = s.optString("codec_name", ""),
                        frameRate = s.optString("r_frame_rate", ""),
                        pixelFormat = s.optString("pix_fmt", ""),
                        bitRate = s.optString("bit_rate", "0").toLongOrNull() ?: 0L,
                        rotation = readRotation(s),
                        colorPrimaries = tag(s, "color_primaries"),
                        colorTransfer = tag(s, "color_transfer"),
                        // ffprobe's key is `color_space`. Reading `colorspace` compiles and
                        // silently returns "" — the "色彩空间" row then never renders.
                        colorSpace = tag(s, "color_space")
                    )
                }

                "audio" -> if (audioStream == null) {
                    audioStream = AudioStreamInfo(
                        codec = s.optString("codec_name", ""),
                        sampleRate = s.optInt("sample_rate", 0),
                        bitRate = s.optString("bit_rate", "0").toLongOrNull() ?: 0L,
                        channelLayout = s.optString("channel_layout", "")
                    )
                }
            }
        }

        return MediaInfo(
            path = path,
            durationMs = durationMs,
            formatName = formatName,
            formatTags = tags,
            video = videoStream,
            audio = audioStream,
            mdtaTags = Mp4MetadataUtils.readMdtaTags(path)
        )
    }

    /**
     * ffprobe reports `"unknown"` for a field that exists in the schema but is unset in the file.
     * The UI treats that the same as absent, so both collapse to "".
     */
    private fun tag(stream: JSONObject, key: String): String =
        stream.optString(key, "").takeIf { it.isNotEmpty() && it != "unknown" } ?: ""

    /** Rotation lives in `side_data_list` (tkhd display matrix), not on the stream itself. */
    private fun readRotation(stream: JSONObject): Int {
        val sideData = stream.optJSONArray("side_data_list") ?: return 0
        for (i in 0 until sideData.length()) {
            val sd = sideData.optJSONObject(i) ?: continue
            if (sd.has("rotation")) return sd.optInt("rotation", 0)
        }
        return 0
    }
}
