# Compress Video Info Display Design

**Goal:** Show original and output video metadata (codec, resolution, bitrate, audio info, creation time, GPS location) in the compress tab for user verification.

**Scope:** CompressFragment UI + FFmpegService probe enhancement + VideoInfo model update. Single plan.

---

## Overview

When the user selects a video in the compress tab, a card appears showing the original video's metadata. After compression completes, a second card appears below showing the output video's metadata, allowing the user to verify the compression achieved the desired settings.

---

## Component 1: VideoInfo Model Update

**File:** `app/src/main/java/com/pisces312/streamclip/model/VideoInfo.kt`

Add five fields to the existing `VideoInfo` data class:

```kotlin
data class VideoInfo(
    val path: String,
    val width: Int,
    val height: Int,
    val videoCodec: String,
    val audioCodec: String,
    val frameRate: String,
    val pixelFormat: String,
    val rotation: Int,
    val videoBitrate: Long = 0,      // bits per second
    val audioSampleRate: Int = 0,    // Hz
    val audioBitrate: Long = 0,      // bits per second
    val creationTime: String = "",   // e.g. "2024-03-15 10:30:00"
    val location: String = ""        // e.g. "+121.2345+031.6789/"
)
```

Add display helpers:

```kotlin
val videoBitrateKbps: String get() = if (videoBitrate > 0) "${videoBitrate / 1000}kbps" else "N/A"
val audioSampleRateStr: String get() = if (audioSampleRate > 0) "${audioSampleRate}Hz" else "N/A"
val audioBitrateKbps: String get() = if (audioBitrate > 0) "${audioBitrate / 1000}kbps" else "N/A"
val creationTimeStr: String get() = creationTime.ifEmpty { "N/A" }
val locationStr: String get() = location.ifEmpty { "N/A" }
```

Existing `isCompatibleWith()` and `getIncompatibleFields()` are unchanged (bitrate, creation time, and location are not compatibility fields).

---

## Component 2: FFmpegService Probe Enhancement

**File:** `app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt`

### Modify `probeVideoInfo()` (lines 342-390)

Add bitrate, audio detail, creation time, and location queries to the existing method. The video stream query adds `bit_rate`; the audio stream query adds `sample_rate,bit_rate`; a format query adds `tags` for creation_time; and `probeLocation()` is reused for GPS:

```kotlin
fun probeVideoInfo(inputPath: String): VideoInfo? {
    return try {
        // Video stream: add bit_rate
        val session = FFprobeKit.execute(
            "-v quiet -select_streams v:0 -show_entries stream=width,height,codec_name,r_frame_rate,pix_fmt,bit_rate -of csv=p=0 \"$inputPath\""
        )
        if (!ReturnCode.isSuccess(session.returnCode)) return null
        val output = session.output.trim()
        if (output.isEmpty()) return null
        val parts = output.split(",")
        if (parts.size < 5) return null

        val width = parts[0].trim().toIntOrNull() ?: 0
        val height = parts[1].trim().toIntOrNull() ?: 0
        val videoCodec = parts[2].trim()
        val frameRate = parts[3].trim()
        val pixelFormat = parts[4].trim()
        val videoBitrate = parts.getOrNull(5)?.trim()?.toLongOrNull() ?: 0L

        // Audio stream: codec_name, sample_rate, bit_rate
        val audioSession = FFprobeKit.execute(
            "-v quiet -select_streams a:0 -show_entries stream=codec_name,sample_rate,bit_rate -of csv=p=0 \"$inputPath\""
        )
        val audioCodec: String
        var audioSampleRate = 0
        var audioBitrate = 0L
        if (ReturnCode.isSuccess(audioSession.returnCode)) {
            val audioParts = audioSession.output.trim().split(",")
            audioCodec = audioParts.getOrNull(0)?.trim()?.ifEmpty { "none" } ?: "none"
            audioSampleRate = audioParts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            audioBitrate = audioParts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
        } else {
            audioCodec = "none"
        }

        // Rotation (unchanged)
        val rotationSession = FFprobeKit.execute(
            "-v quiet -select_streams v:0 -show_entries stream_side_data=rotation -of csv=p=0 \"$inputPath\""
        )
        val rotation = if (ReturnCode.isSuccess(rotationSession.returnCode)) {
            rotationSession.output.trim().toIntOrNull() ?: 0
        } else 0

        // Creation time from format tags
        val formatSession = FFprobeKit.execute(
            "-v quiet -show_entries format_tags=creation_time -of csv=p=0 \"$inputPath\""
        )
        val creationTime = if (ReturnCode.isSuccess(formatSession.returnCode)) {
            formatSession.output.trim().ifEmpty { "" }
        } else ""

        // GPS location (reuse existing probeLocation)
        val location = probeLocation(inputPath) ?: ""

        VideoInfo(
            path = inputPath,
            width = width,
            height = height,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            frameRate = frameRate,
            pixelFormat = pixelFormat,
            rotation = rotation,
            videoBitrate = videoBitrate,
            audioSampleRate = audioSampleRate,
            audioBitrate = audioBitrate,
            creationTime = creationTime,
            location = location
        )
    } catch (e: Exception) {
        LogCollector.e("FFmpegService", "Probe video info failed: ${e.message}")
        null
    }
}
```

**Key points:**
- `bit_rate` in ffprobe CSV may be `N/A` for some containers (especially MKV); `toLongOrNull()` returns 0 in that case
- The audio query now gets `sample_rate` and `bit_rate` in addition to `codec_name`
- `creation_time` is probed from `format_tags`; may be empty for files without it
- `location` reuses the existing `probeLocation()` method (GPS coordinates like `+121.2345+031.6789/`)
- All new fields default to empty string or 0 if unavailable

---

## Component 3: Layout XML

**File:** `app/src/main/res/layout/fragment_compress.xml`

### Replace `tvSelectedFile` with two card containers

Remove the `tvSelectedFile` TextView (lines 35-43) and replace with:

```xml
<!-- Original video info card -->
<com.google.android.material.card.MaterialCardView
    android:id="@+id/cardOriginalInfo"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:visibility="gone"
    app:cardElevation="2dp"
    app:cardCornerRadius="8dp"
    style="?attr/materialCardViewOutlinedStyle">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <TextView
            android:id="@+id/tvOriginalInfoTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/original_video_info"
            android:textStyle="bold"
            android:textSize="13sp" />

        <TextView
            android:id="@+id/tvOriginalPath"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:textSize="11sp"
            android:maxLines="1"
            android:ellipsize="middle"
            android:textColor="?android:textColorSecondary" />

        <TextView
            android:id="@+id/tvOriginalVideoInfo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/tvOriginalAudioInfo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/tvOriginalMetaInfo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textSize="11sp"
            android:textColor="?android:textColorSecondary" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>

<!-- Output video info card (hidden until compression completes) -->
<com.google.android.material.card.MaterialCardView
    android:id="@+id/cardOutputInfo"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:visibility="gone"
    app:cardElevation="2dp"
    app:cardCornerRadius="8dp"
    style="?attr/materialCardViewOutlinedStyle">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <TextView
            android:id="@+id/tvOutputInfoTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/output_video_info"
            android:textStyle="bold"
            android:textSize="13sp" />

        <TextView
            android:id="@+id/tvOutputPath"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:textSize="11sp"
            android:maxLines="1"
            android:ellipsize="middle"
            android:textColor="?android:textColorSecondary" />

        <TextView
            android:id="@+id/tvOutputVideoInfo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/tvOutputAudioInfo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/tvOutputMetaInfo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textSize="11sp"
            android:textColor="?android:textColorSecondary" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

### String resources

Add to `app/src/main/res/values/strings.xml`:
```xml
<string name="original_video_info">原视频信息</string>
<string name="output_video_info">输出视频信息</string>
```

Add to `app/src/main/res/values-en/strings.xml`:
```xml
<string name="original_video_info">Original Video Info</string>
<string name="output_video_info">Output Video Info</string>
```

---

## Component 4: CompressFragment Logic

**File:** `app/src/main/java/com/pisces312/streamclip/fragment/CompressFragment.kt`

### After video selection (`handleVideoSelected`, line 319)

Add probe call and populate card:

```kotlin
private fun handleVideoSelected(uri: Uri) {
    val path = FileUtils.getPathFromUri(requireContext(), uri)
    if (path != null) {
        videoPath = path
        // Hide output card when new video is selected
        binding.cardOutputInfo.visibility = View.GONE
        SettingsManager.setLastVideoDir(requireContext(), uri)
        sourceFileTimes = FileUtils.readFileTimes(path)

        // Probe and display original video info
        lifecycleScope.launch(Dispatchers.IO) {
            val info = FFmpegService.probeVideoInfo(path)
            withContext(Dispatchers.Main) {
                if (info != null) {
                    showVideoInfoCard(
                        card = binding.cardOriginalInfo,
                        title = binding.tvOriginalInfoTitle,
                        pathView = binding.tvOriginalPath,
                        videoInfoView = binding.tvOriginalVideoInfo,
                        audioInfoView = binding.tvOriginalAudioInfo,
                        metaInfoView = binding.tvOriginalMetaInfo,
                        info = info
                    )
                }
            }
        }
    } else {
        Toast.makeText(requireContext(), getString(R.string.cannot_get_path), Toast.LENGTH_SHORT).show()
    }
}
```

### After compression success (line 521)

Add probe and display output info:

```kotlin
if (result.success) {
    // ... existing code (scanFile, applyFileTimes, probeLocation) ...

    // Probe and display output video info
    lifecycleScope.launch(Dispatchers.IO) {
        val outputInfo = FFmpegService.probeVideoInfo(outPath)
        withContext(Dispatchers.Main) {
            if (outputInfo != null) {
                showVideoInfoCard(
                    card = binding.cardOutputInfo,
                    title = binding.tvOutputInfoTitle,
                    pathView = binding.tvOutputPath,
                    videoInfoView = binding.tvOutputVideoInfo,
                    audioInfoView = binding.tvOutputAudioInfo,
                    metaInfoView = binding.tvOutputMetaInfo,
                    info = outputInfo
                )
            }
        }
    }

    Toast.makeText(requireContext(), getString(R.string.compress_complete, outFileName), Toast.LENGTH_LONG).show()
}
```

### New helper method

```kotlin
private fun showVideoInfoCard(
    card: View,
    title: TextView,
    pathView: TextView,
    videoInfoView: TextView,
    audioInfoView: TextView,
    metaInfoView: TextView,
    info: VideoInfo
) {
    pathView.text = info.path
    videoInfoView.text = "编码: ${info.videoCodec}  分辨率: ${info.resolution}  视频码率: ${info.videoBitrateKbps}"
    audioInfoView.text = "音频: ${info.audioCodec} ${info.audioSampleRateStr} ${info.audioBitrateKbps}"
    val metaParts = mutableListOf<String>()
    if (info.creationTime.isNotEmpty()) metaParts.add("创建时间: ${info.creationTime}")
    if (info.location.isNotEmpty()) metaParts.add("地理位置: ${info.location}")
    metaInfoView.text = metaParts.joinToString("  ")
    metaInfoView.visibility = if (metaParts.isNotEmpty()) View.VISIBLE else View.GONE
    card.visibility = View.VISIBLE
}
```

### Hide output card on new video selection

When a new video is picked, hide the output card (already shown in `handleVideoSelected` above).

---

## Data Flow

1. **User selects video** → `handleVideoSelected()` → `probeVideoInfo()` on IO thread → populate `cardOriginalInfo`
2. **User configures settings** → spinner selections in `buildConfig()`
3. **User clicks compress** → `executeSingleCompress()` → FFmpeg runs
4. **Compression completes** → `probeVideoInfo(outPath)` on IO thread → populate `cardOutputInfo`
5. **User compares** two cards to verify settings were applied correctly

---

## Error Handling

- If `probeVideoInfo()` returns null (corrupt file, unsupported codec), the card is not shown — silent degradation
- If bitrate is `N/A` in ffprobe output (some MKV files), the field shows "N/A" via the `videoBitrateKbps` computed property
- The output card only appears on successful compression; failed compressions show only a toast
