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

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配对码发现服务。
 *
 * 协议（基于 UDP 广播，端口 [DISCOVERY_PORT]）：
 *  - 手机 → 接收端： {"v":1,"type":"pair","code":"482913"}
 *  - 接收端 → 手机： {"v":1,"type":"pair_ack","code":"482913","tcp_port":8855,"name":"客厅电视","ip":"192.168.1.20"}
 *
 * 接收端也周期性主动广播自己的存在（type=beacon），便于手机端列表预览，
 * 但最终连接仍以配对码匹配为准，避免误连到邻居的设备。
 *
 * 注意：需要 ACCESS_WIFI_STATE / ACCESS_NETWORK_STATE 用于拿到本机 IP（multicast lock）。
 */
class PairingServer(
    private val context: Context,
    private val tcpPort: Int,
    private val deviceName: String,
    private val onCodeGenerated: (code: String) -> Unit
) {
    companion object {
        private const val TAG = "PairingServer"
        const val DISCOVERY_PORT = 8856
        private const val BEACON_INTERVAL_MS = 2000L
    }

    @Volatile
    var running = false
        private set

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var beaconThread: Thread? = null
    private val code: String = (100000 + (Math.random() * 900000).toInt()).toString()

    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        onCodeGenerated(code)
        thread = Thread({ receiveLoop() }, "pair-recv").also { it.start() }
        beaconThread = Thread({ beaconLoop() }, "pair-beacon").also { it.start() }
        Log.i(TAG, "pairing server started, code=$code")
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
        socket = null
        thread = null
        beaconThread = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("screencast-pair").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock failed: ${e.message}")
        }
    }

    /** 监听手机发来的配对请求并回复。 */
    private fun receiveLoop() {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(DISCOVERY_PORT))
            socket = s
            val buf = ByteArray(2048)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (e: SocketException) {
                    if (running) Log.w(TAG, "socket closed: ${e.message}")
                    break
                }
                handlePacket(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiveLoop error", e)
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        try {
            val msg = String(packet.data, 0, packet.length)
            val json = JSONObject(msg)
            if (json.optInt("v") != 1) return
            val type = json.optString("type")
            val reqCode = json.optString("code")
            // 只回应配对码匹配的请求
            if (type == "pair" && reqCode == code) {
                val localIp = getLocalIpv4(packet.address) ?: return
                val ack = JSONObject().apply {
                    put("v", 1)
                    put("type", "pair_ack")
                    put("code", code)
                    put("tcp_port", tcpPort)
                    put("name", deviceName)
                    put("ip", localIp)
                }.toString().toByteArray()
                s()?.send(DatagramPacket(ack, ack.size, packet.address, packet.port))
                Log.i(TAG, "pair_ack sent to ${packet.address.hostAddress}:${packet.port}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handlePacket: ${e.message}")
        }
    }

    /** 周期性广播 beacon，便于手机端发现可用接收端列表（不暴露配对码）。 */
    private fun beaconLoop() {
        while (running) {
            try {
                val localIp = getLocalIpv4(InetAddress.getByName("255.255.255.255")) ?: continue
                val beacon = JSONObject().apply {
                    put("v", 1)
                    put("type", "beacon")
                    put("tcp_port", tcpPort)
                    put("name", deviceName)
                    put("ip", localIp)
                }.toString().toByteArray()
                s()?.send(DatagramPacket(beacon, beacon.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            } catch (e: Exception) {
                // 忽略偶发错误
            }
            try {
                Thread.sleep(BEACON_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun s() = socket

    /**
     * 取本机 IPv4。
     * 优先用接收到的 packet 来源 IP 所在网段推断；失败时遍历网络接口。
     */
    private fun getLocalIpv4(hint: InetAddress?): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var best: String? = null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                        val ip = a.hostAddress ?: continue
                        if (best == null) best = ip
                        // 优先选与请求方同网段的
                        if (hint != null && !hint.isLoopbackAddress) {
                            val hintPrefix = hint.hostAddress?.substringBeforeLast(".") ?: ""
                            if (ip.startsWith(hintPrefix)) return ip
                        }
                    }
                }
            }
            return best
        } catch (e: Exception) {
            return null
        }
    }
}
