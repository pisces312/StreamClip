# 轻剪辑 更新日志

## [2.3.0] - 2026-11-01

### 新增
- **压缩页新增打开视频/所在位置/复制路径/分享按钮，裁剪与合并改用成功弹窗**：压缩完成后的结果卡可一键打开视频、定位文件所在目录、复制路径或分享；无损截取与视频合并完成后改为弹窗提示「截取成功 / 合并成功」，并提供打开与分享入口
- **输入/输出信息卡显示全部第二类元数据**：直接解析 MP4 的 `moov/meta`（mdta `keys` + `ilst`），把厂商相机/镜头键逐条列出，如 `com.honor.lens.model = back_main`、`com.apple.quicktime.model = iPhone 15 Pro`。ffprobe 只暴露 demuxer 映射到的那部分键，直接读容器才能保证「全部」；文本、整数、浮点分别格式化，二进制值只显示前 16 字节预览

### 修复
- **压缩后色彩空间/色域/传输与原片不一致**：源片的容器 `colr` atom 与码流 VUI 是两套独立标签（荣耀样本容器为 bt470bg/bt709/smpte170m，码流 VUI 为 bt709/sRGB/bt709），重编码后 ffmpeg 从码流属性重写容器标签，信息卡读到的三值随之改变。现在编码完成后把源 `stsd` 里的 `colr` box 原样移植进输出（与 mdta `meta` 移植同一手法），输出容器三值恒等于源容器；码流 VUI 保持编码器写出的值，播放行为不变
- **信息卡「色彩空间」一行从不显示**：`probeMediaInfo()` 读的是 ffprobe 的 `colorspace` 键，而 ffprobe 实际输出 `color_space`，取值恒为空串。修正后输入/输出卡片都会显示矩阵（如 `smpte170m` / `bt709`）
- **移除从不生效的色彩参数**：`-color_primaries` / `-color_trc` 属于 ffmpeg 输入侧选项，`-colorspace` 只写矩阵（会让容器与码流各说一半），`-color_range tv` 对全范围源是空操作；实测带与不带这段参数输出逐字节相同，故整体删除。容器标签改由上面的 `colr` 移植负责
- **压缩后丢失相机与镜头信息**：厂商把相机/镜头型号写在 MP4 的 mdta 元数据键里（荣耀 `com.honor.lens.model` / `com.honor.product.model`，iPhone `com.apple.quicktime.make` / `.model` / `.lens_model`）。FFmpeg 的 mov 封装器只写固定的 4cc 白名单（`©nam`/`©mak`/`©mod`/`©xyz` 等），`-map_metadata 0` 无法带出 mdta 键，压缩后这些信息全部丢失。现在编码完成后把源文件的 `meta` box 原样移植进输出 `moov`，同时保留 Android 读取位置所依赖的 `©xyz` atom；单任务与批量任务两条链路都已修复

### 测试
- **补齐色彩与相机元数据的单元测试**：把 ffprobe 的 JSON 解析从 `FFmpegService` 抽到 `MediaProbeParser`（原类依赖 ffmpeg-kit，宿主 JVM 加载不了），新增 `MediaProbeParserTest` 13 例——JSON 取自真实 `ffprobe` 输出，锁死 `color_space` 键名（改回 `colorspace` 立即失败）、`unknown` 归一为空串、`side_data_list` 旋转、多流取首条、mdta 合并；`MediaInfoColorTest` 8 例覆盖三色标签透传、`isHdr` 只看 transfer、`is10bit` 与 `hdrTag` 组合；`Mp4MetadataUtilsTest` 扩到 23 例，新增 64 位 `moov` 的 largesize 改写、Apple 全键保序、UTF-16/整数/浮点解码、NUL 裁剪、二进制预览、`keys` 无 `ilst`、ilst 索引越界、截断文件、源文件缺失、源=目标等边界。单测总数 42 → 76

### 构建
- **ffmpeg-kit 8.1.3 → ffmpeg-kit-next 9.0.2**：FFmpeg 升级到 n9.0.2（`--enable-version3` 取代非自由 `--enable-nonfree`），新增 libiconv 1.17（LGPL-2.1）与显式 `--enable-zlib`；x264 升到 165、x265 4.2、cpu_features 0.11.0；AAR 由 Java 实现换成 Kotlin 实现，会话属性改为显式 getter 调用（两代 AAR 兼容），并新增 smart-exception-java 0.2.1 依赖
- **LAME 升回 4.0**：上一版 8.1.3 已编入 LAME 4.0，next 初始 AAR 回退到 3.100；本次重编 AAR 将 LAME 升回 4.0，与上一版保持一致

## [2.2.3] - 2026-10-31

### 新增
- **完整英文界面**：补齐 `values-en` 全部词条，并抽出约 40 条硬编码中文，系统语言为英语时主页、压缩、合并、截取、提取音频、音频编辑器、设置、关于、日志与错误提示全部显示英文；应用内「语言」选项仍可单独覆盖
- **关于页开源许可清单**：列出打包进 APK 的全部 17 个开源组件，每项显示版本、许可类型，点进去可看源码地址、Maven 坐标/构建开关与许可全文；FFmpeg 版本在运行时向 native 层读取，换 AAR 不用改代码；列表末尾附 GPL 合规声明链接

### 修复
- **Opus 编码必然失败**：内置 `opus` 编码器不自动重采样，源采样率不是 48 kHz（44.1 kHz 最常见）时直接报错。现在 Opus 输出强制重采样到 48 kHz
- **音频编辑器导出失败后留下 0 字节文件**：目标文件在编码返回失败或抛异常时没被删除，现在两条失败路径都会清理
- **语言切换后编码器与旋转选项仍显示中文**：这些下拉项的文字在初始化时就被固定下来，现在改为显示时按当前语言解析

### 构建
- **ffmpeg-kit 8.1 → 8.1.3**：FFmpeg 升级到 n8.1.3 补丁版，`LIBAVUTIL_60` 等 SONAME 未变；AAR 重新自编译，静态链接 libx264 / libx265 / libmp3lame
- **修复新 AAR 一压缩就崩溃**：上游 `compat/android/binder.c` 在 App 进程把 Binder 线程池缩容到 1 会触发 `Binder threadpool cannot be shrunk after starting` 而 abort。AAR 现在只保证线程池已启动，不再设置线程上限
- **关于页的整体许可说明改为 GPL-3.0**：因静态链接 x264 / x265，此前写 LGPL-3.0 不准确

## [2.2.2] - 2026-10-31

### 修复
- **部分手机拍摄的视频压缩后左转 90°**：MP4 容器解析不识别 64 位 box（size 字段为 1）的 `mdat`，遇到这种结构（相机直出常见：`ftyp / free / mdat(64bit) / moov`）就提前中断，找不到视频轨的 tkhd，旋转矩阵复制静默失败。现在优先处理 64 位 size，并按 `hdlr` 的 `vide` 标记识别视频轨
- **批量任务取消按钮无效**：取消只改了列表里的状态，没有通知后台 Service，FFmpeg 继续跑；取消后状态还会被完成回调覆盖成「已完成」。现在取消会真正终止运行中的协程与 FFmpeg 会话，且已取消/已完成状态不会被迟到的回调改写
- **取消排队中的任务后仍会被执行**：取消只改状态未出队，队列取任务时又把状态覆盖回「运行中」。现在终态任务一律移出队列
- **批量任务重试后永远不执行**：重试只把任务放回内存队列，而后台 Service 在队列排空时已自行停止，没有代码重新拉起。现在重试会带上新任务重新启动 Service
- **批量任务中非压缩任务也被改了 MP4 旋转**：音频提取与自定义命令的输出同样执行了 tkhd 旋转写入，现仅对压缩任务执行
- **音频编辑器导出无法取消**：编码会话在阻塞执行结束后才被保存，取消时拿不到运行中的会话。现在执行期间即捕获会话，取消真正生效

### 调整
- **移除批量任务的暂停/恢复**：暂停的语义实际是终止（队列排空即判定完成并停止服务），通知栏也只保留「取消」按钮
- 需要原始 FFmpeg 会话的同步调用（元数据保存、音频导出、波形加载）统一走带会话追踪的执行入口，可被取消

### 构建
- **AGP 9.2.0 → 9.4.0，Gradle 9.4.1 → 9.6.1**：AGP 9.4.0 硬性要求 Gradle ≥ 9.6.0，两者必须同批升级
- **Gradle daemon JVM 由 Java 21 切换到 Java 25**（`gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=25`）。该文件优先级高于 `JAVA_HOME`，仅改 `JAVA_HOME` 不会切换 daemon JVM
- **compileSdk 36 → 37，targetSdk 36 → 37**：对齐 Android 17（API 37）。targetSdk 提升会启用 Android 17 的运行时行为变更，需真机回归
- 详见 `docs/toolchain-upgrade-2026-10-agp94-gradle96-jdk25.md`

## [2.2.1] - 2026-10-31

### 修复
- **压缩后竖屏视频变成横屏**：FFmpeg 8.1 在重编码时不再向输出传播 tkhd display matrix（只有 `-c copy` 走 streamcopy 路径才会传播），旋转选「保持不变」时输出会丢失方向元数据。现在压缩完成后显式把源文件的旋转矩阵复制到输出文件
- **压缩后缩略图横屏且被压扁**：修复写旋转矩阵时错误交换 tkhd 宽高的问题。tkhd 的 width/height 必须保持编码尺寸，方向只由矩阵表达；交换后系统相册会按错误的显示尺寸缩放画面，把竖屏内容塞进横框
- **负角度导致横竖屏判断错误**：Kotlin 中 `-90 % 180` 等于 -90 而非 90，`rotation` 为负值时显示宽高被算反，现已统一归一化后再判断

### 调整
- 移除 `full` / `github` / `store` 三个构建 flavor。三者唯一差异是捐赠入口是否显示二维码，构建任务名与产物路径却因此多一层前缀；捐赠入口现统一显示二维码

## [2.2.0] - 2026-06-23

### 新增
- **无损旋转**：压缩时支持 0°/90°/180°/270° 旋转，通过直接修改 MP4 tkhd display matrix 实现，秒级完成无需重编码
- **音频波形编辑器**：新增音频编辑功能，支持波形可视化、区间选择、裁剪、淡入淡出等操作
- **原生视频压缩**：基于 MediaCodec 的原生压缩方案（开发测试用）

### 修复
- **tkhd 偏移量错误**：修复旋转矩阵写入位置偏差 4 字节的 bug，修正 tkhd version 0/1 的 matrix 和 width/height 偏移计算
- **旋转元数据读取**：修复 ffprobe side_data JSON key 名称错误（`side_data` → `side_data_list`），app 现在能正确显示视频旋转角度
- **FFmpeg 自动旋转冲突**：添加 `-noautorotate` 防止 FFmpeg 对已有旋转元数据的源视频自动旋转像素，避免双重旋转
- **音频编辑器多项修复**：选区逻辑统一、播放同步、拖拽选择、光标位置显示等

## [2.1.2] - 2026-06-22

### 修复
- **缩放后奇数分辨率导致编码失败**：某些视频（如 720×1582）在选择"缩小 2.25×"后高度为奇数（703），导致 HEVC/H264 MediaCodec 编码器初始化失败、输出 0 帧。修复 scale 表达式为 `trunc(iw/factor/2)*2:trunc(ih/factor/2)*2` 确保偶数对齐

## [2.1.1] - 2026-05-14

### 调整
- **应用正式更名为「轻剪辑」**：从原名「剪流」更名，更口语化好记，突出轻量无损秒级特点

## [2.1.0] - 2026-05-13

### 修复
- **ffmpeg-kit 8.1 连续执行崩溃**：回退到自定义编译的 ffmpeg-kit 8.1 AAR，修复 `executeAsync` 连续调用导致的 native crash
- **音频采样率默认改为"复制"**：避免 ffmpeg-kit 8.1 的 `libswresample` 在特定采样率转换场景下触发 SIGSEGV

### 调整
- 视频压缩和音频压缩页面的采样率 spinner 默认选中 "复制"
- `CompressConfig.audioSampleRate` 默认值由 `"44100"` 改为 `"copy"`

## [2.0.0] - 2026-05-12

### 新增
- **国际化（i18n）**：完整中英文切换支持，所有界面和提示双语化
- **标签页排序**：支持自定义标签页顺序，拖拽调整，持久化保存
- **UI 大改造**：移除 Toolbar 标题栏，标签栏上移，右侧菜单按钮，信息卡片换行显示
- **实时进度条**：显示百分比、已用时间、预估剩余时间和输出文件大小
- **批处理队列**：支持多文件批量处理
- **帧率控制**：视频压缩支持自定义输出帧率

### 优化
- 合并 5 次 ffprobe 调用为单次 JSON 请求，大幅提升文件信息加载速度
- 视频信息卡片增加文件大小(MB)显示
- 标签文字支持两行显示，指示器显示当前位置 (1/8)

## [1.6.0] - 2026-05-11

### 升级
- **FFmpeg Kit 升级至 8.1**：从 `com.arthenica` 迁移到 `com.antonkarpenko` fork，包含更多编解码器支持和 bug 修复
- **移除 smart-exception 依赖**：FFmpeg Kit 8.0.0+ 不再依赖此库，减少包体积

### 调整
- ProGuard 规则同步更新，包名从 `com.arthenica.ffmpegkit` 更新为 `com.antonkarpenko.ffmpegkit`
- 错误处理适配新版本 API（移除 `failStackTrace`，使用 `output` 替代）

## [1.5.0] - 2026-05-10

### 新增
- **音频压缩支持音频文件输入**：不仅支持视频，也支持纯音频文件（MP3、FLAC、AAC、Opus）
- **元数据编辑功能**：支持修改视频/音频文件的元数据信息
- **自定义命令增强**：支持 `-progress pipe:1` 实时进度监控

### 修复
- 修复 probeVideoInfo 输出累积问题

## [1.4.0] - 2026-05-09

### 新增
- **二进制 FFmpeg 支持**：添加 BinaryFFmpeg 封装，支持直接调用 FFmpeg 二进制文件
- **FFmpeg 后端切换**：设置中可选择 ffmpeg-kit 或 binary-ffmpeg 后端
- **音频压缩独立标签页**：从视频压缩中分离，支持更多音频编码器（AAC、MP3、FLAC、Opus）
- **无损剪辑界面**：新的无损剪辑交互界面

### 优化
- 视频信息探测增加码率、拍摄日期、位置信息
- 信息卡片合并显示，原视频→输入视频

## [1.3.0] - 2026-05-08

### 修复
- **GPS 元数据丢失**：移除 `-movflags use_metadata_tags` 参数，改用 `-f mov` 强制格式，确保 Android 图库正确识别 GPS 信息

### 新增
- **自定义 FFmpeg 命令**：新增"自定义命令"页面，支持输入任意 FFmpeg 参数

### 优化
- 日志弹窗适配暗色主题

## [1.2.0] - 2026-05-07

### 新增
- 视频压缩功能（硬编码/软编码可选）
- 参数一致性检查
- 文件时间戳恢复
- GPS 元数据保留
- 实时 FFmpeg 日志弹窗
- 捐赠功能
- 使用指南
