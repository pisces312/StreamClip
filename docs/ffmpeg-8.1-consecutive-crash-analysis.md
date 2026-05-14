# FFmpeg 8.1 连续执行 Crash 分析

## 问题现象

- **App**: StreamClip (debug 版)
- **触发条件**: 连续执行两次 FFmpeg 压缩命令（任意视频）
- **崩溃信号**: `SIGSEGV (signal 11)`, `SEGV_MAPERR`
- **故障地址**: `0x000000030000005b`（野指针特征）
- **设备**: HONOR BKQ-AN80, Android 16 (API 36)
- **FFmpeg 版本**: 8.1 (自定义编译)
- **FFmpegKit 版本**: 8.1 (自定义 JNI wrapper)

## 调试过程

### 1. 初步排查

最初怀疑是 FFmpegKit AAR 兼容性问题，尝试切换 antonkarpenko 的 8.0 AAR，但遇到 `JNI_OnLoad` 返回 0 的错误（API 36 不兼容）。

### 2. 构建 Debug 版 SO

为定位 native crash，需要带符号表的 `.so` 文件：

```bash
# 编译 JNI wrapper（debug 模式，保留符号）
./build-jni-ndk-r27d.sh --debug

# 注意：FFmpeg 的 .so 本身也需要 debug 编译
# 修改 build-ffmpeg-ndk-27d.sh：
#   - 去掉 --strip 参数
#   - CFLAGS 从 -Os 改为 -O0 -g
```

**关键发现**: `obj/local/arm64-v8a/libffmpegkit.so` 是未 strip 的（有 debug_info），而 `libs/arm64-v8a/libffmpegkit.so` 被 strip 了。ndk-stack 需要用 `obj/local/` 下的版本。

### 3. 复现 Crash

在 CustomCommandFragment 中填入测试命令：
```
-y -i /storage/emulated/0/DCIM/Camera/VID_20260511_121703.mp4 -c:v libx264 -c:a copy /storage/emulated/0/DCIM/Camera/VID_20260511_121703_compressed.mp4
```

执行两次后 App crash。

### 4. 收集 Tombstone

使用 `collect-native-crash.sh` 脚本收集：
```bash
./collect-native-crash.sh
# 复现 crash 后按回车
# 脚本自动拉取 tombstone 并用 ndk-stack 解析
```

### 5. 解析堆栈

```bash
ndk-stack -sym /mnt/d/nili/3rd_party_projects/ffmpeg-kit/android/obj/local/arm64-v8a \
          -dump tombstone_27
```

## 崩溃堆栈

```
#00 map_auto_video    fftools_ffmpeg_mux_init.c:1621:36
#01 create_streams    fftools_ffmpeg_mux_init.c:2035:19
#02 of_open           fftools_ffmpeg_mux_init.c:3403:11
#03 open_files        fftools_ffmpeg_opt.c:1465:15
#04 ffmpeg_parse_options  fftools_ffmpeg_opt.c:1521:11
#05 ffmpeg_execute    fftools_ffmpeg.c:1054:11
#06 Java_com_arthenica_ffmpegkit_FFmpegKitConfig_nativeFFmpegExecute  ffmpegkit.c:834:22
```

## 根因分析

崩溃发生在 `fftools_ffmpeg_mux_init.c:1621` 的 `map_auto_video` 函数：

```c
switch (istg->stg->type) {  // <-- 这里崩溃
```

`istg->stg` 是 **野指针**。第一次执行后，FFmpeg 的全局状态（`input_files`、`input_streams` 等）没有被正确清理，第二次执行时这些指针指向了已释放的内存。

这是 FFmpeg 8.1 的已知问题：`ffmpeg_execute` 后没有完整清理 muxer 相关的全局状态。

## 相关代码

崩溃位置（`fftools_ffmpeg_mux_init.c` 约 1621 行）：
```c
for (int i = 0; i < ifile->nb_stream_groups; i++) {
    InputStreamGroup *istg = ifile->stream_groups[i];
    // ...
    switch (istg->stg->type) {  // stg 是野指针
```

## 修复方案

### 方案 1: App 层互斥（临时规避）

在 `FFmpegService.executeCommand` 中确保同时只有一个命令执行：

```kotlin
suspend fun executeCommand(...): Result = withContext(Dispatchers.IO) {
    cancelCurrentSession()
    delay(100)  // 等待 cleanup
    // ...
}
```

### 方案 2: FFmpeg 源码修复（推荐）

在 `ffmpeg_execute` 返回前，强制清理 `of_open` 中分配的 `InputStreamGroup` 状态。

### 方案 3: 降级 FFmpeg

如果 8.1 有不可修复的 bug，考虑降级到 6.x LTS 版本。

## 调试工具

- **ndk-stack**: 解析 tombstone 符号
- **tombstone 路径**: `/data/tombstones/tombstone_*`（需要 root）
- **logcat 过滤**: `adb logcat | grep -E "signal|DEBUG|FFmpegKit"`
- **SO 符号检查**: `file libffmpegkit.so`（看 `stripped` 或 `not stripped`）

## 参考

- FFmpeg 源码: `/home/pisces312/ffmpeg-8.1`
- FFmpegKit 源码: `/mnt/d/nili/3rd_party_projects/ffmpeg-kit`
- Debug SO 路径: `android/obj/local/arm64-v8a/`
