# FFmpeg 9 升级评估与方案

> 评估日期：2026-10-31
> 评估对象：StreamClip 自编译 ffmpeg-kit AAR（当前基于 FFmpeg n8.1）
> 结论摘要：**不重编 FFmpeg 9**；改为把 AAR 跟随到 8.1.3，9.x 采用触发式升级

---

## 一、结论

1. **9.0 对本项目六类核心操作没有能力增益。** MediaCodec 编解码路径在 6 个月窗口内只有 1 个提交（且是音频解码器 class 声明修正）；裁剪/合并/提取依赖的 mov 封装与解封装没有本项目需要的新特性。
2. **9.0 唯一可量化的收益（mov 解封装安全加固）已经回移到 8.1.3。** 2026-09-19 同批 7 个 `libavformat/mov.c` 边界/溢出校验补丁进入 8.1.3，无需跳到 9.0 即可获得。
3. **9.0 对本项目是净风险项。** 9.0 独有的 movenc 行为变更（movie timescale 统一分母、编辑列表负 cts、tmcd 时长推导）会改变无损裁剪/合并输出的时长与时间戳表现，而这两条恰是本项目回归成本最高的部分。
4. **推荐动作：AAR 从 n8.1 跟随到 n8.1.3（0.5–1 人日，零 API 变更），并建立 9.x 的触发条件与移植脚手架。**

---

## 二、版本事实（已核实）

| 版本 | 提交日期 | 状态 | 说明 |
|---|---|---|---|
| n8.1 | 2026-03-16 | 本项目当前基线 | 自编译 AAR 基于此 |
| n8.1.3 | 2026-09-20 | 8.1 分支仍在维护 | 9.0 发布后两个月仍有安全回移 |
| n9.0 | 2026-08-03（08-04 公布） | 当前大版本 | 代号 "Lei"，ABI 破坏，7 个 soname 变更 |
| n9.0.2 | 2026-09-17 | 最新稳定 | 已含两轮修订 |
| n9.1-dev | 2026-06-26 起 | 开发中，未发布 | 含 AAC 编码器大幅改进、H.264 数据分区 |

关键判断：**8.1 分支未 EOL**，不具备"必须跟大版本才能拿到修复"的前提。FFmpeg 一贯维护最近两个 release 分支，8.1 预计至少延续到 9.2 周期。

---

## 三、本项目能力面与 9.0 变更对照

| 功能 | 实际命令/模块 | 9.0 相关变更 | 收益 |
|---|---|---|---|
| 无损裁剪 | `-ss/-t -c copy`，mov demuxer + muxer | 解封装加固（已回移 8.1.3）；封装侧 9.0 独有行为变更 | 无 / 有回归风险 |
| 无损合并 | concat demuxer + `-c copy` | 同上 | 无 / 有回归风险 |
| 提取音频 | `-c:a copy` | 无 | 无 |
| 硬编压缩 | `hevc_mediacodec` / `h264_mediacodec` | `mediacodec*.c` 6 个月共 1 个提交（`mediacodecdec: declare correct class for audio decoders`） | **零** |
| 软编压缩 | `libx264` / `libx265` | 编码器本体独立于 FFmpeg（x264 BUILD 164、x265 X265_BUILD 216） | 零（与 FFmpeg 版本无关） |
| 音频压缩 | `libmp3lame` / `libopencore_amrnb` | 无 | 无 |
| 元数据/自定义命令 | ffprobe + 用户任意参数 | 移除 `-vsync`、`-filter_complex_script`、`-top`、`-adrift_threshold` | **负**（旧命令直接报错） |

9.0 的发布亮点（Vulkan v360/APV、AMF vqe/frc、ProRes RAW VideoToolbox、Playdate、动画 WebP、ONNX Runtime DNN、LCEVC、x86 AVX-512）**与本项目无交集**，公开 changelog 中也无 ARM64 侧性能项。

### 9.0 独有的 movenc 行为变更（风险项，非收益）

| 变更 | 日期 | 对本项目的潜在影响 |
|---|---|---|
| 全轨统一分母作为 movie timescale | 2026-06-16 | 输出 mvhd timescale 变化 → 播放器/相册显示的时长可能与源不同 |
| 编辑列表避免负 cts（CMAF） | 2026-06-10 | 仅影响 CMAF 输出，本项目不触发 |
| tmcd 轨时长推导调整 | 2026-06-23 | 无 tmcd 轨时不触发 |
| 分片 flush 空数据崩溃修复 | 2026-08-28 | 已在 8.1.3（唯一被回移的 movenc 修复） |

---

## 四、移植成本测算

### 4.1 代码面

本项目并非直接调用 FFmpeg 库，而是把 FFmpeg 的 `fftools/` 前端整体搬进 JNI 目录并加 `fftools_` 前缀：

- 现有移植层：**35,647 行 C**，62 个文件（含 ffmpeg.c/ffprobe.c/cmdutils.c 拆分的全部子模块）
- 自有修改：**5 处**（`docs/ffmpeg-modifications.md`），核心是把 stdout 输出改走 `av_log` → JNI 回调
  - `fftools_opt_common.c`：108 处 `printf` → `av_log`
  - `fftools_tw_stdout.c`：stdout writer 整体重写为 av_log writer
  - `fftools_cmdutils.c` / `fftools_ffprobe.c`：去掉 `av_log_set_callback(log_callback_help)`，help 输出改 stderr
  - `fftools_ffmpeg_mux.c`：SDP 输出改 av_log

上游 diff 规模（GitHub compare API 实测）：

| 升级路径 | 涉及 fftools 文件 | 增/删行 | 需重新落位的自有修改 |
|---|---|---|---|
| n8.1 → n8.1.3 | 7（含 opt_common.c 仅 +2/−2、mux_init.c +25/−1） | +95 / −41 | 1 处，且不冲突（`print_program_info` 格式化） |
| n8.1 → n9.0 | 26（排除 ffplay/ffplay_renderer） | **+735 / −511** | 5 处全部需要，opt_common.c 本身被改 +22/−11 |

n9.0 还把 `textformat/`、`graph/` 子目录做了调整，需要按现有 flatten 规则重新映射文件名（如 `graph/graphprint.c` → `fftools_graphprint.c`）。

### 4.2 API 兼容性（已逐项核对）

9.0 相比 8.1 删除的 FF_API 宏：

| 库 | 被删宏 |
|---|---|
| libavcodec | CODEC_PROPS、EXR_GAMMA、NVDEC_OLD_PIX_FMTS、PARSER_CODECID、PARSER_PRIVATE、V408_CODECID |
| libavformat | INTERNAL_TIMING、NO_DEFAULT_TLS_VERIFY |
| libavfilter | BUFFERSINK_OPTS、CONTEXT_PUBLIC、LIBNPP_SUPPORT |
| libavutil | MOD_UINTP2、OPT_INT_LIST、OPT_PTR、RISCV_FD_ZBA、VULKAN_FIXED_QUEUES |

本地代码命中情况：

| 检查项 | 结果 |
|---|---|
| `avformat_transfer_internal_stream_timing_info`（FF_API_INTERNAL_TIMING） | 0 处 |
| `av_opt_set_ptr` / `av_opt_set_int_list`（FF_API_OPT_PTR / OPT_INT_LIST） | 0 处 |
| `av_mod_uintp2`（FF_API_MOD_UINTP2） | 0 处 |
| `avcodec_string`、`av_stream_get_codec_timebase` | 0 处 |
| `AVFilterContext`（FF_API_CONTEXT_PUBLIC） | 23 处，均为指针声明/传递，不访问被删字段 |
| JNI 层 `->codec` 字段访问 | 0 处 |

结论：**API 层面可编译通过的概率高**，不构成主要障碍；主要成本在 fftools 重搬 + 5 处修改重落位 + 设备回归。

### 4.3 两个高危历史知识点复核（针对 9.0 源码直接验证）

| 项目内的既有结论 | 9.0 源码现状 | 判定 |
|---|---|---|
| 重编码不传播 display matrix，需 `Mp4RotationUtils.copyRotation` 兜底 | `fftools/ffmpeg_mux_init.c` 中 `codecpar->coded_side_data` 复制仍**只**出现在 `streamcopy_init()` 内（n9.0 第 1066 行） | 结论不变，workaround 仍必需 |
| tkhd 宽高必须写编码尺寸，不能按旋转交换 | `libavformat/movenc.c` n9.0：tkhd 仍写 `track->height`（第 4077–4090 行，源自 `codecpar->height`），矩阵来自 side data（第 4000–4020 行） | 结论不变 |
| ffprobe rotation 符号与 tkhd 矩阵相反 | 9.0 未改动该换算路径 | 结论不变 |

即：**升级到 9.0 不会让现有的旋转处理变简单，也不会让它自动失效**——但必须重跑真机回归确认。

### 4.4 工作量估算

| 方案 | 工作量 | 风险 | 需回归范围 |
|---|---|---|---|
| 维持 n8.1 | 0 | 缺 6 个月安全修复 | — |
| **跟随 n8.1.3** | **0.5–1 人日** | **低（无 API 变更、movenc 行为不变）** | 全量 8 项 |
| 移植 n9.0.2 | 2–4 人日 | 中（movenc 时长/时间戳行为变更） | 全量 8 项 + 时长/时间戳专项 |

---

## 五、方案对比

| 方案 | 收益 | 成本 | 风险 | 建议 |
|---|---|---|---|---|
| A. 维持 n8.1 不动 | 0 | 0 | 累积未修复的解封装漏洞 | 不采纳 |
| **B. 重建 8.1.3 AAR** | 拿齐 mov 解封装安全加固；与 9.0 同等安全水位 | 0.5–1 人日 | 低 | **采纳** |
| C. 移植 9.0.2 | 仅新增无关特性；movenc 行为变更 | 2–4 人日 | 中 | 暂缓，设触发条件 |
| D. 9.0 + 同步做体积裁剪 | 两个变量耦合，问题定位困难 | 3–5 人日 | 高 | 不采纳 |

> 体积现状（AAR 内未压缩）：libavcodec.so 15.3 MB、libavfilter.so 4.0 MB、合计 25.8 MB。若要减体积，应作为**独立课题**在 8.1.3 基线上做，不要与版本升级合并。

---

## 六、推荐方案执行计划（B：n8.1 → n8.1.3）

### Phase 0 — 基线固化（0.5 h）

1. 记录当前 AAR 指纹：`sha256sum app/libs/ffmpeg-kit-8.1.aar`
2. 真机抓当前 `ffmpeg -version` 与 `configuration:` 全串，存档到 `docs/`（升级后逐字对比 configure 差异）
3. 固定回归素材：竖拍 4K（微信导出，ffprobe `-90`，tx=2160<<16）、竖拍 720p（tx=0）、横拍 H.264、HEVC 10bit 各一份

### Phase 1 — 源码替换（0.5 h）

```bash
# 不覆盖旧目录，保留可回退构建
cd /home/pisces312
curl -LO https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz
tar xf ffmpeg-8.1.3.tar.xz          # → /home/pisces312/ffmpeg-8.1.3
```

构建脚本内 `export FFMPEG_SRC=/home/pisces312/ffmpeg-8.1` 改为 `.../ffmpeg-8.1.3`（`build-ffmpeg-ndk-27d.sh`、`build_ffmpeg_full.sh` 各一处）。

### Phase 2 — 同步 fftools 的 7 个文件（2 h）

从 `ffmpeg-8.1.3/fftools/` 取以下文件，按现有 flatten 规则落到 `android/ffmpeg-kit-android-lib/src/main/cpp/`：

| 上游文件 | 本地目标 | 注意 |
|---|---|---|
| `ffmpeg.h` | `fftools_ffmpeg.h` | 直接替换 |
| `ffmpeg_dec.c` | `fftools_ffmpeg_dec.c` | +11/−2 |
| `ffmpeg_demux.c` | `fftools_ffmpeg_demux.c` | +53/−35，改动最大 |
| `ffmpeg_mux_init.c` | `fftools_ffmpeg_mux_init.c` | +25/−1，新增视频流 disposition 处理 |
| `ffmpeg_opt.c` | `fftools_ffmpeg_opt.c` | +2 |
| `graph/graphprint.c` | `fftools_graphprint.c` | 子目录映射 |
| `opt_common.c` | `fftools_opt_common.c` | **含 108 处自有 printf→av_log，需重新落位** |

`opt_common.c` 的处理建议：先用脚本对上游文件重放 `printf(` → `av_log(NULL, AV_LOG_STDERR, ` 的批量替换（注意 `PRINT_CODEC_SUPPORTED` 宏），再与旧文件 diff，确认除 8.1.3 的 2 行格式化差异外无其他偏离。

### Phase 3 — 版本号与打包（0.5 h）

| 位置 | 修改 |
|---|---|
| `android/ffmpeg-kit-android-lib/build.gradle` | `versionName "8.1"` → `"8.1.3"` |
| `android/ffmpeg-kit-android-lib/src/main/cpp/ffmpegkit.h` | `FFMPEG_KIT_VERSION "8.1"` → `"8.1.3"` |
| 产物 | `ffmpeg-kit-8.1.3.aar`，**不要覆盖** `ffmpeg-kit-8.1.aar` |

构建：`./build-ffmpeg-ndk-27d.sh` → `./build-aar-ndk-r27d.sh`（NDK r27d，WSL2 Ubuntu，aarch64 目标 android24）。

### Phase 4 — 接入 StreamClip（0.5 h）

```kotlin
// app/build.gradle.kts:67
implementation(files("libs/ffmpeg-kit-8.1.3.aar"))
```

AAR 按文件名显式引用，改名必须同步改这一行；`ffmpeg-kit-8.1.aar` 保留在 `libs/` 便于一键回退（不影响打包，仅多占仓库空间，或移入 `libs/archive/`）。

### Phase 5 — 回归验收（2–3 h，真机）

| # | 用例 | 判定标准 |
|---|---|---|
| 1 | 竖拍 4K 无损裁剪（`-c copy`） | 输出可播；ffprobe rotation 与源一致（`-90`）；tkhd 宽高 = 编码尺寸（3840×2160）；**视频流 md5 与源对应区间一致** |
| 2 | 竖拍 720p 无损裁剪（tx=0 矩阵） | 同上，重点看 tx/ty 原样保留 |
| 3 | 两段 mp4 concat 合并 | 时长 = 两段之和 ±1 帧；音画同步；首帧正常 |
| 4 | 提取音频（`-c:a copy`） | 时长与源一致；无爆音 |
| 5 | `hevc_mediacodec` 压缩 + 旋转保持不变 | 方向正确、缩略图非横屏压扁；**依赖 `copyRotation` 生效** |
| 6 | `libx265` CRF 压缩 | 编码成功；色彩元数据与源 VUI 一致 |
| 7 | 音频压缩（mp3lame） | 可播；时长偏差 < 100 ms |
| 8 | 元数据编辑 + 自定义命令 | ffprobe 读取正常；help/错误信息仍能进日志（验证 5 处 stdout→av_log 修改未失效） |

第 8 项是本方案最易被忽略的验收点：8.1.3 改动了 `print_program_info`，若重新落位时漏改，`-version` 输出会重新掉进 stdout。

### Phase 6 — 提交（0.5 h）

- StreamClip：替换 AAR + 更新 `AGENTS.md` 中"AAR 版本 8.1"表述 + `CHANGELOG.md` 记录
- ffmpeg-kit 仓库：打 tag（如 `ffmpeg-8.1.3`），记录 configure 全串

---

## 七、9.x 的触发条件与升级脚手架

### 触发条件（满足任一即启动 C 方案）

1. `n8.1.x` 不再产生新 tag（分支 EOL）
2. 上游出现影响本项目的 movenc / mediacodec 修复且**未回移** 8.1
3. 需要 9.x 独有能力（例如未来做 HDR/杜比视界拆分、CMAF、或 mediacodec 新参数）
4. 安全公告涉及本项目可触达的解封装路径（mp4/mov/mkv）且未回移

### 升级前应准备好的三件事（半天，可提前做）

1. **把 5 处修改固化为 patch 文件**（`tools/patches/ffmpegkit-fftools/*.patch`），用 `git apply` 重放，替代手工改 108 处 printf。当前方式是"改了但没留 patch"，每次升级都要重新人工比对，这是 9.x 移植最大的隐性成本。
2. **目录 flatten 映射表脚本化**：`fftools/*.c` → `fftools_*.c`、`fftools/graph/*.c` → `fftools_*.c`、`fftools/textformat/avtextformat.c` → `fftools_textformat.c`。9.0 之后子目录还会继续调整。
3. **真机回归脚本化**：用 `adb shell` + 固定素材 + ffprobe 断言（rotation/tkhd/时长/md5），把 Phase 5 的 8 项变成一条命令。

完成以上三项后，9.x 移植的实际工时可从 2–4 人日压到 1 人日以内。

---

## 八、附：为什么"等 9.x"不是拖延

- 9.1 开发分支已包含 AAC 编码器"大幅改进"与 H.264 数据分区，这类改动对"压缩页"的音频质量有实际意义；等 9.1 稳定后再一次性移植，比现在先跳 9.0、再跳 9.1 少一轮全量回归。
- 9.0 的 movenc 时间基变更需要额外的时长/时间戳验证成本，而它带来的收益（LCEVC 轨道封装、Playdate、AMF）本项目一条都用不上。
- 8.1.3 已把 9.0 的安全加固拿全，安全水位没有差距。

**净结论：现在做 9.0 是"用两个大版本的回归风险，换零功能收益"。**

---

## 九、决策记录（2026-10-31）

**已采纳：B 方案（跟随 8.1.3）**。详细施工方案已移入 ffmpeg-kit fork 仓库：`D:\3rd-party-projects\ffmpeg-kit\docs\ffmpeg-8.1.3-rebuild-plan.md`（构建总纲同目录 `ffmpeg-build.md`）。

落地方案时对仓库做了实测，补正本评估中的三处推断：

| 项 | 本评估的推断 | 实测事实 |
|---|---|---|
| FFmpeg 8.1 源码形态 | 假定可按 git 仓库 `fetch/checkout` 升级 | `D:\3rd-party-projects\ffmpeg-8.1` 是**单提交 `init` 快照**，无 remote、无 tag，工作树混着上次构建产物 → 改为新落 tarball 到 `ffmpeg-8.1.3`，旧目录保留回退 |
| 本地改造规模 | "5 处修改需重落位" | 实为 **45 个 flatten 文件**中 7 个含实质改造（三类：include 改名 / 输出通道迁移 / 手工钩子），且 8.1→8.1.3 恰好只动其中 7 个 fftools 文件 → 三方合并预期 **0 冲突** |
| 移植工时 | 2–4 人日（针对 9.0） | 8.1.3 为**半天量级**（人工约 2.5h + 机器约 1h） |

另外两项实测进一步压低风险：

- 8.1.3 对 fftools 的三处**行为变更**（readrate 重构、`-t` 非负校验、视频 disposition 继承）在本项目均为**空集**——应用无 `-re`/`readrate`/`disposition`，唯一 `-t` 用法取值为正时长。
- 8.1.3 的 `FF_API_*` 集合与 8.1 完全一致（0/9/5/4），`libavutil` 仅 micro 由 100→103，SONAME `libavutil.so.60` 不变 → 确认是零 API 变更的补丁级发布。
