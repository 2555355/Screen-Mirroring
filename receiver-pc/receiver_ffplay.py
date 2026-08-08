"""ScreenCast PC 接收端 · ffplay 备选版。

不依赖 PyAV / PySDL2，仅依赖系统已安装的 ffmpeg/ffplay。
TCP 接收 H.264 帧后剥离协议头，直接 pipe 给 ffplay 播放。

快捷键（ffplay 内置）：f=全屏，q/ESC=退出，方向键=快进（这里无用）。
把 ffplay 窗口拖到副屏后按 f，即可作为扩展屏全屏显示。
"""
from __future__ import annotations

import argparse
import shutil
import socket
import subprocess
import sys

from frame_protocol import iter_frames


def find_ffplay() -> str:
    for name in ("ffplay", "ffplay.exe"):
        path = shutil.which(name)
        if path:
            return path
    sys.exit("[ffplay] 未找到 ffplay，请先安装 ffmpeg。")


def main() -> None:
    ap = argparse.ArgumentParser(description="ScreenCast PC 接收端 (ffplay 版)")
    ap.add_argument("--host", default="0.0.0.0", help="监听地址")
    ap.add_argument("--port", type=int, default=8855, help="监听端口")
    ap.add_argument("--name", default=socket.gethostname(), help="设备名 (用于配对发现)")
    ap.add_argument("--width", type=int, default=1280, help="窗口初始宽度")
    ap.add_argument("--height", type=int, default=720, help="窗口初始高度")
    args = ap.parse_args()

    # 启动配对码发现服务
    from pairing_server import PairingServer
    ps = PairingServer(tcp_port=args.port, device_name=args.name)
    ps.start()
    print("=" * 48)
    print(f"  配对码:  {ps.code}")
    print(f"  在手机端输入此配对码即可连接")
    print("=" * 48)

    ffplay = find_ffplay()

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((args.host, args.port))
    s.listen(2)
    print(f"[ffplay] 监听 {args.host}:{args.port}，等待手机连接 ...")

    while True:
        conn, addr = s.accept()
        print(f"[ffplay] 手机已连接：{addr[0]}:{addr[1]}")
        cmd = [
            ffplay,
            "-fflags", "nobuffer",
            "-flags", "low_delay",
            "-framedrop",
            "-infbuf",
            "-x", str(args.width),
            "-y", str(args.height),
            "-f", "h264",
            "-i", "-",
            "-window_title", "ScreenCast Receiver (ffplay)",
        ]
        ff = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        try:
            for _ts, payload in iter_frames(conn):
                if ff.poll() is not None:
                    break
                try:
                    ff.stdin.write(payload)
                    ff.stdin.flush()
                except (BrokenPipeError, OSError):
                    break
        finally:
            try:
                if ff.stdin:
                    ff.stdin.close()
            except Exception:
                pass
            try:
                ff.wait(timeout=2)
            except Exception:
                ff.kill()
            try:
                conn.close()
            except Exception:
                pass
            print("[ffplay] 手机已断开，等待重连 ...")


if __name__ == "__main__":
    main()
