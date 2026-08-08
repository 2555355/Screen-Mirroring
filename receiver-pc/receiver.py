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

"""ScreenCast PC 接收端。

工作流程：
1. 在指定端口起 TCP server，等待手机端连接。
2. 连接建立后，用 [H264Stream] 把 socket 包装成纯 H.264 字节流，
   交给 PyAV 解码。
3. 解码出的 RGB 帧放入一个长度为 2 的队列，主线程用 SDL2 渲染。

显示特性：
- 窗口可缩放、可拖动。
- 按 F 切换全屏（SDL_WINDOW_FULLSCREEN_DESKTOP），会全屏到窗口
  当前所在的显示器——把窗口拖到副屏（扩展显示器）后按 F，
  即可作为“扩展屏幕”全屏显示手机画面。
- ESC / Q 退出。

依赖：pip install -r requirements.txt（av、PySDL2、numpy），
另需系统已安装 SDL2 原生库。
"""
from __future__ import annotations

import argparse
import collections
import socket
import sys
import threading
from ctypes import POINTER, byref, c_uint8

import numpy as np

try:
    import av
except ImportError:
    sys.exit("[receiver] 缺少依赖 av，请先 `pip install -r requirements.txt`")

try:
    from sdl2 import (  # noqa: F401  (导入大量 SDL 常量)
        SDL_CreateRenderer,
        SDL_CreateTexture,
        SDL_CreateWindow,
        SDL_Delay,
        SDL_DestroyRenderer,
        SDL_DestroyTexture,
        SDL_DestroyWindow,
        SDL_Init,
        SDL_PollEvent,
        SDL_Quit,
        SDL_RenderClear,
        SDL_RenderCopy,
        SDL_RenderPresent,
        SDL_RendererAccelerated,
        SDL_RendererPresentVSYNC,
        SDL_SetWindowFullscreen,
        SDL_SetWindowSize,
        SDL_TEXTUREACCESS_STREAMING,
        SDL_TEXTUREACCESS_STREAMING,
        SDL_UpdateTexture,
        SDL_WINDOW_FULLSCREEN_DESKTOP,
        SDL_WINDOWPOS_CENTERED,
        SDL_WINDOW_RESIZABLE,
        SDL_WINDOW_SHOWN,
        SDL_Event,
        SDL_PIXELFORMAT_RGB24,
        SDL_INIT_VIDEO,
        SDL_QUIT,
        SDLK_ESCAPE,
        SDLK_f,
        SDLK_q,
    )
except ImportError:
    sys.exit("[receiver] 缺少依赖 PySDL2，请先 `pip install PySDL2`，并安装 SDL2 原生库")

from frame_protocol import H264Stream


class Receiver:
    def __init__(self, host: str, port: int, init_w: int = 1280, init_h: int = 720):
        self.host = host
        self.port = port
        self.init_w = init_w
        self.init_h = init_h
        # 只保留最新 2 帧，降低延迟
        self.frame_queue: collections.deque = collections.deque(maxlen=2)
        self.running = True
        self.client_connected = threading.Event()

    # ------------------------------------------------------------------ 网络
    def serve(self) -> None:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((self.host, self.port))
        s.listen(2)
        print(f"[receiver] 监听 {self.host}:{self.port}，等待手机连接 ...")
        while self.running:
            try:
                conn, addr = s.accept()
            except OSError:
                break
            print(f"[receiver] 手机已连接：{addr[0]}:{addr[1]}")
            self.handle_client(conn)
            print("[receiver] 手机已断开，等待重连 ...")
        try:
            s.close()
        except Exception:
            pass

    def handle_client(self, conn: socket.socket) -> None:
        self.client_connected.set()
        container = None
        try:
            stream = H264Stream(conn)
            container = av.open(stream, format="h264", mode="r")
            for frame in container.decode(video=0):
                if not self.running:
                    break
                img = frame.to_ndarray(format="rgb24")
                if not img.flags["C_CONTIGUOUS"]:
                    img = np.ascontiguousarray(img)
                self.frame_queue.append(img)
        except Exception as e:
            print(f"[receiver] 解码异常/连接结束：{e}")
        finally:
            try:
                if container is not None:
                    container.close()
            except Exception:
                pass
            try:
                conn.close()
            except Exception:
                pass
            self.client_connected.clear()

    # ------------------------------------------------------------------ 显示
    def run_sdl(self) -> None:
        SDL_Init(SDL_INIT_VIDEO)
        window = SDL_CreateWindow(
            b"ScreenCast Receiver",
            SDL_WINDOWPOS_CENTERED,
            SDL_WINDOWPOS_CENTERED,
            self.init_w,
            self.init_h,
            SDL_WINDOW_RESIZABLE | SDL_WINDOW_SHOWN,
        )
        renderer = SDL_CreateRenderer(
            window, -1, SDL_RendererAccelerated | SDL_RendererPresentVSYNC
        )
        texture = None
        tex_w = tex_h = 0
        fullscreen = False

        print("[receiver] 窗口已创建。快捷键：F=全屏，ESC/Q=退出")
        print("[receiver] 提示：把窗口拖到副屏(扩展显示器)后按 F，即可作为扩展屏全屏显示")

        event = SDL_Event()
        while self.running:
            while SDL_PollEvent(byref(event)):
                if event.type == SDL_QUIT:
                    self.running = False
                elif event.type == 0x300:  # SDL_KEYDOWN
                    sym = event.key.keysym.sym
                    if sym in (SDLK_ESCAPE, SDLK_q):
                        self.running = False
                    elif sym == SDLK_f:
                        fullscreen = not fullscreen
                        SDL_SetWindowFullscreen(
                            window,
                            SDL_WINDOW_FULLSCREEN_DESKTOP if fullscreen else 0,
                        )

            img = None
            if self.frame_queue:
                img = self.frame_queue[-1]
                self.frame_queue.clear()
            if img is not None:
                h, w = img.shape[:2]
                if texture is None or tex_w != w or tex_h != h:
                    if texture is not None:
                        SDL_DestroyTexture(texture)
                    texture = SDL_CreateTexture(
                        renderer,
                        SDL_PIXELFORMAT_RGB24,
                        SDL_TEXTUREACCESS_STREAMING,
                        w,
                        h,
                    )
                    tex_w, tex_h = w, h
                    SDL_SetWindowSize(window, w, h)
                ptr = img.ctypes.data_as(POINTER(c_uint8))
                SDL_UpdateTexture(texture, None, ptr, w * 3)
                SDL_RenderClear(renderer)
                SDL_RenderCopy(renderer, texture, None, None)
            SDL_RenderPresent(renderer)
            SDL_Delay(1)

        if texture is not None:
            SDL_DestroyTexture(texture)
        if renderer is not None:
            SDL_DestroyRenderer(renderer)
        if window is not None:
            SDL_DestroyWindow(window)
        SDL_Quit()


def main() -> None:
    ap = argparse.ArgumentParser(description="ScreenCast PC 接收端")
    ap.add_argument("--host", default="0.0.0.0", help="监听地址 (默认 0.0.0.0)")
    ap.add_argument("--port", type=int, default=8855, help="监听端口 (默认 8855)")
    ap.add_argument("--name", default=socket.gethostname(), help="设备名 (用于配对发现)")
    args = ap.parse_args()

    # 启动配对码发现服务，手机端可凭配对码自动连接（无需手填 IP）
    from pairing_server import PairingServer
    ps = PairingServer(tcp_port=args.port, device_name=args.name)
    ps.start()
    print("=" * 48)
    print(f"  配对码:  {ps.code}")
    print(f"  在手机端输入此配对码，或用手机端「扫码连接」扫描下方二维码")
    print("=" * 48)
    # 终端打印 ASCII 二维码，手机可直接扫描
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(ps.code)
        qr.make(fit=True)
        qr.print_ascii(invert=True)
    except ImportError:
        print("[receiver] 如需终端二维码，请 pip install qrcode")

    r = Receiver(args.host, args.port)
    t = threading.Thread(target=r.serve, daemon=True)
    t.start()
    try:
        r.run_sdl()
    finally:
        r.running = False
        ps.stop()
        print("[receiver] 已退出")


if __name__ == "__main__":
    main()
