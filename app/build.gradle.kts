plugins {
    id("com.android.application")
}

android {
    namespace = "com.pisces312.streamclip"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pisces312.streamclip"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "2.2.3"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = project.findProperty("enableMinify")?.toString()?.toBoolean() ?: true
            isShrinkResources = project.findProperty("enableShrinkResources")?.toString()?.toBoolean() ?: true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        // Mp4RotationUtils logs through LogCollector (android.util.Log). Unit tests exercise
        // the real container parsing on host JVM, so Android stubs must return defaults
        // instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    
    // FFmpeg Kit Next 9.0.2 (自编译 ffmpeg-kit-next fork，FFmpeg n9.0.2 + x264/x265/lame3.100/libiconv + MediaCodec)
    // 回退：改回 files("libs/ffmpeg-kit-8.1.3.aar") 并删除下一行 smart-exception 依赖
    implementation(files("libs/ffmpeg-kit-next-9.0.2-arm64.aar"))
    // next AAR 为 Kotlin 实现，POM 声明 api smart-exception-java:0.2.1；files() 不传递依赖，需显式声明，
    // 否则运行期抛 NoClassDefFoundError（见 ffmpeg-kit-next docs/android-aar-build-plan-v9.0.2.md §6.2）
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Media3 for video preview
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-common:1.6.1")
    
    // DocumentFile for SAF
    implementation("androidx.documentfile:documentfile:1.0.1")

    // VideoCompressor native dependencies
    implementation(files("libs/isoparser-1.0.6.jar"))
    implementation(files("libs/aspectjrt-1.7.3.jar"))

    // Host JVM regression tests (see app/src/test/.../Mp4RotationUtilsTest.kt)
    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests. `unitTests.isReturnDefaultValues = true` only stubs the
    // android.jar copy, whose JSONObject returns null from every getter — MediaProbeParser and
    // MediaInfo would then be untestable (or worse, look broken). Test-only; production keeps
    // using the platform implementation.
    testImplementation("org.json:json:20260814")
}
