package com.screencast.tv

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 与发射端 [com.screencast.sender.FrameProtocol] 对齐的接收解析。
 *
 * 帧格式：magic(1B=0x55) | payloadLen(4B BE) | timestampUs(8B BE) | payload
 */
object FrameProtocol {
    const val MAGIC: Byte = 0x55
    const val HEADER_SIZE = 13

    data class Frame(val timestampUs: Long, val payload: ByteArray)
}

/**
 * 从一个 [InputStream] 顺序解析出自定义帧。
 *
 * 内部维护一个累积缓冲，遇到错位的 magic 会自动重新同步，
 * 保证 TCP 流中断点续传后仍能恢复。
 */
class FrameReader(private val input: InputStream) {
    private val buf = ByteArray(4 * 1024 * 1024) // 单帧最大 4MB 足够
    private var bufLen = 0

    fun readFrame(): FrameProtocol.Frame? {
        while (true) {
            val parsed = tryParse() ?: run {
                val n = input.read(buf, bufLen, buf.size - bufLen)
                if (n <= 0) return null
                bufLen += n
                null
            }
            if (parsed != null) return parsed
        }
    }

    private fun tryParse(): FrameProtocol.Frame? {
        if (bufLen < FrameProtocol.HEADER_SIZE) return null
        // magic 同步
        var idx = 0
        while (idx < bufLen && buf[idx] != FrameProtocol.MAGIC) idx++
        if (idx > 0) {
            System.arraycopy(buf, idx, buf, 0, bufLen - idx)
            bufLen -= idx
        }
        if (bufLen < FrameProtocol.HEADER_SIZE) return null

        val payloadLen = ByteBuffer.wrap(buf, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        if (payloadLen < 0 || payloadLen > buf.size - FrameProtocol.HEADER_SIZE) {
            // 异常长度，丢掉这个 magic 字节重新同步
            System.arraycopy(buf, 1, buf, 0, bufLen - 1)
            bufLen -= 1
            return null
        }
        val total = FrameProtocol.HEADER_SIZE + payloadLen
        if (bufLen < total) return null

        val ts = ByteBuffer.wrap(buf, 5, 8).order(ByteOrder.BIG_ENDIAN).long
        val payload = buf.copyOfRange(FrameProtocol.HEADER_SIZE, total)
        System.arraycopy(buf, total, buf, 0, bufLen - total)
        bufLen -= total
        return FrameProtocol.Frame(ts, payload)
    }
}
