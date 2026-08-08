package com.screencast.tv

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 在指定 [Display] 上全屏显示的投屏 Presentation。
 *
 * 每个 CastPresentation 持有：
 * - 一个全屏 [SurfaceView]
 * - 一个对应的 [H264Decoder]（surface 创建后才启动）
 *
 * 用法：构造后调 [show]，[onReady] 回调里拿到已就绪的 Presentation，
 * 之后把 TCP 收到的 H.264 帧通过 [feed] 喂进来即可在该 Display 上看到画面。
 * 退出时调 [release] 会同时停掉解码器并 dismiss。
 */
class CastPresentation(
    context: Context,
    display: Display,
    private val displayId: Int,
    private val displayName: String,
    private val onReady: (CastPresentation) -> Unit,
    private val onExit: () -> Unit
) : Presentation(context, display) {

    companion object {
        private const val TAG = "CastPresentation"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var tvOverlay: TextView
    private var decoder: H264Decoder? = null
    @Volatile private var surfaceReady = false
    /** 当前 Display 的宽高，用于保持视频比例避免拉伸。 */
    private var displayWidth = 0
    private var displayHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_cast)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        // 保持常亮
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surface)
        tvOverlay = findViewById(R.id.tvOverlay)
        tvOverlay.text = "$displayName · 等待画面 ..."

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startDecoder(holder.surface)
                onReady(this@CastPresentation)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                displayWidth = w
                displayHeight = h
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                stopDecoder()
            }
        })
    }

    /**
     * 当解码器拿到视频真实宽高时回调，据此调整 SurfaceView 布局保持比例（letterbox），
     * 避免竖屏视频被拉伸到横屏 TV 的全屏。
     */
    fun onVideoSizeChanged(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val dispW = if (displayWidth > 0) displayWidth else window?.decorView?.width ?: return
        val dispH = if (displayHeight > 0) displayHeight else window?.decorView?.height ?: return
        if (dispW <= 0 || dispH <= 0) return

        val videoRatio = videoW.toFloat() / videoH
        val dispRatio = dispW.toFloat() / dispH
        val params = surfaceView.layoutParams
        if (videoRatio > dispRatio) {
            // 视频更宽：以宽度为准，高度按比例（上下留黑）
            params.width = dispW
            params.height = (dispW / videoRatio).toInt()
        } else {
            // 视频更高：以高度为准，宽度按比例（左右留黑）
            params.height = dispH
            params.width = (dispH * videoRatio).toInt()
        }
        surfaceView.layoutParams = params
        surfaceView.invalidate()
        Log.i(TAG, "video ${videoW}x${videoH} -> layout ${params.width}x${params.height} on display ${dispW}x${dispH}")
    }

    private fun startDecoder(surface: Surface) {
        if (decoder != null) return
        val d = H264Decoder(surface) { w, h ->
            // 在主线程调整 SurfaceView 布局
            surfaceView.post { onVideoSizeChanged(w, h) }
        }
        d.start()
        decoder = d
        Log.i(TAG, "decoder started on display=$displayId ($displayName)")
    }

    private fun stopDecoder() {
        decoder?.stop()
        decoder = null
    }

    /** 把一帧 H.264 喂给本 Display 的解码器。 */
    fun feed(timestampUs: Long, data: ByteArray) {
        decoder?.feed(timestampUs, data)
    }

    /** 更新左上角浮层文字（如连接状态）。 */
    fun setOverlay(text: String) {
        if (this::tvOverlay.isInitialized) {
            tvOverlay.text = text
        }
    }

    /** 隐藏浮层（开始出图后调用）。 */
    fun hideOverlay() {
        if (this::tvOverlay.isInitialized) {
            tvOverlay.visibility = View.GONE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 在任意 Display 上按 BACK/MENU 都退回主界面选择
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            onExit()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 释放解码器并 dismiss。 */
    fun release() {
        stopDecoder()
        if (!isShowing) return
        try {
            dismiss()
        } catch (_: Exception) {
        }
    }
}
