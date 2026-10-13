package com.ai.android.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常捕获：把堆栈写到 filesDir/crash_last.txt，下次启动可读。
 *
 * 用途：诊断页闪退但 logcat 抓不到 FATAL 时，靠这个还原堆栈。
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private const val FILE_NAME = "crash_last.txt"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val f = File(appCtx.filesDir, FILE_NAME)
                val text = buildString {
                    append("Time: ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append("Type: ").append(throwable.javaClass.name).append('\n')
                    append("Message: ").append(throwable.message).append('\n')
                    append('\n')
                    append(throwable.stackTraceToString())
                }
                f.writeText(text)
                Log.e(TAG, "crash captured:\n$text")
            }
            // 继续走默认 handler，让系统弹对话框/杀进程
            default?.uncaughtException(thread, throwable)
        }
    }

    fun readLast(context: Context): String? {
        val f = File(context.filesDir, FILE_NAME)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clearLast(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}