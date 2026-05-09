# Video Info Card Date Labels

## Goal

Rename the "creation_time" display label to "拍摄日期" (shooting date) and add a new "创建日期" (file creation date) field in the video info cards.

## Background

FFmpeg's `creation_time` metadata actually represents the shooting/recording date embedded by the camera, not the file system creation date. The current display label "创建时间" is misleading. Additionally, users want to see the actual file creation date separately.

## Changes

### 1. `VideoInfo.kt` — Add field

- Add `fileCreationTime: String = ""` field
- Add computed property `fileCreationTimeStr` returning "N/A" when empty

### 2. `FFmpegService.probeVideoInfo()` — Get file creation time

- After probing metadata, use NIO to get file creation time:
  ```kotlin
  val fileCreationTime = try {
      val p = Paths.get(inputPath)
      val fileTime = java.nio.file.Files.getAttribute(p, "creationTime") as? java.nio.file.attribute.FileTime
      fileTime?.let {
          java.time.Instant.ofEpochMilli(it.toMillis())
              .atZone(java.time.ZoneId.systemDefault())
              .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
      } ?: ""
  } catch (e: Exception) { "" }
  ```
- Pass `fileCreationTime` to `VideoInfo` constructor

### 3. `CompressFragment.kt` — Update display

- Change `"创建时间: ${info.creationTime}"` → `"拍摄日期: ${info.creationTime}"`
- Add `"创建日期: ${info.fileCreationTimeStr}"` to metaParts (after 拍摄日期, before 地理位置)

## Files

| File | Change |
|------|--------|
| `app/src/main/java/com/pisces312/streamclip/model/VideoInfo.kt` | Add `fileCreationTime` field + computed property |
| `app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt` | Probe file creation time via NIO, pass to VideoInfo |
| `app/src/main/java/com/pisces312/streamclip/fragment/CompressFragment.kt` | Change display label, add new field |

## Notes

- Code variable `creationTime` stays unchanged, only display label changes
- File creation time uses `java.nio.file.Files.getAttribute` which requires API 26+ (project minSdk is 26)
- Gracefully handles exceptions (returns empty string on failure)
