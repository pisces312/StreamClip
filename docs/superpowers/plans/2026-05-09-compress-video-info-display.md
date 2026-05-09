# Compress Video Info Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show original and output video metadata (codec, resolution, bitrate, audio info, creation time, GPS location) in the compress tab via two MaterialCardView cards.

**Architecture:** Update VideoInfo model with new fields, enhance probeVideoInfo() to probe bitrate/audio details/creation time/location, replace tvSelectedFile with two card layouts in XML, and add probe-then-display logic in CompressFragment.

**Tech Stack:** Kotlin, FFmpeg-kit, Material Design 3

---

### Task 1: Update VideoInfo model

**Files:**
- Modify: `app/src/main/java/com/pisces312/streamclip/model/VideoInfo.kt`

- [ ] **Step 1: Add new fields and display helpers to VideoInfo**

Replace the entire file content:

```kotlin
package com.pisces312.streamclip.model

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
) {
    val resolution: String get() = "${width}x${height}"
    val videoBitrateKbps: String get() = if (videoBitrate > 0) "${videoBitrate / 1000}kbps" else "N/A"
    val audioSampleRateStr: String get() = if (audioSampleRate > 0) "${audioSampleRate}Hz" else "N/A"
    val audioBitrateKbps: String get() = if (audioBitrate > 0) "${audioBitrate / 1000}kbps" else "N/A"
    val creationTimeStr: String get() = creationTime.ifEmpty { "N/A" }
    val locationStr: String get() = location.ifEmpty { "N/A" }

    fun isCompatibleWith(other: VideoInfo): Boolean {
        return width == other.width &&
                height == other.height &&
                videoCodec == other.videoCodec &&
                audioCodec == other.audioCodec &&
                frameRate == other.frameRate &&
                pixelFormat == other.pixelFormat &&
                rotation == other.rotation
    }

    fun getIncompatibleFields(other: VideoInfo): List<String> {
        val fields = mutableListOf<String>()
        if (width != other.width || height != other.height) fields.add("分辨率")
        if (videoCodec != other.videoCodec) fields.add("视频编码")
        if (audioCodec != other.audioCodec) fields.add("音频编码")
        if (frameRate != other.frameRate) fields.add("帧率")
        if (pixelFormat != other.pixelFormat) fields.add("像素格式")
        if (rotation != other.rotation) fields.add("旋转方向")
        return fields
    }
}
```

- [ ] **Step 2: Build verification**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pisces312/streamclip/model/VideoInfo.kt
git commit -m "feat: add bitrate, creation time, and location fields to VideoInfo

Add videoBitrate, audioSampleRate, audioBitrate, creationTime, and
location fields with display helper properties for the compress
video info display feature."
```

---

### Task 2: Enhance probeVideoInfo() in FFmpegService

**Files:**
- Modify: `app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt:342-390`

- [ ] **Step 1: Replace probeVideoInfo() with enhanced version**

Replace the entire `probeVideoInfo()` method (lines 342-390) with:

```kotlin
    fun probeVideoInfo(inputPath: String): com.pisces312.streamclip.model.VideoInfo? {
        return try {
            // Video stream: width, height, codec, frame rate, pixel format, bitrate
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

            // Audio stream: codec, sample rate, bitrate
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

            // Rotation from side_data
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

            com.pisces312.streamclip.model.VideoInfo(
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

- [ ] **Step 2: Build verification**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt
git commit -m "feat: enhance probeVideoInfo with bitrate, creation time, and location

Add video bitrate, audio sample rate/bitrate, creation time, and GPS
location probing to probeVideoInfo(). Reuses existing probeLocation()
for GPS coordinates."
```

---

### Task 3: Update layout XML and string resources

**Files:**
- Modify: `app/src/main/res/layout/fragment_compress.xml:35-43`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Add string resources**

Add to `app/src/main/res/values/strings.xml` (before the closing `</resources>` tag):

```xml
    <string name="original_video_info">原视频信息</string>
    <string name="output_video_info">输出视频信息</string>
```

Add to `app/src/main/res/values-en/strings.xml` (before the closing `</resources>` tag):

```xml
    <string name="original_video_info">Original Video Info</string>
    <string name="output_video_info">Output Video Info</string>
```

- [ ] **Step 2: Replace tvSelectedFile with two card containers in fragment_compress.xml**

Remove the `tvSelectedFile` TextView (lines 35-43):

```xml
        <TextView
            android:id="@+id/tvSelectedFile"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/batch_no_files_selected"
            android:textSize="12sp"
            android:maxLines="2"
            android:ellipsize="end" />
```

Replace with the two card containers:

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

- [ ] **Step 3: Build verification**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/fragment_compress.xml app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml
git commit -m "feat: add video info card layouts to compress tab

Replace tvSelectedFile with two MaterialCardView containers for
original and output video info display. Each card shows path, video
codec, resolution, bitrate, audio info, creation time, and location."
```

---

### Task 4: Add probe-then-display logic in CompressFragment

**Files:**
- Modify: `app/src/main/java/com/pisces312/streamclip/fragment/CompressFragment.kt:319-331` (handleVideoSelected)
- Modify: `app/src/main/java/com/pisces312/streamclip/fragment/CompressFragment.kt:521-531` (after compression success)
- Add: new `showVideoInfoCard()` helper method

- [ ] **Step 1: Add showVideoInfoCard() helper method**

Add this method in `CompressFragment.kt` (after `handleVideoSelected`, around line 331):

```kotlin
    private fun showVideoInfoCard(
        card: android.view.View,
        title: android.widget.TextView,
        pathView: android.widget.TextView,
        videoInfoView: android.widget.TextView,
        audioInfoView: android.widget.TextView,
        metaInfoView: android.widget.TextView,
        info: com.pisces312.streamclip.model.VideoInfo
    ) {
        pathView.text = info.path
        videoInfoView.text = "编码: ${info.videoCodec}  分辨率: ${info.resolution}  视频码率: ${info.videoBitrateKbps}"
        audioInfoView.text = "音频: ${info.audioCodec} ${info.audioSampleRateStr} ${info.audioBitrateKbps}"
        val metaParts = mutableListOf<String>()
        if (info.creationTime.isNotEmpty()) metaParts.add("创建时间: ${info.creationTime}")
        if (info.location.isNotEmpty()) metaParts.add("地理位置: ${info.location}")
        metaInfoView.text = metaParts.joinToString("  ")
        metaInfoView.visibility = if (metaParts.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        card.visibility = android.view.View.VISIBLE
    }
```

- [ ] **Step 2: Modify handleVideoSelected() to probe and display**

Replace the `handleVideoSelected` method (lines 319-331):

```kotlin
    private fun handleVideoSelected(uri: Uri) {
        val path = FileUtils.getPathFromUri(requireContext(), uri)
        if (path != null) {
            videoPath = path
            binding.cardOutputInfo.visibility = android.view.View.GONE
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

- [ ] **Step 3: Add output video info probe after compression success**

In the `executeSingleCompress` method, after the existing success block (after line 530, before the Toast), add the output probe. The existing code at lines 521-531 becomes:

```kotlin
                if (result.success) {
                    val outFileName = outPath.substring(outPath.lastIndexOf('/') + 1)
                    FileUtils.scanFile(requireContext(), java.io.File(outPath))
                    sourceFileTimes?.let { (creation, modified) ->
                        FileUtils.applyFileTimes(outPath, creation, modified)
                    }
                    val sourceLocation = videoPath?.let { FFmpegService.probeLocation(it) }
                    val outputLocation = FFmpegService.probeLocation(outPath)
                    LogCollector.d("CompressFragment", "Source location: $sourceLocation")
                    LogCollector.d("CompressFragment", "Output location: $outputLocation")

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
                } else {
                    Toast.makeText(requireContext(), getString(R.string.compress_failed, result.error), Toast.LENGTH_SHORT).show()
                }
```

- [ ] **Step 4: Build verification**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pisces312/streamclip/fragment/CompressFragment.kt
git commit -m "feat: display video info cards in compress tab

Probe and show original video info after selection, and output video
info after compression. Cards display codec, resolution, bitrate,
audio info, creation time, and GPS location for verification."
```
