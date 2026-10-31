# FFmpegKitNext 调研（作者官方延续项目）

> 调研日期：2026-10-31
> 调研对象：`github.com/arthenica/ffmpeg-kit-next`
> 结论摘要：**这是一个真实、活跃、由原作者 tanersener 维护的官方延续项目；它改变了本项目的维护成本结构，但不改变"FFmpeg 9.0 无功能收益"的既有结论。短期不建议迁移，中期应把它作为 9.x 升级的首选基座。**

---

## 一、结论先行

1. **项目属实且活跃**：仓库 2026-06-01 创建，最新 release `v9.0.2`（2026-10-24，对应 FFmpeg 9.0.2），9 个 release，185 stars / 56 forks。旧仓库 `arthenica/ffmpeg-kit` 的 README 已显式指向它，官方定性为 "actively maintained continuation"。
2. **分发模式变了**：**不再发布任何预编译二进制**（Maven Central / CocoaPods / pub.dev / npm 全部停止），只能本地构建。对本项目无实质影响——我们本来就是自编译。
3. **API 保持兼容**：包名仍是 `com.arthenica.ffmpegkit`，Kotlin 重写但暴露 Java 风格 API。本项目用到的 8 类调用逐项核对**全部保留**，`import` 一行都不用改。
4. **最有价值的发现**：本项目当前最大隐性成本——`fftools_opt_common.c` 里 108 处 `printf` → `av_log` 的手工改造——**上游已经完整接管并随每个 FFmpeg 版本持续迁移**（v9.0.2 该文件 109 处 `AV_LOG_STDERR`、0 处 `printf`）。这直接消灭了 `docs/ffmpeg9-upgrade-assessment.md` 第七节点名的"9.x 移植最大的隐性成本"。
5. **但 9.0 的风险判断不变**：movenc 时间基变更、MediaCodec 编码异常（未定论）、以及 next 项目自身的 9.0.0 取消 bug（9.0.1 已修）都直接命中本项目核心功能，仍需全量真机回归。
6. **推荐路线**：短期维持既定 8.1.3 方案（半天量级）；把 next `v9.0.2` 作为 9.x 触发条件满足时的首选基座，届时工时从原估 2–4 人日压到 **0.5–1 人日**（省掉 fftools 重搬 + 108 处 printf 重放）。

---

## 二、项目事实

### 2.1 基本信息（2026-10-31 实测）

| 项 | 值 |
|---|---|
| 仓库 | `arthenica/ffmpeg-kit-next` |
| 作者 | `tanersener`（FFmpegKit / mobile-ffmpeg 原作者） |
| 创建时间 | 2026-06-01 |
| 最新 release | `v9.0.2`，2026-10-24（FFmpeg 9.0.2） |
| Stars / Forks | 185 / 56 |
| Open issues | 4 个（其中 2 个与本项目相关，见 §3.4） |
| 许可证 | LGPL-3.0 默认；`--enable-gpl` 编译 GPL 库后整包变 GPL-3.0 |
| 分发 | **源码 only**，无任何预编译产物 |
| Wiki | 完整（30+ 页，含 Host Support / Building / Protocols） |
| 官方站点 | `arthenica.github.io/ffmpeg-kit-next/` |

### 2.2 Release 时间线

| 版本 | 日期 | FFmpeg | 关键变化 |
|---|---|---|---|
| v6.1.0 | 2026-06-26 | 6.1.2 | 项目起点。Nix 成为主构建；Android API Java→Kotlin；`saf:`→`ffkitsaf:`；新增 `ffkitmem:`/`ffkitstream:`；**转为源码分发**；宣布 API 兼容 FFmpegKit 6.0 |
| v6.1.1 | 2026-06-27 | 6.1.6 | 跟随上游 |
| v7.1.0 | 2026-07-01 | 7.1.5 | 新增 libjxl / liblc3 / libsvtav1 / vvenc |
| v8.1.0 | 2026-07-02 | 8.1.2 | 弃用 32 位 iOS |
| v8.1.1 | 2026-07-28 | 8.1.2 | 新增 Web(Wasm) / visionOS / Linux arm64 / Android Prefab；`getPackageName()` 行为变更；弃用 `isLTSBuild()` |
| v8.1.1.windows | 2026-08-22 | 8.1.x | 新增 Windows 平台（MSYS2/MinGW-w64） |
| v9.0.0 | 2026-08-23 | 9.0.1 | 跟随 FFmpeg 9.0 |
| v9.0.1 | 2026-10-18 | 9.0.2 | **native 平台废弃 named pipe**（改用 buffer/stream）；新增 `shutdown()` API；对齐各平台 `execute()`；修 cancel 失效（#77）等 5 个 issue |
| **v9.0.2** | **2026-10-24** | **9.0.2** | 修 #85；Flutter/RN 也废弃 named pipe |

维护节奏：4 个月 9 个 release，FFmpeg 大版本跟随延迟约 3 周（9.0 发布 8-03 → next 9.0.0 8-23）。

### 2.3 平台覆盖

Android、iOS、iPadOS、macOS、tvOS、visionOS、Linux、Windows、Web(Wasm)、Flutter、React Native。
比旧 FFmpegKit 多出：iPadOS、visionOS、Windows、Web、Linux arm64。

---

## 三、与 FFmpegKit 的差异（对本项目有意义的部分）

### 3.1 API 兼容性逐项核对

本项目（`FFmpegService` / `AudioEncoder` / `MetadataService` / `FFmpegWaveformLoader` / `CustomCommandFragment` / `OssComponents`）实际用到的调用：

| 本项目用法 | next v9.0.2 是否存在 | 说明 |
|---|---|---|
| `FFmpegKit.executeAsync(cmd, complete, log, stats)` | ✅ 保留（4 参重载仍在） | 另有 `executeWithArgumentsAsync` 数组重载，避免引号转义 |
| `FFmpegKit.cancel(sessionId)` | ✅ 保留 | 新增 `session.cancel()` |
| `FFprobeKit.execute(cmd)` | ✅ 保留 | |
| `ReturnCode.isSuccess(rc)` | ✅ 保留 | |
| `session.returnCode` / `session.sessionId` | ✅ 保留 | Kotlin 属性，Kotlin 调用方写法不变 |
| `FFmpegKitConfig.getFFmpegVersion()` | ✅ 保留（L943） | |
| `StatisticsCallback` / `LogCallback` | ✅ 保留 | |
| `saf:` 协议 | ❌ 改名 `ffkitsaf:` | **本项目经 `FileUtils` 转真实路径，未使用该协议 → 零影响** |

结论：**迁移不需要改任何 Kotlin 调用代码**。这是 next 项目"API 兼容 FFmpegKit 6.0"承诺的实际体现。

### 3.2 构建方式变化

| 维度 | 本项目当前（自维护 fork） | next v9.0.2 |
|---|---|---|
| 宿主 | WSL2 Ubuntu（`D:\3rd-party-projects\ffmpeg-kit`） | **Nix 推荐**；直脚本仅 Linux/macOS |
| Windows 原生 | 不支持 | **不支持 Android 目标**（`windows.sh` 只产 Windows 库） |
| WSL2 | 已在用 | 官方称"技术可行但不在测试矩阵" |
| NDK | r27d | r27d（`android-r27d` profile） |
| 默认 ABI | 仅 arm64-v8a | **5 个全开**，需 `--disable-arch-*` 逐个关 |
| 默认 API | 26 | **24**，可 `--api-level=26` |
| 默认外部库 | 无 | 仅 cpu-features |
| 默认 MediaCodec | 开 | **关**，需 `--enable-lib-android-media-codec` |
| 产物 | `app/libs/ffmpeg-kit-8.1.aar` | `prebuilt/bundle-android-aar-24-maven/com/arthenica/ffmpeg-kit-next/9.0.2/*.aar` |
| Maven 坐标 | 本地 file 依赖 | `com.arthenica:ffmpeg-kit-next:9.0.2`（本地 maven repo） |
| minSdk / compileSdk | 26 / 37 | 24 / 34 |
| 16 KB page size | 需自行确认 | 原生支持（v6.1.0 起） |

等价于本项目当前 AAR 的构建命令（推断，需实测）：

```bash
export ANDROID_SDK_ROOT=... ANDROID_NDK_ROOT=...
./android.sh --api-level=26 \
  --disable-arch-arm-v7a --disable-arch-arm-v7a-neon \
  --disable-arch-x86 --disable-arch-x86-64 \
  --enable-gpl \
  --enable-lib-x264 --enable-lib-x265 \
  --enable-lib-mp3lame --enable-lib-opencore-amr \
  --enable-lib-android-media-codec
```

### 3.3 核心收益：fftools 改造由上游接管

`docs/ffmpeg9-upgrade-assessment.md` §4.1 记录本项目把 `fftools/` 整体搬进 JNI 加 `fftools_` 前缀：45 个 flatten 文件、7 个含实质改造，其中 **`opt_common.c` 的 108 处 `printf` → `av_log`** 是每次升级的人工大头。

next 项目的做法不同：保留 FFmpeg 原生 `fftools/` 目录结构（含 `textformat/`、`graph/`、`resources/`），改造由上游维护，并在每个文件头写"Modification history"记录逐版本迁移。实测证据：

| 文件 | next v9.0.2 内容 | 本项目对应改造 |
|---|---|---|
| `fftools/opt_common.c` | 1595 行，`AV_LOG_STDERR` 出现 **109** 次，`printf(` **0** 次 | 需手工重放 108 处 |
| `fftools/textformat/tw_stdout.c` | 存在，头注释写明 "FFmpegKitNext stdout redirection through log callback path preserved" | 整体重写为 av_log writer |
| `ffmpegkit.c` | `av_log_set_callback(ffmpegkit_log_callback_function)` + `set_report_callback(...)` | 等价钩子 |

含义：**迁移到 next 后，本项目的自定义修改面从 7 处降为 0 处（若无非上游需求）**，这是比"跟随新版本"更重要的结构性变化。代价是失去对 fftools 的完全控制权——若未来要加 StreamClip 专有钩子，需以 patch 形式维护在 next 之上。

### 3.4 已知问题（与本项目相关度排序）

| # | 状态 | 标题 | 对 StreamClip 的影响 |
|---|---|---|---|
| 77 | CLOSED（9.0.1 修） | 9.0.0 cancel 不生效，转码跑到自然结束才返回 255 | **高**。取消是核心功能。标签含 v8.1.0/8.1.1，但正文只在 FFmpeg 9.0.1 复现，v8.1.x 是否受影响存疑 → 必须实测 |
| 51 | OPEN | MediaCodec 编码产物异常（软解+硬编同样复现） | **高**。硬编压缩是核心功能。作者明确"不修改 MediaCodec 实现，问题属上游 FFmpeg 或设备 codec"，尚未定论 |
| 52 | CLOSED | v8.1.1 session complete callback 不触发 | 中。本项目依赖 complete callback 驱动状态机 |
| 62 | CLOSED（9.0.1 修） | macOS 宿主 Nix 构建 `gcc not found` | 低。WSL2/Linux 宿主不受影响 |
| 76 | OPEN | `-tls_verify` 对 ts playlist 失败 | 无。本项目不涉及网络串流 |
| 65 | OPEN | Flutter webm 透明背景 | 无 |

### 3.5 许可与合规

- next 默认 LGPL-3.0；启用 `--enable-gpl` + x264/x265 后整包 GPL-3.0，与本项目当前 AAR 一致（`GPL_COMPLIANCE.md` 无需改动）。
- 差异点：next 会**强制** `--enable-version3`，且"永不启用 non-free 库"，这点比旧项目更严格。

---

## 四、三条路线对比

| 方案 | 基座 | FFmpeg | 工作量 | 收益 | 风险 |
|---|---|---|---|---|---|
| **A. 维持既定 8.1.3** | 自维护 fork | 8.1.3 | 0.5 天（已规划） | 拿齐 mov 解封装安全补丁；零 API 变更 | 维护成本结构不变：下次升级仍需重放 108 处 printf |
| **B. 迁 next v9.0.2** | ffmpeg-kit-next | 9.0.2 | 2–3 天（较原估 2–4 天减少，因 fftools 免重搬）+ 全量回归 3 h | 上游接管 fftools；持续维护；`ffkitmem`/`ffkitstream`；16 KB page；Nix 可复现构建 | movenc 时间基变更（§4.1 已列）；#51 MediaCodec 未定论；#77 需实测 |
| C. 迁 next v8.1.1 | ffmpeg-kit-next | 8.1.2（可自升 8.1.3） | 1–1.5 天 | 拿到新构建基础设施 + 上游接管改造，同时回避 9.0 风险 | **8.1 线在 next 项目已停更**（v8.1.1 后再无 8.1 release）；缺 9.0.1 的 cancel 修复与 `shutdown()` |

**建议**：A 现在就做（已定案，别改）；B 作为 9.x 触发条件满足时的默认路径；C 仅作为 PoC——用半天验证"WSL2 能否编出可用的 arm64-only AAR"，为 B 铺路。

> 关键判断：next 项目**不改变**"9.0 无功能收益"的结论（`ffmpeg9-upgrade-assessment.md` §3 的能力面分析依然成立），它改变的是**届时升级的边际成本**。因此不应因为看到新项目就提前跳 9.0。

---

## 五、若走 B 路线：施工清单（草案）

### Phase 0 — 基座验证（0.5 天，可提前做）

1. WSL2 内装 Nix（`sh <(curl -L https://nixos.org/nix/install) --daemon`），或直接用非 Nix 直脚本（已有 NDK r27d）。
2. `git clone --depth 1 -b v9.0.2 https://github.com/arthenica/ffmpeg-kit-next`，按 §3.2 命令编 arm64-only AAR。
3. 断言产物：`unzip -l *.aar | grep jni/` 只有 `arm64-v8a`；`ffmpeg -version` 的 `configuration:` 串逐字比对当前 8.1 AAR，确认 `--enable-mediacodec`、`--enable-gpl`、`--enable-libx264/x265/mp3lame` 齐全。

### Phase 1 — 代码接入（0.5 天）

4. `app/build.gradle.kts` 换依赖：`implementation(files("libs/ffmpeg-kit-next-9.0.2.aar"))` 或本地 maven。
5. 检查 `consumer-rules.pro` → 合并 AAR 自带 ProGuard 规则（next 已提供）。
6. 编译：预期零 Kotlin 改动（§3.1）。若有编译错，优先怀疑 Kotlin 属性/getter 差异，而非 API 缺失。

### Phase 2 — 回归（1 天）

7. 复用 `ffmpeg9-upgrade-assessment.md` §Phase 5 的 8 项用例，追加 4 项 next 专项：

| # | 用例 | 判定 |
|---|---|---|
| 9 | 压缩中途点取消（硬编 + 软编各一次） | 5 s 内终止，返回 255，UI 状态回到 idle（验证 #77 未复发） |
| 10 | `hevc_mediacodec` 硬编产物 | 可播、时长正确、无花屏（验证 #51 未命中本机机型） |
| 11 | 连续 3 次 `executeAsync` 并发/串行 | session 回调不丢（验证 #52 未复发） |
| 12 | `-version` / `-h` 输出进日志面板 | 验证上游 `tw_stdout` 重定向对自定义命令页有效 |

### Phase 3 — 收尾（0.5 天）

8. 更新 `AGENTS.md` 的 FFmpeg 版本段落、`GPL_COMPLIANCE.md` 的来源说明（源码改为 ffmpeg-kit-next v9.0.2）。
9. 保留旧 AAR 在 `libs/archive/` 便于回退。

总工时：**2–3 天 + 3 h 真机回归**。

---

## 六、待验证项（本报告未实测）

| 项 | 原因 | 建议验证方式 |
|---|---|---|
| WSL2 + Nix 编 Android 是否真能跑通 | 官方明确"不在测试矩阵" | 半天 PoC |
| `--enable-lib-*` 的确切 flag 名 | help 输出只确认了 media-codec/arch/api-level | `./android.sh --help` 全量输出 |
| next 的 AAR 是否仍依赖 `smart-exception-java` | v9 删除了 `ffmpegkit_exception.c`，但 README 仍提 POM 声明该依赖 | 解压 POM 查看 |
| v8.1.x 是否真受 #77（cancel 失效）影响 | issue 标签与正文不一致 | 若走 C 路线，必测 |
| next 是否支持替换 FFmpeg 源码到非 tag 版本 | 影响"C 路线自升 8.1.3"的可行性 | 查 `scripts/function.sh` 的下载逻辑 |
| #51 MediaCodec 异常的设备相关性 | 作者未定论 | B 路线 Phase 2 第 10 项 |

---

## 七、功能与架构对比：是否比老 kit 更好

> 判断原则：区分两类改进——(a) next 项目自身的包装层设计，(b) 跟随 FFmpeg 新版本自动获得的改进。
> 只有 (a) 才算"项目比项目更好"；(b) 属于版本红利，老 kit 若升级 FFmpeg 也能拿到。

### 7.1 架构：明确更好，且有本项目亲历的故障作证

#### ① 并发模型——从"全局状态 + 手工清理"变成"线程局部隔离"

这是最本质的差异。老架构把 FFmpeg `fftools` 的进程级全局变量直接暴露在库进程里，多次执行靠 `ffmpeg_cleanup()` 重置。本项目踩过的坑（`docs/ffmpeg-kit-8.1-double-execute-crash.md`）正是：第一次执行 `av_freep(&input_files)` 后 `nb_input_files` 未归零，第二次执行在 `map_auto_video()` 解引用悬空指针 → `SIGSEGV`。修复方式是在 `fftools_ffmpeg.c` 里手工补 `nb_input_files = 0`。

next 的做法是系统性重构：

| 层 | 本项目当前 fork | next v9.0.2 |
|---|---|---|
| JNI 层 | `__thread long globalSessionId`（1 个） | 同 + 完整 session 状态 |
| `fftools` 层 | **0 个 `__thread`**，全为进程全局 | 20+ 个变量声明为 `__thread`（见 `ffmpeg_context.h`） |
| 调度器 worker 线程 | 无处理 | `saveFFmpegContext()` / `loadFFmpegContext()` 显式传播（142 处字段拷贝） |
| libavutil 日志级别 | 全局 | `av_log_level` 由构建脚本改为 `__thread` |
| 并发语义 | API 允许并发，底层不安全 | 每 session 线程独立状态，可真正并发 |

next 的 `ffmpeg_context.c` 头部注释把这个设计的不变量写得很清楚：FFmpeg 7.x 起调度器会把一次调用 fan-out 到 demux/decode/filter/encode/mux 多个 worker 线程，新 pthread 的 TLS 是**零初始化**而非继承父线程，因此必须 save/load。这条注释本身就是老 kit 缺失的工程资产。

需要说明：next 在这条路径上也付出了复杂度代价——每新增一个 `__thread` 全局，都必须同时加进 save 和 load 两处，漏一处就是"只在多线程下偶发"的隐蔽 bug。作者已在注释里把它列为升级检查项，但这是持续的维护负担。

#### ② fftools 维护方式——从"人工移植"变成"上游追踪"

| 维度 | 本项目当前 fork | next |
|---|---|---|
| 目录结构 | 45 个文件 flatten 成 `fftools_*.c` | 保留 `fftools/`、`fftools/textformat/`、`fftools/graph/` 原结构 |
| `printf` → `av_log` | 108 处手工改，无 patch 文件 | 上游已做（109 处 `AV_LOG_STDERR`、0 处 `printf`） |
| 版本迁移记录 | 无 | 每个文件头带 "Modification history"，逐版本记 |
| 升级动作 | 三方合并 + 重放改造 | 换 FFmpeg 源码 + 核对 `__thread` 清单 |
| 对上游的偏离 | 高（结构 + 输出通道都改了） | 低（结构不动，只加 TLS 与输出重定向） |

#### ③ 构建系统

| 维度 | 老 kit | next |
|---|---|---|
| 依赖管理 | 手工安装 Autotools/pkg-config/NDK | Nix flake 固定 profile（`android-r27d`），`nix develop` 进环境 |
| 可复现性 | 依赖宿主环境，作者自述"build failures"是主要动机 | flake.lock 锁定输入 |
| 宿主矩阵 | 文档化弱 | 明确到"哪个脚本在哪个宿主可用"，并声明 WSL 不在测试矩阵 |
| CI | 无官方模板 | v9.0.1 起提供 GitHub Actions 示例 |

#### ④ API 层

| 维度 | 老 kit | next |
|---|---|---|
| Android 语言 | Java | Kotlin（暴露 Java 风格 API，Kotlin 调用方可用属性语法） |
| 命令传参 | 仅命令字符串，路径含空格/引号需自行转义 | 新增 `executeWithArguments()` / `...Async()` 数组重载 |
| 会话历史 | `getSessions()` 等 | 扩展 `getAllLogs()` / `getAllStatistics()` / `getSessionsByState()`，明确区分"已投递日志"与"等待在途日志" |
| 生命周期 | 无 | 新增 `FFmpegKitConfig.shutdown(timeout)` 永久关闭 |
| 取消 | `cancel()` / `cancel(id)` | 同 + `session.cancel()` |
| 内存 I/O | 无（只能落临时文件） | `ffkitmem:`（可 seek 定长）/ `ffkitstream:`（不可 seek 流式） |
| SAF | `saf:` | `ffkitsaf:`（改名，语义增强：可复用 URL + 显式 `unregisterSafProtocolUrl()`） |

### 7.2 功能：能力面更宽，但分发方式明显变差

**新增能力（对普通用户是净增益）**

| 能力 | 说明 | 对本项目价值 |
|---|---|---|
| `ffkitmem:` | 字节数组/ByteBuffer 直通 FFmpeg 输入输出 | 中。音频编辑导出可省临时 WAV 往返；波形加载可省临时文件 |
| `ffkitstream:` | 边跑边喂/边取，不可 seek | 低。本项目无流式场景 |
| `shutdown()` | 进程退出前彻底回收 native 资源 | 低-中。可降低进程残留风险 |
| `executeWithArguments` | 数组传参，免转义 | 中。自定义命令页与含空格路径更安全 |
| 16 KB page size | v6.1.0 起原生支持 | 中。NDK r27d 自编译大概率已满足，属持平而非独有 |
| Prefab payload | AAR 可被 CMake/native 消费 | 无。本项目不消费 |
| 新平台 | visionOS / Web(Wasm) / Windows / Linux arm64 | 无 |
| Kotlin API | 空安全、属性访问 | 低-中。可读性提升，但不改变调用方式 |

**倒退或削弱（必须计入）**

| 项 | 影响 |
|---|---|
| **不发预编译包** | 对"只想 `implementation 'com.arthenica:ffmpeg-kit:...'` 的用户是显著倒退。对本项目无影响（本来自编译） |
| **named pipe 废弃**（9.0.1） | 依赖 `pipe:` 协议做流式处理的应用必须改到 buffer/stream API |
| **8.1 线停更** | 想回避 FFmpeg 9.0 风险就没有长期维护的版本可选，形成"要么跟 9.x、要么回到自维护"的二选一 |
| **单人维护** | bus factor = 1，与老 kit 相同；但老 kit 至少有大量社区 fork 分担 |
| **仍需 ndk-build** | Android 侧仍用 `Android.mk` 而非 CMake，与现代 AGP/NDK 生态略脱节（`--prefab` 只是缓解） |

### 7.3 综合判定

| 维度 | 判断 | 依据强度 |
|---|---|---|
| 并发/线程安全架构 | **明显更好** | 强（代码级证据 + 本项目故障史） |
| fftools 可维护性 | **明显更好** | 强（109 vs 108 处的直接对照） |
| 构建可复现性 | **更好** | 中（Nix 是设计目标，但 WSL 不在测试矩阵） |
| API 表达力 | **更好** | 强（新增 5 类 API） |
| 分发与上手成本 | **明显更差** | 强（源码 only） |
| 版本可选性 | **更差** | 中（8.1 停更，被迫二选一） |
| 稳定性口碑 | **持平偏弱** | 中（4 个月 9 个 release，仍处早期；#51/#52/#77 均为核心路径问题） |

**一句话结论**：next 在"架构是否先进"上是明确胜出的——它解决了老 kit 最根本的并发状态污染问题，并把 fftools 移植这个最大的维护负担收归上游；但它在"是否更好用"上是退步的——源码分发、8.1 停更、早期稳定性都提高了使用门槛。

对 StreamClip 而言，这套架构优势只在**真正并发执行**和**跟进新 FFmpeg 版本**两个场景下才兑现。本项目当前是单任务串行 + 已定 8.1.3 基线，因此架构优势是"期权价值"而非"当期收益"。

### 7.4 架构优势真正兑现的条件

1. 需要同时跑多个 FFmpeg 会话（如批量队列改为真并发）——当前 `TaskQueueManager` 是串行，不触发。
2. 需要跟进 FFmpeg 9.x/10.x——届时 fftools 移植成本会随版本差指数上升。
3. 需要内存直通 I/O 以消除临时文件中转——音频编辑器的导出/波形链路可受益。

---

## 八、附：与既有评估文档的关系

| 既有结论（`ffmpeg9-upgrade-assessment.md`） | 本次调研后的状态 |
|---|---|
| 9.0 对六类核心操作无能力增益 | **不变**。next 的能力面与 FFmpeg 9.0 一致，未引入编码/封装增强 |
| 9.0 movenc 行为变更是净风险 | **不变**。next 用原生 fftools，行为即上游 9.0.2 行为 |
| 移植 n9.0.2 需 2–4 人日 | **下调至 2–3 天**（fftools 免重搬、108 处 printf 免重放），但回归范围不变 |
| "9.x 移植最大的隐性成本是没留 patch" | **该项被上游消除**。前提是不再加 StreamClip 专有钩子 |
| 推荐 B 方案跟随 8.1.3 | **维持**。本次调研不构成提前升级的理由 |
