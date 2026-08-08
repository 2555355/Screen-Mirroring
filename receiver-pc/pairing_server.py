# SPDX-License-Identifier: GPL-3.0-or-later
# Screen-Mirroring - 跨平台手机投屏软件
# Copyright (C) 2025 Screen-Mirroring Contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""配对码发现服务（PC 端）。

与 Android 端 PairingServer / PairingClient 协议一致，基于 UDP 广播：

  手机 → 接收端：  {"v":1,"type":"pair","code":"482913"}
  接收端 → 手机：  {"v":1,"type":"pair_ack","code":"482913","tcp_port":8855,"name":"MyPC","ip":"192.168.1.20"}

接收端启动后会：
1. 在终端打印 6 位配对码。
2. 在后台监听 UDP 8856，收到配对码匹配的询问即回复本机 IP + TCP 端口。
3. 周期性广播 beacon，便于手机端预览可用接收端。

用法：
    from pairing_server import PairingServer
    ps = PairingServer(tcp_port=8855, device_name="MyPC")
    ps.start()   # 阻塞前打印配对码
    ...
    ps.stop()
"""
from __future__ import annotations

import json
import random
import socket
import threading
import time
from typing import Optional

DISCOVERY_PORT = 8856
BEACON_INTERVAL = 2.0


def get_local_ipv4() -> Optional[str]:
    """取本机用于访问公网的 IPv4（UDP 连一下 8.8.8.8，不实际发包）。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return None
    finally:
        s.close()


class PairingServer:
    def __init__(self, tcp_port: int, device_name: str):
        self.tcp_port = tcp_port
        self.device_name = device_name
        self.code = f"{random.randint(0, 999999):06d}"
        self._running = False
        self._recv_thread: Optional[threading.Thread] = None
        self._beacon_thread: Optional[threading.Thread] = None
        self._sock: Optional[socket.socket] = None

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._recv_thread = threading.Thread(target=self._recv_loop, daemon=True)
        self._beacon_thread = threading.Thread(target=self._beacon_loop, daemon=True)
        self._recv_thread.start()
        self._beacon_thread.start()

    def stop(self) -> None:
        self._running = False
        try:
            if self._sock:
                self._sock.close()
        except Exception:
            pass

    # ----------------------------------------------------------- 接收询问
    def _recv_loop(self) -> None:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", DISCOVERY_PORT))
        s.settimeout(0.5)
        self._sock = s
        while self._running:
            try:
                data, addr = s.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                msg = json.loads(data.decode("utf-8", "ignore"))
            except Exception:
                continue
            if msg.get("v") != 1 or msg.get("type") != "pair":
                continue
            if msg.get("code") != self.code:
                continue
            ip = get_local_ipv4() or addr[0]
            ack = json.dumps({
                "v": 1,
                "type": "pair_ack",
                "code": self.code,
                "tcp_port": self.tcp_port,
                "name": self.device_name,
                "ip": ip,
            }).encode("utf-8")
            try:
                s.sendto(ack, addr)
                print(f"[pairing] 已回复配对请求：{addr[0]}:{addr[1]}")
            except Exception as e:
                print(f"[pairing] 回复失败：{e}")

    # ----------------------------------------------------------- 主动广播
    def _beacon_loop(self) -> None:
        while self._running:
            ip = get_local_ipv4()
            if ip:
                beacon = json.dumps({
                    "v": 1,
                    "type": "beacon",
                    "tcp_port": self.tcp_port,
                    "name": self.device_name,
                    "ip": ip,
                }).encode("utf-8")
                try:
                    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    s.sendto(beacon, ("255.255.255.255", DISCOVERY_PORT))
                    s.close()
                except Exception:
                    pass
            time.sleep(BEACON_INTERVAL)
