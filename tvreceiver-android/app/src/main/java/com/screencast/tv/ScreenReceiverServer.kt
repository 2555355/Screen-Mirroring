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
