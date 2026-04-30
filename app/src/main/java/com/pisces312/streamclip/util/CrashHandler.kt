package com.pisces312.streamclip.util

import android.content.Context
import kotlin.system.exitProcess

/**
 * 全局崩溃捕获器
 * 捕获未处理异常，保存崩溃日志，然后正常退出
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 保存崩溃日志
        LogCollector.saveCrashLog(context, throwable)

        // 调用默认处理器（让系统显示崩溃对话框或记录）
        defaultHandler?.uncaughtException(thread, throwable)

        // 退出应用
        exitProcess(1)
    }
}
