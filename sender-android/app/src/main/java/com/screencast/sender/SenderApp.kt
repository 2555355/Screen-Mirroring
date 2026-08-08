package com.screencast.sender

import android.app.Application

/**
 * 自定义 Application：在进程启动时第一时间注册全局崩溃处理器。
 * 任何线程的未捕获异常都会被写入 /sdcard/Download/screencast/screencast_crash.log。
 */
class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
