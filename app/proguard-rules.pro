# ProGuard rules for StreamClip

# FFmpeg Kit
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep ViewBinding classes
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(android.view.LayoutInflater);
}

# Keep classes with JNI / reflection
-keepclassmembers class * {
    native <methods>;
}

# Keep exceptions for crash reporting
-keep public class * extends java.lang.Exception
