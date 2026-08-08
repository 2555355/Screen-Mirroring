package com.screencast.sender

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 自定义帧协议
 * 每个数据帧由固定头部 + 变长 payload 组成：
 *
 *   | magic(1B) | payloadLen(4B, big-endian) | timestampUs(8B, big-endian) | payload(N B) |
 *
 * 其中 magic = 0x55 用于接收端做同步校验。
 */
object FrameProtocol {
    const val MAGIC: Byte = 0x55
    const val HEADER_SIZE = 13

    fun writeHeader(out: ByteArray, payloadLen: Int, timestampUs: Long) {
        out[0] = MAGIC
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(payloadLen)
        ByteBuffer.wrap(out, 5, 8).order(ByteOrder.BIG_ENDIAN).putLong(timestampUs)
    }
}
