package com.pisces312.streamclip.util

import com.pisces312.streamclip.R

/**
 * 一个开源组件在「关于 → 开源许可」里的登记项。
 *
 * [version] 必须是**构建时实际使用的版本**，而不是文档里写过的版本：
 * 原生库以 `ffmpeg-kit/src` 下的源码目录与 `libs/ffmpeg-kit-*.aar` 为准，Java 依赖以
 * `app/build.gradle.kts` 为准。升级依赖时同步改这里，否则关于页会撒谎。
 */
data class OssComponent(
    val name: String,
    val version: String,
    val license: String,
    val licenseRes: Int,
    val url: String,
    /** 详情页顶部的补充说明：Maven 坐标、提交号、构建开关等。 */
    val coordinate: String? = null
) {
    /** 列表项文案，例如 `FFmpeg · 8.1.3 — GPL-3.0` */
    val label: String get() = "$name · $version — $license"
}

/**
 * 开源组件清单 —— 关于页「开源许可」的唯一数据源。
 *
 * 收录范围：**实际打包进 APK 的全部开源代码**。
 * - 原生层：编进 `libffmpegkit.so` / `libav*.so` 的静态库
 * - Java 层：`app/build.gradle.kts` 里的 `implementation` 与 `coreLibraryDesugaring`
 *
 * 系统框架（Android SDK）与自研代码不在列。新增依赖时，在 [all] 里补一条，
 * 同时把许可全文放进 `res/raw/`。
 */
object OssComponents {

    /** native 未加载或返回值无法解析时的兜底版本，与当前 AAR 保持一致。 */
    private const val FFMPEG_VERSION_FALLBACK = "8.1.3"

    private val VERSION_PATTERN = Regex("""\d+\.\d+(?:\.\d+)?""")

    /**
     * FFmpeg 版本优先在运行时向 native 层取，换 AAR 时无需改代码；
     * 取不到（native 未加载、ABI 不支持）则回退到编译期已知版本。
     */
    private val ffmpegVersion: String by lazy {
        runCatching {
            com.arthenica.ffmpegkit.FFmpegKitConfig.getFFmpegVersion()
                ?.let { VERSION_PATTERN.find(it)?.value }
        }.getOrNull() ?: FFMPEG_VERSION_FALLBACK
    }

    /**
     * 全部组件，按「原生层 → Java 依赖」排序。
     * 原生层在前，因为它们是 GPL 义务的主要来源。
     */
    fun all(): List<OssComponent> = nativeLibraries + javaDependencies

    /** 编进 `libffmpegkit.so` / `libav*.so` 的第三方静态库。 */
    private val nativeLibraries: List<OssComponent>
        get() = listOf(
            OssComponent(
                name = "FFmpeg",
                version = ffmpegVersion,
                license = "GPL-3.0",
                licenseRes = R.raw.license_ffmpeg,
                url = "https://github.com/FFmpeg/FFmpeg",
                coordinate = "configure: --enable-gpl --enable-nonfree " +
                    "--enable-libx264 --enable-libx265 --enable-libmp3lame --enable-mediacodec"
            ),
            OssComponent(
                name = "FFmpegKit",
                version = "8.1.3",
                license = "LGPL-3.0",
                licenseRes = R.raw.license_ffmpegkit,
                url = "https://github.com/pisces312/ffmpeg-kit",
                coordinate = "pisces312 fork，NDK r27d / aarch64-linux-android24"
            ),
            OssComponent(
                name = "x264",
                version = "164 (eaa68fa)",
                license = "GPL-2.0+",
                licenseRes = R.raw.license_x264,
                url = "https://code.videolan.org/videolan/x264",
                coordinate = "arthenica/x264 stable @ eaa68fa，X264_BUILD 164"
            ),
            OssComponent(
                name = "x265",
                version = "4.2 (b81f650)",
                license = "GPL-2.0+",
                licenseRes = R.raw.license_x265,
                url = "https://bitbucket.org/multicoreware/x265_git",
                coordinate = "multicoreware/x265_git master @ b81f650，X265_BUILD 216"
            ),
            OssComponent(
                name = "LAME",
                version = "4.0",
                license = "LGPL-2.0",
                licenseRes = R.raw.license_lame,
                url = "https://lame.sourceforge.io",
                coordinate = "libmp3lame，configure 需 --disable-decoder"
            ),
            OssComponent(
                name = "cpu_features",
                version = "0.8.0",
                license = "Apache-2.0",
                licenseRes = R.raw.license_cpu_features,
                url = "https://github.com/google/cpu_features",
                coordinate = "google/cpu_features v0.8.0"
            )
        )

    /** `app/build.gradle.kts` 中声明的第三方 Java/Kotlin 依赖。 */
    private val javaDependencies: List<OssComponent>
        get() = listOf(
            OssComponent(
                name = "AndroidX Core",
                version = "1.18.0",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://developer.android.com/jetpack/androidx/releases/core",
                coordinate = "androidx.core:core-ktx:1.18.0"
            ),
            OssComponent(
                name = "AndroidX AppCompat",
                version = "1.7.1",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://developer.android.com/jetpack/androidx/releases/appcompat",
                coordinate = "androidx.appcompat:appcompat:1.7.1"
            ),
            OssComponent(
                name = "AndroidX Activity",
                version = "1.13.0",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://developer.android.com/jetpack/androidx/releases/activity",
                coordinate = "androidx.activity:activity-ktx:1.13.0"
            ),
            OssComponent(
                name = "AndroidX ConstraintLayout",
                version = "2.2.1",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://developer.android.com/jetpack/androidx/releases/constraintlayout",
                coordinate = "androidx.constraintlayout:constraintlayout:2.2.1"
            ),
            OssComponent(
                name = "AndroidX Lifecycle",
                version = "2.10.0",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
                coordinate = "androidx.lifecycle:lifecycle-runtime-ktx:2.10.0\n" +
                    "androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0"
            ),
            OssComponent(
                name = "AndroidX DocumentFile",
                version = "1.0.1",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://developer.android.com/jetpack/androidx/releases/documentfile",
                coordinate = "androidx.documentfile:documentfile:1.0.1"
            ),
            OssComponent(
                name = "Material Components",
                version = "1.13.0",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://github.com/material-components/material-components-android",
                coordinate = "com.google.android.material:material:1.13.0"
            ),
            OssComponent(
                name = "AndroidX Media3",
                version = "1.6.1",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://developer.android.com/jetpack/androidx/releases/media3",
                coordinate = "androidx.media3:media3-exoplayer:1.6.1\n" +
                    "androidx.media3:media3-ui:1.6.1\n" +
                    "androidx.media3:media3-common:1.6.1"
            ),
            OssComponent(
                name = "MP4Parser (isoparser)",
                version = "1.0.6",
                license = "Apache-2.0",
                licenseRes = R.raw.license_apache2,
                url = "https://github.com/sannies/mp4parser",
                coordinate = "libs/isoparser-1.0.6.jar — MP4 容器解析"
            ),
            OssComponent(
                name = "AspectJ Runtime",
                version = "1.7.3",
                license = "EPL-1.0",
                licenseRes = R.raw.license_epl1,
                url = "https://eclipse.dev/aspectj/",
                coordinate = "libs/aspectjrt-1.7.3.jar — MP4Parser 运行时依赖"
            ),
            OssComponent(
                name = "Desugar JDK Libs",
                version = "2.1.4",
                license = "GPL-2.0 (Classpath Exception)",
                licenseRes = R.raw.license_gpl2,
                url = "https://developer.android.com/studio/write/java8-support",
                coordinate = "com.android.tools:desugar_jdk_libs:2.1.4 — core library desugaring"
            )
        )
}
