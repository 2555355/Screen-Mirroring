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

package com.screencast.tv

import android.util.Log
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP 接收服务：在 [port] 上监听，接收手机端推来的 H.264 帧。
 *
 * 每个连接交给 [FrameReader] 解析，解析出的帧通过 [onFrame] 回调；
 * 连接状态通过 [onState] 回调（回调在 IO 线程，UI 更新需切回主线程）。
 */
class ScreenReceiverServer(
    private val port: Int,
    private val onState: (String) -> Unit,
    private val onFrame: (Long, ByteArray) -> Unit
) {
    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ serve() }, "receiver-server").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
    }

    private fun serve() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(port))
        } catch (e: Exception) {
            onState("监听 $port 失败：${e.message}")
            return
        }
        server = ss
        onState("监听端口 $port，等待手机连接 ...")
        while (running) {
            val conn = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) onState("accept 异常：${e.message}")
                break
            }
            onState("手机已连接：${conn.inetAddress.hostAddress}")
            handleClient(conn)
            if (running) onState("手机已断开，等待重连 ...")
        }
        try {
            ss.close()
        } catch (_: Exception) {
        }
    }

    private fun handleClient(conn: Socket) {
        try {
            conn.tcpNoDelay = true
            val input = BufferedInputStream(conn.getInputStream(), 64 * 1024)
            val reader = FrameReader(input)
            while (running) {
                val frame = reader.readFrame() ?: break
                onFrame(frame.timestampUs, frame.payload)
            }
        } catch (e: Exception) {
            Log.e("ReceiverServer", "client error", e)
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
    }
}
