# ffmpeg-kit 8.1 自编译 AAR 连续执行崩溃分析

> 问题：使用自编译 ffmpeg-kit 8.1 AAR 时，连续两次调用 `executeAsync` 压缩视频，第二次必现 native crash（SIGSEGV）。
> 官方 antonkarpenko/ffmpeg-kit 8.0.0 AAR 无此问题。

---

## 1. 崩溃现象

- **第一次压缩**：正常完成
- **第二次压缩**：应用闪退，logcat 中出现 `F/libc` 和 `DEBUG` tombstone
- **信号**：`SIGSEGV` (signal 11), `SEGV_MAPERR`
- **崩溃地址**：`0x000000030000005b`（非空指针，疑似小整数被当作指针）

---

## 2. Tombstone 关键信息

```
pid: 32194, tid: 32600, name: pool-7-thread-2
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000030000005b

backtrace:
  #00 pc 0x005434c  libffmpegkit.so  map_auto_video+...
  #01 pc 0x0052370  libffmpegkit.so  create_streams+...
  #02 pc 0x0051a88  libffmpegkit.so  of_open+976          <-- 崩溃点
  #03 pc 0x0067254  libffmpegkit.so  open_files+...
  #04 pc 0x0066f6c  libffmpegkit.so  ffmpeg_parse_options+472
  #05 pc 0x003e178  libffmpegkit.so  ffmpeg_execute+176
  #06 pc 0x0035b88  libffmpegkit.so  nativeFFmpegExecute+484
```

---

## 3. 符号解析（addr2line）

使用带 debug info 的 `libffmpegkit.so` 解析：

| PC | 函数 | 文件:行 |
|----|------|---------|
| `0x005434c` | `map_auto_video` | `fftools_ffmpeg_mux_init.c:1621` |
| `0x0052370` | `create_streams` | `fftools_ffmpeg_mux_init.c:2035` |
| `0x0051a88` | `of_open` | `fftools_ffmpeg_mux_init.c:3403` |

崩溃在 `map_auto_video` 第 1621 行：

```c
for (int j = 0; j < nb_input_files; j++) {
    InputFile *ifile = input_files[j];   // <-- 这里崩溃
```

---

## 4. 根因分析

### 4.1 全局变量残留

FFmpeg 命令行工具使用大量**全局变量**管理状态：

```c
// fftools_ffmpeg.c
InputFile   **input_files   = NULL;
int        nb_input_files   = 0;

OutputFile   **output_files   = NULL;
int         nb_output_files   = 0;

FilterGraph **filtergraphs;
int        nb_filtergraphs;
```

### 4.2 第一次执行流程

```
ffmpeg_execute()
  -> ffmpeg_parse_options()     // 分配 input_files[]，设置 nb_input_files = N
  -> transcode()                // 正常执行
  -> ffmpeg_cleanup()           // av_freep(&input_files) 释放数组
  -> return
```

**问题**：`ffmpeg_cleanup()` 释放了 `input_files` 数组，但**没有重置 `nb_input_files = 0`**。

### 4.3 第二次执行流程

```
ffmpeg_execute()
  -> ffmpeg_parse_options()
    -> of_open()
      -> create_streams()
        -> map_auto_video()
          -> for (j = 0; j < nb_input_files; j++)   // nb_input_files 还是 N！
            -> InputFile *ifile = input_files[j];   // input_files 已释放/悬空 -> SIGSEGV
```

### 4.4 为什么崩溃地址是 0x000000030000005b？

- `input_files` 指针数组已被 `av_freep()` 置为 NULL
- 但 `nb_input_files` 仍为非零值（如 1 或 3）
- 代码尝试访问 `input_files[0]`，即 `*(NULL + 0)`，但实际情况更复杂
- 崩溃地址 `0x...0000003` 的模式与 `nb_input_files` 的值相关，说明是**悬空指针/已释放内存被当作指针解引用**

---

## 5. 修复方案

在 `fftools_ffmpeg.c` 的 `ffmpeg_cleanup()` 函数中，释放数组后**重置计数器**：

```c
// 第 354 行附近
av_freep(&input_files);
nb_input_files = 0;       // 添加

av_freep(&output_files);
nb_output_files = 0;      // 添加
```

以及：

```c
// 第 326 行附近
av_freep(&filtergraphs);
nb_filtergraphs = 0;      // 添加
```

### 其他可能需要重置的全局变量

```c
av_freep(&decoders);
nb_decoders = 0;

// 以及 static 变量如：
// transcode_init_done = 0;
// ffmpeg_exited = 0;
// received_sigterm = 0;
// received_nb_signals = 0;
```

---

## 6. 为什么官方 AAR 没问题？

antonkarpenko/ffmpeg-kit 8.0.0 基于 FFmpeg n8.0（开发分支），可能：

1. **FFmpeg n8.0 已修复此问题**：`ffmpeg_cleanup()` 在较新版本中已包含计数器重置
2. **ffmpeg-kit JNI 层做了额外保护**：`ffmpegkit.c` 在每次调用前重置全局状态
3. **版本差异**：8.0 vs 8.1 的 FFmpeg 源码有差异

---

## 7. 验证修复

1. 修改 `fftools_ffmpeg.c` 的 `ffmpeg_cleanup()`
2. 重新构建 debug AAR：`./build-debug-aar.sh`
3. StreamClip 引用新 AAR
4. 连续压缩两次视频
5. 预期：不再崩溃

---

## 8. 相关文件

- `fftools_ffmpeg.c`：`ffmpeg_cleanup()`、`ffmpeg_execute()`
- `fftools_ffmpeg_mux_init.c`：`of_open()`、`create_streams()`、`map_auto_video()`
- `ffmpegkit.c`：`Java_com_arthenica_ffmpegkit_FFmpegKitConfig_nativeFFmpegExecute()`

---

## 9. 参考资料

- [capture-native-crash-log.md](./capture-native-crash-log.md) — 本项目的 native crash 抓取指南
- 崩溃 tombstone：`D:/downloads/crash_logs/crash_20260514_095037/tombstone_27`
- 带符号 so：`D:/nili/3rd_party_projects/ffmpeg-kit/android/obj/local/arm64-v8a/libffmpegkit.so`
