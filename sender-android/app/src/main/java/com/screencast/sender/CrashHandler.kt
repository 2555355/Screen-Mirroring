package com.screencast.sender

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器。
 *
 * 关键作用：App 崩溃时把完整堆栈写到 /sdcard/Download/screencast_crash.log，
 * 用户无需任何工具，闪退后去手机「文件管理 → 下载」即可找到日志文件发给我。
 *
 * 同时仍走默认处理器（让系统正常弹“应用已停止”），不影响原有崩溃流程。
 */
class CrashHandler private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        @Volatile
        private var installed = false

        fun install(context: Context) {
            if (installed) return
            installed = true
            val default = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, default))
            Log.i(TAG, "CrashHandler installed")
        }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("===== ScreenCast Crash =====")
                pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                pw.println("Thread: ${t.name}")
                pw.println()
                e.printStackTrace(pw)
            }
            val text = sw.toString()
            Log.e(TAG, text)
            // 写到 Download 目录，用户可在文件管理中直接看到
            val outDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "screencast")
            if (!outDir.exists()) outDir.mkdirs()
            File(outDir, "screencast_crash.log").writeText(text)
            // 同时写入应用私有目录（Download 公共目录在新版系统可能需要权限，私有目录一定可写）
            try {
                val internal = File(context.filesDir, "screencast_crash.log")
                internal.writeText(text)
            } catch (_: Exception) {
            }
        } catch (_: Throwable) {
            // 尽量不影响默认流程
        }
        // 继续走系统默认处理（弹出“已停止运行”）
        defaultHandler?.uncaughtException(t, e)
    }
}
