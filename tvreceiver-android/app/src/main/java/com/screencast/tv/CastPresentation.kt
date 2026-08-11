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
    private lateinit var cursorView: CursorView
    private var decoder: H264Decoder? = null
    @Volatile private var surfaceReady = false
    // TV Display 真实尺寸（surfaceChanged 回调拿到的容器尺寸）
    private var displayW = 0
    private var displayH = 0
    // 视频真实尺寸（从 SPS 解析）
    private var videoW = 0
    private var videoH = 0

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
        cursorView = findViewById(R.id.cursor)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startDecoder(holder.surface)
                onReady(this@CastPresentation)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                displayW = w
                displayH = h
                applyLetterbox()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                stopDecoder()
            }
        })
    }

    private fun startDecoder(surface: Surface) {
        if (decoder != null) return
        // 直投方案：发送端按手机实际方向输出（横屏全屏比例 / 竖屏竖屏比例），
        // TV 端按视频真实宽高 letterbox 居中显示，不拉伸
        val d = H264Decoder(surface) { w, h ->
            videoW = w
            videoH = h
            // 在解码线程回调，需切到 UI 线程调整布局
            surfaceView.post { applyLetterbox() }
        }
        d.start()
        decoder = d
        Log.i(TAG, "decoder started on display=$displayId ($displayName)")
    }

    /**
     * 按视频真实比例调整 SurfaceView 尺寸：
     * - 横屏视频（宽 ≥ 高）：crop 模式，全屏填满 TV，超出部分裁切（类似手机视频播放器全屏）
     * - 竖屏视频（宽 < 高）：letterbox 模式，居中显示，左右留黑边，不裁切不拉伸
     */
    private fun applyLetterbox() {
        if (displayW == 0 || displayH == 0) return
        if (videoW == 0 || videoH == 0) return
        val containerRatio = displayW.toFloat() / displayH
        val videoRatio = videoW.toFloat() / videoH
        val targetW: Int
        val targetH: Int
        if (videoW >= videoH) {
            // 横屏视频：全屏填满（crop），SurfaceView 比容器大，超出部分被裁掉
            if (videoRatio > containerRatio) {
                // 视频更宽 → 填满高度，宽度溢出裁掉左右
                targetH = displayH
                targetW = (displayH * videoRatio).toInt()
            } else {
                // 视频更高 → 填满宽度，高度溢出裁掉上下
                targetW = displayW
                targetH = (displayW / videoRatio).toInt()
            }
        } else {
            // 竖屏视频：letterbox（留黑边），不裁切不拉伸
            if (videoRatio > containerRatio) {
                targetW = displayW
                targetH = (displayW / videoRatio).toInt()
            } else {
                targetH = displayH
                targetW = (displayH * videoRatio).toInt()
            }
        }
        val lp = surfaceView.layoutParams
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW
            lp.height = targetH
            surfaceView.layoutParams = lp
            Log.i(TAG, "scale: video=${videoW}x${videoH} display=${displayW}x${displayH} → surface=${targetW}x${targetH} mode=${if (videoW >= videoH) "crop" else "letterbox"}")
        }
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

    /**
     * 移动触摸板光标：相对位移，自动夹到 View 边界内。
     * 首次调用时光标自动变为可见。
     */
    fun moveCursor(dx: Int, dy: Int) {
        if (!this::cursorView.isInitialized) return
        if (!cursorView.visible) {
            cursorView.visible = true
            cursorView.visibility = View.VISIBLE
        }
        cursorView.moveBy(dx, dy)
    }

    /** 触发一次点击反馈：让光标短暂闪烁。 */
    fun clickCursor() {
        if (!this::cursorView.isInitialized) return
        cursorView.clickCursor()
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
