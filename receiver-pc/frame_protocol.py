"""自定义帧协议解析。

帧格式（与 Android 端 FrameProtocol.kt 对齐）：
    magic(1B=0x55) | payloadLen(4B, big-endian) | timestampUs(8B, big-endian) | payload(N B)

本模块提供两类工具：
1. [H264Stream] —— 把 TCP socket 包装成只吐纯 H.264 字节流的 file-like 对象，
   供 PyAV / ffmpeg 解码。
2. [iter_frames] —— 直接迭代每帧的 (timestamp_us, payload)，便于二次处理。
"""
from __future__ import annotations

import socket
import struct
from typing import Iterator, Optional, Tuple

MAGIC = 0x55
MAGIC_BYTES = b"\x55"
HEADER_SIZE = 13  # 1 magic + 4 len + 8 ts
RECV_CHUNK = 65536


def _recv_exact(conn: socket.socket, n: int) -> Optional[bytes]:
    """从 socket 精确读取 n 字节，连接断开返回 None。"""
    data = bytearray()
    while len(data) < n:
        chunk = conn.recv(min(RECV_CHUNK, n - len(data)))
        if not chunk:
            return None
        data.extend(chunk)
    return bytes(data)


def iter_frames(conn: socket.socket) -> Iterator[Tuple[int, bytes]]:
    """迭代每个 H.264 帧的 (timestamp_us, payload)。

    内部自动按 magic 同步：若流错位会跳过非法字节直到重新对齐。
    连接断开时迭代结束。
    """
    leftover = bytearray()
    while True:
        # 1. 凑齐头部
        while len(leftover) < HEADER_SIZE:
            chunk = conn.recv(RECV_CHUNK)
            if not chunk:
                return
            leftover.extend(chunk)

        # 2. 用 magic 同步
        idx = leftover.find(MAGIC_BYTES)
        if idx == -1:
            # 保留尾部以防 magic 跨包
            leftover = bytearray(leftover[-(HEADER_SIZE - 1):])
            continue
        if idx > 0:
            del leftover[:idx]

        payload_len = struct.unpack(">I", bytes(leftover[1:5]))[0]
        # 防御异常长度
        if payload_len > 8 * 1024 * 1024:
            del leftover[:1]
            continue
        total = HEADER_SIZE + payload_len
        while len(leftover) < total:
            chunk = conn.recv(RECV_CHUNK)
            if not chunk:
                return
            leftover.extend(chunk)

        timestamp_us = struct.unpack(">q", bytes(leftover[5:13]))[0]
        payload = bytes(leftover[HEADER_SIZE:total])
        del leftover[:total]
        yield timestamp_us, payload


class H264Stream:
    """把 TCP socket 包装成只输出纯 H.264 字节的 file-like 对象。

    在内部按帧协议剥离头部，把 payload 顺序写入一个队列，
    read(n) 时按需消费。供 av.open(stream, format='h264') 使用。
    """

    def __init__(self, conn: socket.socket):
        self._conn = conn
        self._gen = iter_frames(conn)
        self._queue = bytearray()
        self._eof = False

    def read(self, n: int = -1) -> bytes:
        if n is None or n < 0:
            # 读全部，直到 EOF
            while self._fill():
                pass
            data = bytes(self._queue)
            self._queue.clear()
            return data

        while len(self._queue) < n and not self._eof:
            self._fill()

        data = bytes(self._queue[:n])
        del self._queue[:n]
        return data

    def _fill(self) -> bool:
        """从 socket 再拉一帧，返回是否还有后续数据。"""
        try:
            ts, payload = next(self._gen)
        except StopIteration:
            self._eof = True
            return False
        self._queue.extend(payload)
        return True

    # PyAV 可能调用
    def seek(self, *args, **kwargs):  # noqa: D401
        raise OSError("H264Stream is not seekable")
