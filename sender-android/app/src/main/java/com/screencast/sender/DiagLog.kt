// SPDX-License-Identifier: GPL-3.0-or-later
// Screen-Mirroring - 跨平台手机投屏软件
// Copyright (C) 2025 Screen-Mirroring Contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

package com.screencast.sender

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 屏幕内诊断日志：把投屏各环节关键事件收集起来，主界面实时显示。
 * 用于不方便抓 logcat 时定位问题：用户直接看屏幕就能知道卡在哪一步。
 *
 * 用法：DiagLog.log("环节", "消息")，或 DiagLog.e(...) 表示错误。
 * MainActivity 订阅 [onUpdate] 回调，收到新日志就 append 到 TextView。
 */
object DiagLog {

    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    var onUpdate: (() -> Unit)? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 普通日志。tag 为环节标识（如 "EGL"、"VDisplay"），msg 为具体内容。 */
    fun log(tag: String, msg: String) {
        append("I", tag, msg, null)
    }

    /** 错误日志。 */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        append("E", tag, msg, t)
    }

    /** 清空所有诊断日志。 */
    fun clear() {
        synchronized(lines) {
            lines.clear()
        }
        notifyUi()
    }

    /** 获取当前所有诊断日志（按时间顺序），供 UI 显示。 */
    fun snapshot(): String {
        return synchronized(lines) {
            lines.joinToString("\n")
        }
    }

    private fun append(level: String, tag: String, msg: String, t: Throwable?) {
        val ts = timeFmt.format(Date())
        // 屏幕显示也带上异常 message + stack 第一行，便于无 adb 时定位
        val line = buildString {
            append("$ts $level/[$tag] $msg")
            if (t != null) {
                append("\n  ↳ ")
                append(t.javaClass.simpleName)
                append(": ")
                append(t.message ?: "(no message)")
                // stacktrace 第一行（异常抛出位置）
                val topFrame = t.stackTrace?.firstOrNull()
                if (topFrame != null) {
                    append("\n    at ")
                    append(topFrame.className.substringAfterLast('.'))
                    append('.')
                    append(topFrame.methodName)
                    append('(')
                    append(topFrame.fileName ?: "")
                    append(':')
                    append(topFrame.lineNumber)
                    append(')')
                }
            }
        }
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        // 同时打到 logcat，方便有 adb 时也能看完整 stacktrace
        if (level == "E") Log.e("Diag/$tag", msg, t) else Log.i("Diag/$tag", msg)
        notifyUi()
    }

    private fun notifyUi() {
        // 切到主线程通知 UI 更新
        mainHandler.post { onUpdate?.invoke() }
    }
}
