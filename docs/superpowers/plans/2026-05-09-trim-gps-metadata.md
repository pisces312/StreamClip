# Trim & Merge GPS Metadata Preservation Fix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix lossless trim not preserving GPS metadata, and add GPS metadata from first video to merged output.

**Architecture:** Two changes in `FFmpegService.kt`:
1. Trim: add `-map_metadata 0` + `-f mov` flags (matching compress implementation)
2. Merge: extract metadata from first video via sidecar file, apply after concat merge

**Tech Stack:** Kotlin, FFmpeg-kit

---

### Task 1: Add metadata flags to trimVideo()

**Files:**
- Modify: `app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt:184-194`

**Context:** The compress implementation (`CompressConfig.kt:20-21,64`) uses `-map_metadata 0` and `-f mov` to preserve GPS. Trim is missing these.

- [ ] **Step 1: Add `-map_metadata 0` and `-f mov` to trimVideo() command**

In `FFmpegService.trimVideo()`, modify the `buildString` block. Position `-map_metadata 0` after `-i input` (before `-ss`), and `-f mov` after `-fflags +genpts` (before output path):

```kotlin
val command = buildString {
    append("-y ")
    append("-i ")
    append("\"$inputPath\" ")
    append("-map_metadata 0 ")
    append("-ss $startSec ")
    append("-t $durationSec ")
    append("-c copy ")
    append("-avoid_negative_ts make_zero ")
    append("-fflags +genpts ")
    append("-f mov ")
    append("\"$outputPath\"")
}
```

- [ ] **Step 2: Build verification**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt
git commit -m "fix: preserve GPS metadata in lossless trim

Add -map_metadata 0 and -f mov flags to trimVideo() to match the
compress implementation, ensuring GPS/location data is preserved
in trimmed output files."
```

---

### Task 2: Preserve first video's metadata in mergeVideos()

**Files:**
- Modify: `app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt:203-221`

**Context:** The concat demuxer (`-f concat`) reads from a playlist file, so `-map_metadata 0` references the playlist (not the videos). GPS metadata requires a two-pass approach: merge first, then apply metadata from the first video via sidecar file. This matches how `CompressFragment.kt:527-530` verifies metadata post-operation.

- [ ] **Step 1: Add metadata extraction helper and modify mergeVideos()**

Add a private helper method and modify `mergeVideos()` in `FFmpegService.kt`. The helper extracts format-level tags to a sidecar file; the merge applies them after concat:

```kotlin
/**
 * Extract format-level metadata tags to a sidecar file for later application.
 * Uses ffprobe to dump tags, then writes KEY=VALUE lines.
 */
private fun extractMetadataToFile(inputPath: String, metadataFile: File): Boolean {
    return try {
        val session = FFprobeKit.execute("-v quiet -show_format \"$inputPath\"")
        if (!ReturnCode.isSuccess(session.returnCode)) return false

        val tags = mutableListOf<String>()
        var inTags = false
        for (line in session.output.lines()) {
            if (line == "[FORMAT_TAGS]") {
                inTags = true
                continue
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                inTags = false
                continue
            }
            if (inTags && line.contains('=')) {
                tags.add(line)
            }
        }

        if (tags.isEmpty()) return false
        metadataFile.writeText(tags.joinToString("\n"))
        true
    } catch (e: Exception) {
        LogCollector.e("FFmpegService", "Extract metadata failed: ${e.message}")
        false
    }
}

/**
 * Merge videos using concat demuxer (lossless, no re-encode).
 * Preserves metadata (GPS etc.) from the first video.
 */
suspend fun mergeVideos(
    context: Context,
    inputPaths: List<String>,
    outputPath: String,
    onProgress: ((Progress) -> Unit)? = null
): Result {
    if (inputPaths.size < 2) {
        return Result(false, error = "At least 2 videos required")
    }

    val concatFile = File.createTempFile("concat_list", ".txt", context.cacheDir)
    concatFile.writeText(inputPaths.joinToString("\n") { "file '${it.replace("'", "'\\''")}'" })

    val command = "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" -c copy -fflags +genpts -avoid_negative_ts make_zero -reset_timestamps 1 \"$outputPath\""

    val result = executeCommand(command, outputPath, onProgress = onProgress)
    concatFile.delete()

    // Apply metadata from first video to merged output
    if (result.success) {
        val metadataFile = File.createTempFile("metadata", ".txt", context.cacheDir)
        try {
            if (extractMetadataToFile(inputPaths[0], metadataFile)) {
                val metadataCmd = "-y -i \"$outputPath\" -map_metadata 0 -i \"${metadataFile.absolutePath}\" -map_metadata 1 -c copy -f mov \"$outputPath.tmp\""
                val metadataResult = executeCommand(metadataCmd, "$outputPath.tmp")
                if (metadataResult.success) {
                    java.io.File("$outputPath.tmp").renameTo(java.io.File(outputPath))
                }
            }
        } catch (e: Exception) {
            LogCollector.e("FFmpegService", "Apply metadata failed: ${e.message}")
        } finally {
            metadataFile.delete()
        }
    }

    return result
}
```

**Key details:**
- `extractMetadataToFile()` reads `[FORMAT_TAGS]` section from `ffprobe -show_format` output
- Metadata is applied via `-i metadata.txt -map_metadata 1` (input index 1 = metadata file)
- Output goes to `$outputPath.tmp` then renamed to avoid overwrite issues
- `-f mov` ensures GPS `xyz` atom format (same as compress)
- If metadata extraction or application fails, the merged file is still returned (graceful degradation)

- [ ] **Step 2: Build verification**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt
git commit -m "feat: preserve first video's GPS metadata in merge

Add two-pass metadata application to mergeVideos(): extract format
tags from the first video via ffprobe, then apply them to the merged
output. Ensures GPS/location data from the first source is preserved."
```
