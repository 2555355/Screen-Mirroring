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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

/**
 * 投屏服务：用 MediaProjection 抓屏，MediaCodec 编码为 H.264，
 * 再通过 [H264Sender] 经 TCP 推送到接收端。
 */
class ScreenCastService : Service() {

    companion object {
        private const val TAG = "ScreenCastService"
        private const val CHANNEL_ID = "screencast_sender"
        private const val NOTI_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_MAX_EDGE = "max_edge"

        const val ACTION_START = "com.screencast.sender.START"
        const val ACTION_STOP = "com.screencast.sender.STOP"

        /** 投屏状态变更广播：连接成功/失败/断开，UI 据此更新。 */
        const val ACTION_STATE = "com.screencast.sender.STATE"
        const val EXTRA_STATE_TEXT = "state_text"

        @Volatile
        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var rotationRenderer: RotationRenderer? = null
    private val sender = H264Sender()

    // 编码输出为横屏 16:9，配合 RotationRenderer 把竖屏画面旋转 90°
    private var width = 1280
    private var height = 720
    // VirtualDisplay 的原始竖屏分辨率（镜像手机实际屏幕）
    private var virtualWidth = 720
    private var virtualHeight = 1280
    private var dpi = 1
    private var bitrate = 4_000_000
    private var fps = 30
    private var maxEdge = 1280

    private var drainThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 不在此调用 startForeground。正确顺序在 onStartCommand 中：
        // startForeground(mediaProjection类型) → getMediaProjection()。
        // onCreate→onStartCommand 间隔毫秒级，不会触发 5 秒超时。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCast(intent)
            ACTION_STOP -> {
                stopCast()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCast(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            Log.e(TAG, "no projection data")
            sendState("投屏失败：未获取到屏幕授权数据")
            stopSelf()
            return
        }
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 8855)
        if (host.isNullOrEmpty()) {
            sendState("投屏失败：缺少接收端地址")
            stopSelf()
            return
        }
        bitrate = intent.getIntExtra(EXTRA_BITRATE, bitrate)
        fps = intent.getIntExtra(EXTRA_FPS, fps)
        maxEdge = intent.getIntExtra(EXTRA_MAX_EDGE, maxEdge)

        // 【Android 14 关键顺序】参考 forasoft 生产实践：
        // 必须 startForeground(MEDIA_PROJECTION 类型) 在 getMediaProjection 之前。
        // 反过来会抛 SecurityException。startForeground 只是声明前台服务类型，
        // 不要求此刻已有 projection；getMediaProjection 之后才能 createVirtualDisplay。
        try {
            // 1. 先启动 mediaProjection 类型前台服务（防 5 秒超时 + 满足 Android 14）
            startForegroundCompat(withMediaProjectionType = true)

            // 2. 再获取 MediaProjection（此时前台服务已就绪）
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                sendState("投屏失败：获取 MediaProjection 失败")
                stopCast()
                stopSelf()
                return
            }

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCast()
                    stopSelf()
                }
            }, null)

            // 3. 耗时操作（TCP 连接、编码初始化）放后台线程
            Thread {
                try {
                    val metrics = DisplayMetrics()
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.defaultDisplay.getRealMetrics(metrics)
                    dpi = metrics.densityDpi

                    // VirtualDisplay 用手机原始竖屏分辨率镜像内容，
                    // RotationRenderer 会把它旋转 90° 后渲染到横屏 16:9 的编码器输入。
                    var srcW = metrics.widthPixels
                    var srcH = metrics.heightPixels
                    if (maxOf(srcW, srcH) > maxEdge) {
                        val scale = maxEdge.toFloat() / maxOf(srcW, srcH)
                        srcW = (srcW * scale).toInt()
                        srcH = (srcH * scale).toInt()
                    }
                    srcW = (srcW / 2) * 2
                    srcH = (srcH / 2) * 2

                    // 编码输出固定为横屏 16:9（旋转后填满 TV 全屏，类似扩展屏）
                    width = 1280
                    height = 720
                    virtualWidth = srcW
                    virtualHeight = srcH

                    if (!sender.connect(host, port)) {
                        val reason = sender.lastError ?: "未知原因"
                        Log.e(TAG, "connect failed to $host:$port - $reason")
                        sendState("连接失败：$host:$port\n$reason")
                        stopCast()
                        stopSelf()
                        return@Thread
                    }

                    sendState("已连接 $host:$port，正在投屏")
                    // 必须在 startEncoding 之前置 true：drain 线程的 while 循环依赖
                    // isRunning 作为退出条件，若此时仍为 false，drain 线程启动后
                    // 一次循环都不进就退出，编码出的帧永远发不出去 → 接收端无画面。
                    isRunning = true
                    startEncoding()
                } catch (e: Throwable) {
                    Log.e(TAG, "startCast background error", e)
                    sendState("投屏失败：${e.javaClass.simpleName}: ${e.message}")
                    stopCast()
                    stopSelf()
                }
            }.start()
        } catch (e: Throwable) {
            Log.e(TAG, "startCast error", e)
            sendState("投屏失败：${e.javaClass.simpleName}: ${e.message}")
            stopCast()
            stopSelf()
        }
    }

    /** 发送状态广播给 UI（MainActivity 注册接收）。 */
    private fun sendState(text: String) {
        val intent = Intent(ACTION_STATE).putExtra(EXTRA_STATE_TEXT, text)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun startEncoding() {
        // 编码器：输出横屏 16:9 H.264
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder?.createInputSurface()
        encoder?.start()
        Log.i(TAG, "encoder started: ${width}x${height} ${bitrate}bps ${fps}fps")

        // 旋转渲染器：VirtualDisplay(竖屏 virtualW×virtualH) → SurfaceTexture → 旋转90° → 编码器 inputSurface(横屏 16:9)
        val renderer = RotationRenderer(
            codecInputSurface = inputSurface!!,
            inWidth = virtualWidth,
            inHeight = virtualHeight,
            outWidth = width,
            outHeight = height
        )
        renderer.start()
        rotationRenderer = renderer

        // 等待渲染线程初始化完成（inputSurface 就绪），用 CountDownLatch 精确同步
        if (!renderer.awaitReady(3000)) {
            Log.e(TAG, "RotationRenderer init failed or timeout, inputSurface=${renderer.inputSurface}")
            sendState("投屏失败：画面渲染器初始化失败")
            stopCast()
            stopSelf()
            return
        }
        val renderInput = renderer.inputSurface
        if (renderInput == null) {
            Log.e(TAG, "renderer inputSurface is null after awaitReady")
            sendState("投屏失败：渲染输入面为空")
            stopCast()
            stopSelf()
            return
        }
        Log.i(TAG, "renderer ready, creating VirtualDisplay ${virtualWidth}x${virtualHeight}")

        // VirtualDisplay 渲染到 RotationRenderer 的 inputSurface（竖屏原始分辨率镜像）
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenSender",
            virtualWidth, virtualHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            renderInput, null, null
        )
        if (virtualDisplay == null) {
            Log.e(TAG, "createVirtualDisplay returned null")
            sendState("投屏失败：无法创建虚拟显示")
            stopCast()
            stopSelf()
            return
        }
        Log.i(TAG, "VirtualDisplay created, starting drain thread")

        drainThread = Thread({ drainEncoder() }, "h264-drain").also { it.start() }
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        val codec = encoder ?: return
        var sentFrames = 0L
        var tryAgainCount = 0
        try {
            while (sender.connected && isRunning && codec === encoder) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    val buf: ByteBuffer? = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        codec.releaseOutputBuffer(index, false)
                        sender.sendFrame(info.presentationTimeUs, data, data.size)
                        sentFrames++
                        if (sentFrames == 1L) {
                            Log.i(TAG, "first H264 frame sent: size=${data.size} flags=0x${Integer.toHexString(info.flags)}")
                        } else if (sentFrames % 600 == 0L) {
                            Log.i(TAG, "sent $sentFrames frames total")
                        }
                    } else {
                        codec.releaseOutputBuffer(index, false)
                    }
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    tryAgainCount++
                    // 每 500 次（约 1 秒）日志一次，便于诊断编码器是否收到输入
                    if (tryAgainCount == 500 || tryAgainCount == 3000) {
                        Log.w(TAG, "drain: no output for ${tryAgainCount} tries (~${tryAgainCount * 2}ms), sent=$sentFrames")
                    }
                    Thread.sleep(2)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "drain error", e)
        }
        Log.i(TAG, "drain exited, sent=$sentFrames frames, connected=${sender.connected}, running=$isRunning")
        if (isRunning && !sender.connected) {
            isRunning = false
            sendState("已断开：与接收端的连接中断")
            stopCast()
            stopSelf()
        }
    }

    @Synchronized
    private fun stopCast() {
        isRunning = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            rotationRenderer?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        sender.disconnect()
        virtualDisplay = null
        rotationRenderer = null
        encoder = null
        inputSurface = null
        mediaProjection = null
        drainThread = null
    }

    /**
     * 启动前台服务（mediaProjection 类型）。
     * Android 14 正确顺序：startForeground(type=mediaProjection) 在 getMediaProjection 之前。
     * startForeground 只声明前台服务类型，不要求此刻已有 projection；
     * getMediaProjection 之后才能 createVirtualDisplay。
     */
    private fun startForegroundCompat(withMediaProjectionType: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW)
                ch.description = "显示投屏进行中状态"
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
        val title = if (withMediaProjectionType) "投屏中" else "正在准备投屏..."
        val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("正在将屏幕投射到接收端")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (withMediaProjectionType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 3 参版：指定 mediaProjection 类型，要求已有活跃的 MediaProjection
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            // 2 参版：manifest 声明了类型，会使用 manifest 的类型
            startForeground(NOTI_ID, noti)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCast()
    }
}
