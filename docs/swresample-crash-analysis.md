# StreamClip swresample Native Crash 深度分析

> 分析时间: 2026-05-13
> 模型: Kimi K2.6
> 涉及版本: ffmpeg-kit-8.1.aar (FFmpeg 8.1)

## 现象确认

用户验证：**去掉 `-ar`（采样率转换）参数后，压缩可以成功。**

这精确锁定了崩溃范围：**不是 AAC 编码器的问题，而是 `libswresample` 的采样率转换功能在特定条件下触发 native crash。**

## 构建环境分析

### 用户自定义构建脚本

`build_ffmpeg_full.sh` 中的 FFmpeg configure 参数：

```bash
./configure \
    --prefix=$PREFIX \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --cc=$CC \
    --cxx=$CXX \
    --ar=$AR \
    --ranlib=$RANLIB \
    --strip=$STRIP \
    --enable-cross-compile \
    --enable-gpl \
    --enable-nonfree \
    --enable-libx264 \
    --enable-libx265 \
    --enable-libmp3lame \
    --enable-mediacodec \
    --enable-jni \
    --enable-encoder=h264_mediacodec \
    --enable-encoder=hevc_mediacodec \
    --enable-decoder=h264_mediacodec \
    --enable-decoder=hevc_mediacodec \
    --enable-shared \
    --disable-static \
    --disable-doc \
    --disable-programs \
    --extra-cflags="-I$PREFIX/include" \
    --extra-ldflags="-L$PREFIX/lib"
```

### 关键发现：缺少重要的编译优化标志

与官方 ffmpeg-kit 构建相比，用户的脚本**缺少以下关键标志**：

| 缺失标志 | 官方构建 | 用户构建 | 影响 |
|----------|----------|----------|------|
| `-march=armv8-a` in CFLAGS | ✅ 有 | ❌ 无 | 基础架构优化 |
| `-Os -ffunction-sections -fdata-sections` | ✅ 有 | ❌ 无 | 代码大小优化和段隔离 |
| `-fstrict-aliasing` | ✅ 有 | ❌ 无 | 严格的别名规则优化 |
| `-DANDROID_NDK -DANDROID` | ✅ 有 | ❌ 无 | NDK 平台宏定义 |
| `-Wl,--gc-sections` | ✅ 有 | ❌ 无 | 链接时死代码消除 |
| `-Wl,--hash-style=both` | ✅ 有 | ❌ 无 | 符号哈希表风格 |
| `--extra-cflags="-O3 ..."` | 可能 | 无 | 优化级别 |

> 注：官方 ffmpeg-kit 的 `build.log` 显示其 CFLAGS 包含 `-march=armv8-a -DFFMPEG_KIT_ARM64_V8A -std=c99 -Wno-unused-function -fstrict-aliasing -DANDROID_NDK -fPIC -DANDROID -D__ANDROID__ -D__ANDROID_MIN_SDK_VERSION__=24 -Os -ffunction-sections -fdata-sections`，而用户的 `build_ffmpeg_full.sh` 仅通过 `--extra-cflags="-I$PREFIX/include"` 传递了 include 路径。

## swresample NEON 代码分析

### 关键文件：`libswresample/aarch64/resample.S`

NEON 汇编实现的 resample filter：

```asm
function ff_resample_common_apply_filter_x4_float_neon
    movi  v0.4s, #0           // accumulator = 0
1:  ld1   {v1.4s}, [x1], #16  // load 4 floats from src
    ld1   {v2.4s}, [x2], #16  // load 4 floats from filter
    fmla  v0.4s, v1.4s, v2.4s // accumulate
    subs  w3, w3, #4          // length -= 4
    b.gt  1b                  // loop
    faddp v0.4s, v0.4s, v0.4s // pairwise add
    faddp v0.4s, v0.4s, v0.4s // reduce to single value
    st1   {v0.s}[0], [x0], #4 // store result
    ret
```

### 关键文件：`libswresample/resample.c`

Filter bank 分配（line 244）：

```c
c->filter_alloc  = FFALIGN(c->filter_length, 8);
c->filter_bank   = av_calloc(c->filter_alloc, (phase_count+1)*c->felem_size);
```

**分析：**
- `filter_alloc` 对齐到 8
- `av_calloc` 保证内存对齐（通常 16 字节或更多）
- NEON `ld1` 不需要内存对齐（`ld1 {v1.4s}, [x1]` 是无对齐加载）
- 但 `audio_convert_neon.S` 中 `swri_oldapi_conv_flt_to_s16_neon` 的 `st1 {v4.8h}, [x0], #16` 可能需要 dst 对齐

### 运行时 CPU 检测

`libavutil/aarch64/cpu.c` 中的 CPU 标志检测：

```c
int ff_get_cpu_flags_aarch64(void)
{
    int flags = AV_CPU_FLAG_ARMV8 * HAVE_ARMV8 |
                AV_CPU_FLAG_NEON  * HAVE_NEON;
    // ...
}
```

在 aarch64 上，NEON 是强制指令集，所以 `HAVE_NEON` 总是为 1。CPU 标志检测通过 `getauxval(AT_HWCAP)` 读取硬件能力。

## 可能的崩溃原因

### 假设 1：编译器优化级别差异导致 NEON 代码生成问题

用户的构建脚本**没有显式指定优化级别**（没有 `-O2`、`-O3` 或 `-Os`），而 FFmpeg 的 configure 默认可能使用 `-O3` 或 `-O2`。但缺少 `-march=armv8-a` 和 `-fstrict-aliasing` 等标志可能导致：

1. 编译器生成不兼容的 NEON 代码
2. 某些内联汇编约束（inline asm constraints）被错误处理
3. 内存别名分析不正确，导致错误的指令重排

### 假设 2：FFmpeg 8.1 自身的 swresample bug

FFmpeg 8.1 的 `libswresample` 在特定采样率转换比例下可能存在已知的 edge case bug：

- 48000→44100 的转换比例约为 1.088，这不是简单的整数比
- FFmpeg 的 `exact_rational` 路径会尝试找到精确的分数比，可能导致 `phase_count` 和 `filter_length` 的组合触发某些边界条件
- `rebuild_filter_bank_with_compensation` 函数在补偿模式下重新分配 filter bank，可能存在内存问题

### 假设 3：链接标志差异导致运行时问题

用户的脚本缺少 `-Wl,--gc-sections` 等链接优化标志，但这对运行时 crash 影响较小。更值得关注的是：

- 缺少 `-lm` 显式链接（虽然 NDK 通常自动处理）
- `libx265` 是 C++ 库，需要 `-lc++`，用户的脚本已正确处理

### 假设 4：音频格式转换路径的问题

`libswresample/aarch64/audio_convert_init.c` 中的 NEON 格式转换：

```c
if(out_fmt == AV_SAMPLE_FMT_S16 && in_fmt == AV_SAMPLE_FMT_FLT)
    ac->simd_f = conv_flt_to_s16_neon;
```

AAC 编码器通常接收 `AV_SAMPLE_FMT_FLTP`（planar float），如果输入音频是 `AV_SAMPLE_FMT_S16`（interleaved int16），swresample 需要先进行格式转换，再进行采样率转换。这个转换路径上的 NEON 代码可能与 resample 代码产生冲突。

## 验证建议

### 验证 1：确认崩溃点在 swresample

```bash
adb logcat | grep -i "fatal signal\|libswresample\|resample"
```

期望看到：
```
Fatal signal 11 (SIGSEGV), code 1, fault addr ... in libswresample.so
backtrace:
    #00 pc 0000000000xxxxxx  libswresample.so (ff_resample_common_apply_filter_x4_float_neon)
```

### 验证 2：测试不同采样率组合

使用 adb shell 运行 ffmpeg 二进制（如果可用），测试以下组合：

```bash
# 48000 -> 44100 (触发崩溃的比例)
ffmpeg -f lavfi -i "sine=frequency=1000:duration=5" -ar 44100 test.wav

# 48000 -> 48000 (不转换，应成功)
ffmpeg -f lavfi -i "sine=frequency=1000:duration=5" -ar 48000 test.wav

# 44100 -> 48000 (反向转换)
ffmpeg -f lavfi -i "sine=frequency=1000:duration=5" -ar 48000 test.wav
```

### 验证 3：禁用 NEON 测试

如果可以在运行时禁用 NEON（通过环境变量或代码修改），测试禁用 NEON 后是否还崩溃。

## 修复方案

### 方案 1：补充编译标志（推荐）

修改 `build_ffmpeg_full.sh`，在 FFmpeg configure 中添加缺失的编译优化标志：

```bash
export COMMON_FLAGS="-march=armv8-a -fstrict-aliasing -fPIC -DANDROID -D__ANDROID__ -D__ANDROID_MIN_SDK_VERSION__=24"
export OPT_FLAGS="-Os -ffunction-sections -fdata-sections"

./configure \
    ... \
    --extra-cflags="$COMMON_FLAGS $OPT_FLAGS -I$PREFIX/include" \
    --extra-ldflags="-Wl,--gc-sections -L$PREFIX/lib"
```

### 方案 2：降低 swresample 优化级别

如果确认是 NEON 代码生成问题，可以仅为 `libswresample` 禁用 NEON 或降低优化级别：

```bash
# 在 configure 后，修改 libswresample/Makefile
sed -i 's/-O3/-O2/g' libswresample/Makefile
# 或禁用 NEON 汇编
sed -i 's/HAVE_NEON=yes/HAVE_NEON=no/g' config.h
```

### 方案 3：应用 FFmpeg 上游补丁

检查 FFmpeg 官方仓库是否有针对 swresample aarch64 的修复补丁：

```bash
cd /home/pisces312/ffmpeg-8.1
git log --oneline --all -- libswresample/aarch64/
git log --oneline --all -- libswresample/resample.c
```

### 方案 4：继续使用 workaround（当前方案）

保持 `audioSampleRate` 默认值为 `"copy"`，避免触发 resample。这是最安全、最快速的解决方案，不影响大多数用户场景。

## 结论

**最可能的原因：编译标志不完整导致的 NEON 代码生成问题。**

用户的 `build_ffmpeg_full.sh` 缺少 `-march=armv8-a`、`-fstrict-aliasing`、`-Os` 等关键编译优化标志，这些标志在官方 ffmpeg-kit 构建中都存在。缺少这些标志可能导致编译器生成不稳定的 NEON 汇编代码，在特定的采样率转换场景下触发 segfault。

**建议的修复优先级：**
1. 短期：保持 `audioSampleRate = "copy"` workaround
2. 中期：补充编译标志，重新构建 ffmpeg-kit
3. 长期：考虑升级到 FFmpeg 8.2+ 或应用上游补丁

## 相关文件

- `D:/nili/3rd_party_projects/ffmpeg-kit/build_ffmpeg_full.sh` — 构建脚本
- `D:/nili/3rd_party_projects/ffmpeg-kit/src/ffmpeg/libswresample/aarch64/resample.S` — NEON resample 汇编
- `D:/nili/3rd_party_projects/ffmpeg-kit/src/ffmpeg/libswresample/resample.c` — resample C 代码
- `D:/nili/3rd_party_projects/ffmpeg-kit/src/ffmpeg/libavutil/aarch64/cpu.c` — CPU 标志检测
