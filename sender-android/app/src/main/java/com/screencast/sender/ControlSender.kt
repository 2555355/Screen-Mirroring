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

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 触摸板控制通道：通过 TCP 长连接把光标位移 / 点击 / 按键事件推送到 TV 端。
 *
 * 协议（小端字节序）：
 *   - type=1 MOVE ：type(1B) + dx(int16 LE) + dy(int16 LE)，共 5 字节
 *   - type=2 CLICK：type(1B)，1 字节
 *   - type=3 BACK ：type(1B)，1 字节
 *   - type=4 HOME ：type(1B)，1 字节
 *
 * 与 [H264Sender] 区别：连接到控制端口 8856（非视频端口 8855），
 * 消息短小且要求低延迟，每次发送后立即 flush。
 */
class ControlSender {
    @Volatile
    var connected = false
        private set

    /** 最近一次连接失败的错误信息，供 UI 展示。 */
    @Volatile
    var lastError: String? = null
        private set

    private var socket: Socket? = null
    private var outStream: OutputStream? = null

    @Synchronized
    fun connect(host: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 3000): Boolean {
        disconnect()
        lastError = null
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            outStream = s.getOutputStream()
            socket = s
            connected = true
            DiagLog.log("Control", "已连接 $host:$port")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            DiagLog.e("Control", "连接失败 $host:$port - $lastError")
            connected = false
            false
        }
    }

    /**
     * 发送 MOVE 指令：type=1 + dx(int16 LE) + dy(int16 LE)，共 5 字节。
     * dx/dy 为光标位移（像素），超过 Short 范围（-32768~32767）时拆分为多次发送，
     * 避免有符号 16 位溢出。
     */
    @Synchronized
    fun sendMove(dx: Int, dy: Int) {
        if (!connected) return
        val os = outStream ?: return
        var remainingX = dx
        var remainingY = dy
        // 每次最多发一个 Short 范围内的位移，超出则分多次
        while (remainingX != 0 || remainingY != 0) {
            val stepX = remainingX.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val stepY = remainingY.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            // 用 ByteArrayOutputStream 组装消息，手动写小端字节序
            val buf = ByteArrayOutputStream(5)
            buf.write(TYPE_MOVE.toInt())          // type=1
            buf.write(stepX and 0xFF)              // dx 低字节
            buf.write((stepX shr 8) and 0xFF)      // dx 高字节
            buf.write(stepY and 0xFF)              // dy 低字节
            buf.write((stepY shr 8) and 0xFF)      // dy 高字节
            try {
                os.write(buf.toByteArray())
                os.flush()
            } catch (e: IOException) {
                DiagLog.e("Control", "发送 MOVE 失败：${e.message}，断开连接")
                connected = false
                return
            }
            remainingX -= stepX
            remainingY -= stepY
        }
    }

    /** 发送 CLICK（单击）指令：type=2，1 字节。 */
    @Synchronized
    fun sendClick() = writeSingle(TYPE_CLICK, "CLICK")

    /** 发送 BACK 键指令：type=3，1 字节。 */
    @Synchronized
    fun sendBack() = writeSingle(TYPE_BACK, "BACK")

    /** 发送 HOME 键指令：type=4，1 字节。 */
    @Synchronized
    fun sendHome() = writeSingle(TYPE_HOME, "HOME")

    private fun writeSingle(type: Byte, name: String) {
        if (!connected) return
        val os = outStream ?: return
        try {
            os.write(type.toInt())
            os.flush()
        } catch (e: IOException) {
            DiagLog.e("Control", "发送 $name 失败：${e.message}，断开连接")
            connected = false
        }
    }

    @Synchronized
    fun disconnect() {
        connected = false
        try {
            outStream?.flush()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        outStream = null
    }

    companion object {
        /** TV 端控制通道端口（注意：非 8855 视频端口）。 */
        const val DEFAULT_PORT = 8856

        private const val TYPE_MOVE: Byte = 1
        private const val TYPE_CLICK: Byte = 2
        private const val TYPE_BACK: Byte = 3
        private const val TYPE_HOME: Byte = 4
    }
}
