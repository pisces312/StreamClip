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
        versionCode = 13
        versionName = "2.2.1"
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
    
    // FFmpeg Kit 8.1 (arthenica)
    implementation(files("libs/ffmpeg-kit-8.1.aar"))

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
}
