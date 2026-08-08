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
    private val sender = H264Sender()

    private var width = 720
    private var height = 1280
    private var dpi = 1
    private var bitrate = 4_000_000
    private var fps = 30
    private var maxEdge = 1080

    private var drainThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 注意：不要在 onCreate 中调用 startForeground。
        // manifest 声明了 foregroundServiceType="mediaProjection"，
        // 2 参版和 3 参版 startForeground 在没有活跃 MediaProjection 时都会失败
        // (SecurityException) → 系统认为未启动前台服务 → 5 秒超时崩溃。
        // 正确做法：在 onStartCommand 中先 getMediaProjection 拿到 token，
        // 再调用 3 参版 startForeground。onCreate→onStartCommand 间隔毫秒级，
        // getMediaProjection 同步且很快，整个流程远小于 5 秒，不会超时。
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

        // 关键顺序：先 getMediaProjection 拿到活跃会话，再 3 参版 startForeground。
        // manifest 声明了 foregroundServiceType="mediaProjection"（Android 14 必须），
        // 没有活跃 projection 时 startForeground 会抛 SecurityException。
        // getMediaProjection 同步且很快（几毫秒），不会导致 5 秒超时。
        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                sendState("投屏失败：获取 MediaProjection 失败")
                stopCast()
                stopSelf()
                return
            }

            // 已有活跃 MediaProjection，3 参版 startForeground 成功激活 mediaProjection 类型
            startForegroundCompat(withMediaProjectionType = true)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCast()
                    stopSelf()
                }
            }, null)

            // 耗时操作（TCP 连接、编码初始化）放后台线程，避免阻塞主线程导致 ANR/闪退
            Thread {
                try {
                    val metrics = DisplayMetrics()
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.defaultDisplay.getRealMetrics(metrics)
                    dpi = metrics.densityDpi

                    var w = metrics.widthPixels
                    var h = metrics.heightPixels
                    if (maxOf(w, h) > maxEdge) {
                        val scale = maxEdge.toFloat() / maxOf(w, h)
                        w = (w * scale).toInt()
                        h = (h * scale).toInt()
                    }
                    width = (w / 2) * 2
                    height = (h / 2) * 2

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
            // 捕获 SecurityException / IllegalStateException 等，避免闪退
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

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenSender",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        drainThread = Thread({ drainEncoder() }, "h264-drain").also { it.start() }
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        val codec = encoder ?: return
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
                    } else {
                        codec.releaseOutputBuffer(index, false)
                    }
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Thread.sleep(2)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "drain error", e)
        }
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
     * 启动前台服务。
     * @param withMediaProjectionType 是否带 mediaProjection 类型。
     *   调用前必须先通过 getMediaProjection 拿到活跃会话，否则 Android 14 会抛
     *   SecurityException 导致 startForeground 失败 → 5 秒超时崩溃。
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
