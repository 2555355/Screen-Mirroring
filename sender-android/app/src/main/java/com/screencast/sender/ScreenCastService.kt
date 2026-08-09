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
import android.view.Surface
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
        /** 是否使用 H.265/HEVC 编码（默认 H.264）。 */
        const val EXTRA_USE_HEVC = "use_hevc"

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
    private val sender = H264Sender()

    // 编码输出分辨率（= VirtualDisplay 分辨率，直投无旋转）
    private var width = 1280
    private var height = 720
    // VirtualDisplay 的原始分辨率（镜像手机实际屏幕，按 UI 方向）
    private var virtualWidth = 720
    private var virtualHeight = 1280
    private var dpi = 1
    private var bitrate = 4_000_000
    private var fps = 30
    private var maxEdge = 1280
    // 是否使用 H.265/HEVC 编码（true=HEVC, false=AVC/H.264）
    private var useHevc = false

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
        useHevc = intent.getBooleanExtra(EXTRA_USE_HEVC, false)

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
                    val display = wm.defaultDisplay
                    display.getRealMetrics(metrics)
                    dpi = metrics.densityDpi

                    // Display.getRotation() = 系统融合陀螺仪/加速度传感器后的屏幕方向结果
                    //   ROTATION_0   = 竖屏（手机竖持）
                    //   ROTATION_90  = 横屏（手机左横持）
                    //   ROTATION_270 = 横屏（手机右横持）
                    // 不需要自己读 Sensor，也不需要旋转渲染——直接按 UI 方向直投即可：
                    //   横屏 → 输出横屏比例，TV 全屏填满
                    //   竖屏 → 输出竖屏比例，TV 左右自动加黑边（方向不变形）
                    val rotation = display.rotation
                    val isLandscape = rotation == android.view.Surface.ROTATION_90 ||
                            rotation == android.view.Surface.ROTATION_270
                    DiagLog.log("Cast", "陀螺仪方向 rotation=$rotation isLandscape=$isLandscape")

                    // getRealMetrics 返回物理像素（不随 UI 旋转改变），按 UI 方向调整为「所见即所得」
                    var physW = metrics.widthPixels
                    var physH = metrics.heightPixels
                    if (isLandscape && physW < physH) {
                        val t = physW; physW = physH; physH = t
                    } else if (!isLandscape && physW > physH) {
                        val t = physW; physW = physH; physH = t
                    }

                    var srcW = physW
                    var srcH = physH
                    if (maxOf(srcW, srcH) > maxEdge) {
                        val scale = maxEdge.toFloat() / maxOf(srcW, srcH)
                        srcW = (srcW * scale).toInt()
                        srcH = (srcH * scale).toInt()
                    }
                    srcW = (srcW / 2) * 2
                    srcH = (srcH / 2) * 2

                    // 直投：VirtualDisplay 与编码输出都用 UI 所见尺寸，无旋转
                    virtualWidth = srcW
                    virtualHeight = srcH
                    width = srcW
                    height = srcH

                    if (!sender.connect(host, port)) {
                        val reason = sender.lastError ?: "未知原因"
                        Log.e(TAG, "connect failed to $host:$port - $reason")
                        sendState("连接失败：$host:$port\n$reason")
                        stopCast()
                        stopSelf()
                        return@Thread
                    }

                    sendState("已连接 $host:$port，正在投屏")
                    DiagLog.clear()
                    DiagLog.log("Cast", "已连接 $host:$port")
                    DiagLog.log("Cast", "方向=${if (isLandscape) "横屏全屏" else "竖屏(左右黑边)"}")
                    DiagLog.log("Cast", "VirtualDisplay ${virtualWidth}x${virtualHeight} → 编码 ${width}x${height} 直投")
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
        // 编码器：根据 useHevc 选择 H.265/HEVC 或 H.264/AVC，HEVC 不支持时自动回退 AVC
        if (useHevc) {
            val hevcSupported = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos.any { ci ->
                    ci.isEncoder && ci.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) }
                }
            if (!hevcSupported) {
                DiagLog.e("Encoder", "设备不支持 H.265 编码，回退 H.264")
                useHevc = false
            }
        }
        val mime = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        // 关键帧间隔 2s，平衡延迟与容错（过短码率上涨，过长卡顿后恢复慢）
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        // 【低延迟优化】编码端降低端到端延迟
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：编码器前置低延迟模式
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 实时优先级，减少编码器内部缓冲
            format.setInteger("priority", 0)
        }
        // H.264 用 Baseline profile（最低延迟，无 B 帧）
        if (!useHevc) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }

        encoder = MediaCodec.createEncoderByType(mime)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder?.createInputSurface()
        encoder?.start()
        DiagLog.log("Encoder", "已启动 ${if (useHevc) "H.265" else "H.264"} ${width}x${height} ${bitrate}bps ${fps}fps 低延迟")

        // 直投：VirtualDisplay 直接渲染到 encoder.inputSurface，无旋转、无中间渲染
        val displayTarget = inputSurface!!
        DiagLog.log("VDisplay", "直投模式，目标=encoder.inputSurface")

        DiagLog.log("VDisplay", "创建 VirtualDisplay ${virtualWidth}x${virtualHeight} dpi=$dpi")
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenSender",
            virtualWidth, virtualHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            displayTarget, null, null
        )
        if (virtualDisplay == null) {
            DiagLog.e("VDisplay", "createVirtualDisplay 返回 null")
            sendState("投屏失败：无法创建虚拟显示")
            stopCast()
            stopSelf()
            return
        }
        DiagLog.log("VDisplay", "已创建，开始抓屏投屏")

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
                            DiagLog.log("Drain", "首帧已发送 size=${data.size} flags=0x${Integer.toHexString(info.flags)}")
                        } else if (sentFrames % 600 == 0L) {
                            DiagLog.log("Drain", "累计发送 $sentFrames 帧")
                        }
                    } else {
                        codec.releaseOutputBuffer(index, false)
                    }
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    tryAgainCount++
                    // 每 500 次（约 1 秒）日志一次，便于诊断编码器是否收到输入
                    if (tryAgainCount == 500 || tryAgainCount == 3000) {
                        DiagLog.e("Drain", "编码器 ${tryAgainCount} 次无输出（~${tryAgainCount * 2}ms），已发 $sentFrames 帧")
                    }
                    Thread.sleep(2)
                }
            }
        } catch (e: Exception) {
            DiagLog.e("Drain", "drain 异常", e)
        }
        DiagLog.log("Drain", "退出，共发 $sentFrames 帧")
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
