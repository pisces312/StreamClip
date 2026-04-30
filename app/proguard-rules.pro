# ProGuard rules for StreamClip

# FFmpeg Kit
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

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
