package com.screencast.sender

import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 通过 TCP 长连接把 H.264 帧推送到接收端。
 * 发送格式见 [FrameProtocol]。
 */
class H264Sender {
    @Volatile
    var connected = false
        private set

    /** 最近一次连接失败的错误信息，供 UI 展示。 */
    @Volatile
    var lastError: String? = null
        private set

    private var socket: Socket? = null
    private var outStream: OutputStream? = null
    private val header = ByteArray(FrameProtocol.HEADER_SIZE)

    @Synchronized
    fun connect(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        disconnect()
        lastError = null
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.receiveBufferSize = 512 * 1024
            s.sendBufferSize = 512 * 1024
            s.connect(InetSocketAddress(host, port), timeoutMs)
            outStream = s.getOutputStream().buffered(64 * 1024)
            socket = s
            connected = true
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            connected = false
            false
        }
    }

    @Synchronized
    fun sendFrame(timestampUs: Long, data: ByteArray, size: Int) {
        if (!connected) return
        val os = outStream ?: return
        try {
            FrameProtocol.writeHeader(header, size, timestampUs)
            os.write(header, 0, FrameProtocol.HEADER_SIZE)
            os.write(data, 0, size)
            os.flush()
        } catch (e: IOException) {
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
}
