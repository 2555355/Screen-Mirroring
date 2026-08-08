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

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 配对码发现客户端。
 *
 * 手机输入配对码后，向局域网 UDP 广播 [PairingServer.DISCOVERY_PORT] 发询问：
 *   {"v":1,"type":"pair","code":"482913"}
 * 配对码匹配的接收端回复：
 *   {"v":1,"type":"pair_ack","code":"482913","tcp_port":8855,"name":"客厅电视","ip":"192.168.1.20"}
 *
 * 拿到 ip+tcp_port 后即可发起 TCP 连接，无需用户手填 IP。
 *
 * @return 发现到的接收端信息，超时未发现返回 null。
 */
class PairingClient(private val context: Context) {

    companion object {
        private const val TAG = "PairingClient"
        const val DISCOVERY_PORT = 8856
    }

    data class Receiver(val ip: String, val tcpPort: Int, val name: String)

    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 用配对码查找接收端。
     * @param code 6 位配对码
     * @param timeoutMs 总超时（默认 4s），期间会重复广播询问
     */
    fun discover(code: String, timeoutMs: Int = 4000): Receiver? {
        acquireMulticastLock()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 500
            socket.broadcast = true

            val req = JSONObject().apply {
                put("v", 1)
                put("type", "pair")
                put("code", code)
            }.toString().toByteArray()

            val bcast = InetAddress.getByName("255.255.255.255")
            val buf = ByteArray(2048)

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.send(DatagramPacket(req, req.size, bcast, DISCOVERY_PORT))
                } catch (e: Exception) {
                    Log.w(TAG, "send pair failed: ${e.message}")
                }
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val ack = parseAck(packet, code)
                    if (ack != null) {
                        Log.i(TAG, "pair_ack from ${ack.ip}:${ack.tcpPort} (${ack.name})")
                        return ack
                    }
                } catch (_: SocketTimeoutException) {
                    // 继续重试
                }
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "discover error", e)
            return null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            releaseMulticastLock()
        }
    }

    private fun parseAck(packet: DatagramPacket, expectedCode: String): Receiver? {
        return try {
            val msg = String(packet.data, 0, packet.length)
            val json = JSONObject(msg)
            if (json.optInt("v") != 1) return null
            if (json.optString("type") != "pair_ack") return null
            if (json.optString("code") != expectedCode) return null
            Receiver(
                ip = json.optString("ip").ifEmpty { packet.address.hostAddress ?: return null },
                tcpPort = json.optInt("tcp_port"),
                name = json.optString("name")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("screencast-pair-client").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock failed: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}
