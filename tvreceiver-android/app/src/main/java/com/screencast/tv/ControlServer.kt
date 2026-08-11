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
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 触摸板控制服务：在 [port]（默认 8856）上监听，接收手机端发来的
 * 光标移动 / 点击 / 返回 / Home 消息，并通过回调通知上层。
 *
 * 协议（每条消息以 1 字节 type 开头，小端字节序）：
 * - type=1 MOVE  : dx(int16 LE, 2B) + dy(int16 LE, 2B)，共 5 字节
 * - type=2 CLICK  : 1 字节
 * - type=3 BACK   : 1 字节
 * - type=4 HOME   : 1 字节
 *
 * 每个连接独立线程读取，连接状态通过 [onState] 回调（回调在 IO 线程，
 * UI 更新需切回主线程）。
 */
class ControlServer(
    private val port: Int = 8856,
    private val onMove: (dx: Int, dy: Int) -> Unit,
    private val onClick: () -> Unit,
    private val onBack: () -> Unit,
    private val onHome: () -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        private const val TAG = "ControlServer"
        private const val TYPE_MOVE = 1
        private const val TYPE_CLICK = 2
        private const val TYPE_BACK = 3
        private const val TYPE_HOME = 4
    }

    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ serve() }, "control-server").also { it.start() }
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
            onState("控制端口 $port 监听失败：${e.message}")
            return
        }
        server = ss
        onState("控制端口 $port 已就绪，等待手机连入 ...")
        while (running) {
            val conn = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) onState("control accept 异常：${e.message}")
                break
            }
            onState("控制端已连接：${conn.inetAddress.hostAddress}")
            handleClient(conn)
            if (running) onState("控制端已断开，等待重连 ...")
        }
        try {
            ss.close()
        } catch (_: Exception) {
        }
    }

    private fun handleClient(conn: Socket) {
        try {
            conn.tcpNoDelay = true
            val input = DataInputStream(conn.getInputStream())
            while (running) {
                val type = try {
                    input.readByte().toInt()
                } catch (_: EOFException) {
                    break
                } catch (_: Exception) {
                    break
                }
                when (type) {
                    TYPE_MOVE -> {
                        // dx/dy 是有符号 int16 小端字节序，不能用 readShort()（默认大端）
                        val lo = input.readByte().toInt() and 0xFF
                        val hi = input.readByte().toInt()
                        val dx = (hi shl 8) or lo
                        val lo2 = input.readByte().toInt() and 0xFF
                        val hi2 = input.readByte().toInt()
                        val dy = (hi2 shl 8) or lo2
                        onMove(dx, dy)
                    }
                    TYPE_CLICK -> onClick()
                    TYPE_BACK -> onBack()
                    TYPE_HOME -> onHome()
                    else -> {
                        // 未知类型：跳过本字节，避免污染后续协议
                        Log.w(TAG, "unknown control type=$type, ignore")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "control client error", e)
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
    }
}
