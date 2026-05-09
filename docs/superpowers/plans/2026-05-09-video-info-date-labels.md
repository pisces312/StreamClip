# Video Info Date Labels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename video info card's "creation_time" label to "拍摄日期" and add file creation date ("创建日期") display.

**Architecture:** Three-file change: add `fileCreationTime` field to the data model, probe it via NIO in the FFmpeg service, and update the display label in the compress fragment.

**Tech Stack:** Kotlin, Java NIO (`java.nio.file.Files`), Android CompressFragment UI

---

### Task 1: Add fileCreationTime field to VideoInfo

**Files:**
- Modify: `app/src/main/java/com/pisces312/streamclip/model/VideoInfo.kt:3-25`

- [ ] **Step 1: Add field and computed property**

Add `fileCreationTime: String = ""` field after `creationTime` (line 15), and `fileCreationTimeStr` computed property after `creationTimeStr` (line 25):

```kotlin
// After line 15:
    val fileCreationTime: String = "", // e.g. "2024-03-15 10:30:00"

// After line 25 (creationTimeStr):
    val fileCreationTimeStr: String get() = fileCreationTime.ifEmpty { "N/A" }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/pisces312/streamclip/model/VideoInfo.kt
git commit -m "feat: add fileCreationTime field to VideoInfo model"
```

### Task 2: Probe file creation time in FFmpegService

**Files:**
- Modify: `app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt:398-426`

- [ ] **Step 1: Add file creation time probe after existing creation_time probe**

After the `creationTime` probe block (around line 404), add:

```kotlin
            // File creation time from filesystem
            val fileCreationTime = try {
                val p = java.nio.file.Paths.get(inputPath)
                val fileTime = java.nio.file.Files.getAttribute(p, "creationTime") as? java.nio.file.attribute.FileTime
                fileTime?.let {
                    java.time.Instant.ofEpochMilli(it.toMillis())
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                } ?: ""
            } catch (e: Exception) { "" }
```

- [ ] **Step 2: Pass fileCreationTime to VideoInfo constructor**

In the `VideoInfo(...)` constructor call (around line 409-426), add after `creationTime = creationTime,`:

```kotlin
                fileCreationTime = fileCreationTime,
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt
git commit -m "feat: probe file creation time via NIO in FFmpegService"
```

### Task 3: Update display labels in CompressFragment

**Files:**
- Modify: `app/src/main/java/com/pisces312/streamclip/fragment/CompressFragment.kt:375-376`

- [ ] **Step 1: Change creation_time label and add fileCreationTime**

Replace line 375:
```kotlin
        if (info.creationTime.isNotEmpty()) metaParts.add("创建时间: ${info.creationTime}")
```
With:
```kotlin
        if (info.creationTime.isNotEmpty()) metaParts.add("拍摄日期: ${info.creationTime}")
        if (info.fileCreationTime.isNotEmpty()) metaParts.add("创建日期: ${info.fileCreationTime}")
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/pisces312/streamclip/fragment/CompressFragment.kt
git commit -m "feat: rename creation_time label to 拍摄日期, add 创建日期 display"
```
