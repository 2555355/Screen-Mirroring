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
