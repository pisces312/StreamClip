package com.pisces312.streamclip

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKitConfig

class StreamClipApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Set ffmpeg-kit session history to minimum (1) to prevent potential bugs with size=0
        FFmpegKitConfig.setSessionHistorySize(1)
    }
}
