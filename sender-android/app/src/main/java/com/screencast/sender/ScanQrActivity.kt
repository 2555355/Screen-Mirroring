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

import android.app.Activity
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码扫描界面：用 Camera1 + SurfaceView 预览，ZXing core 解码。
 *
 * 关键点：
 * 1. setDisplayOrientation(90) 让竖屏预览方向正确（仅旋转显示，不旋转数据）
 * 2. ZXing 只用 Y（亮度）平面，所以只旋转 Y 平面即可
 * 3. 限制预览分辨率在 640x480 以内：1280x720 的 Y 平面 ZXing 解码耗时数百 ms，
 *    会导致 callback 大量丢帧、用户感觉"扫了没反应"
 * 4. 0/90/180/270 四个方向都尝试解码：不同设备 sensorOrientation 不同，全试一遍最稳
 * 5. 异步解码（独立线程 + 单 buffer 复用），避免阻塞 preview callback
 */
@Suppress("DEPRECATION")
class ScanQrActivity : Activity() {

    companion object {
        private const val TAG = "ScanQrActivity"
        const val EXTRA_RESULT = "scan_result"
        /** 单次解码最长耗时 ms，超过视为性能问题。 */
        private const val DECODE_SLOW_MS = 300L
    }

    private var camera: Camera? = null
    private lateinit var previewView: SurfaceView
    private lateinit var tvScanStatus: TextView

    /** 相机预览分辨率（传感器原生方向，通常 width > height 横屏）。 */
    private var previewWidth = 0
    private var previewHeight = 0

    /** SurfaceView 容器（屏幕）尺寸，用于计算居中裁剪比例。 */
    private var containerWidth = 0
    private var containerHeight = 0

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        ))
    }

    /** 解码线程：独立线程处理，避免阻塞 camera preview callback。 */
    private val decodeThread = DecodeThread()
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 预览 buffer 池：setPreviewCallbackWithBuffer 用，避免每帧分配新 buffer。 */
    private val previewBuffers = mutableListOf<ByteArray>()

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            try {
                camera = Camera.open().also { cam ->
                    cam.setPreviewDisplay(holder)
                    // 竖屏显示：将传感器横屏画面旋转 90°
                    cam.setDisplayOrientation(90)

                    val params = cam.parameters
                    // 限制预览尺寸 ≤ 640x480：ZXing 解码速度对像素数敏感，
                    // 1280x720 会拖慢到每帧数百 ms，导致大量丢帧
                    val screenRatio = containerWidth.toFloat() / containerHeight.toFloat()
                    val best = choosePreviewSize(params.supportedPreviewSizes, screenRatio)
                    if (best != null) {
                        params.setPreviewSize(best.width, best.height)
                        previewWidth = best.width
                        previewHeight = best.height
                    }
                    DiagLog.log("Scan", "预览尺寸 ${previewWidth}x${previewHeight}")
                    // 持续对焦，提升二维码识别率
                    val modes = params.supportedFocusModes
                    when {
                        modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                            params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                        modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                            params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                        modes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ->
                            params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                    }
                    cam.parameters = params

                    decodeThread.start()

                    // 用 setPreviewCallbackWithBuffer 主动管理 buffer，比 setPreviewCallback 更稳定
                    // 池大小 2：一个正在被 camera 写，一个正在被解码线程读
                    val bufSize = previewWidth * previewHeight * 3 / 2
                    previewBuffers.clear()
                    for (i in 0 until 2) {
                        val buf = ByteArray(bufSize)
                        previewBuffers.add(buf)
                        cam.addCallbackBuffer(buf)
                    }
                    cam.setPreviewCallbackWithBuffer { data, _ ->
                        // 拿到一帧，丢给解码线程；同时把 buffer 还回去
                        if (data != null && data.size >= previewWidth * previewHeight) {
                            decodeThread.submit(data, previewWidth, previewHeight)
                        }
                        cam.addCallbackBuffer(data)
                    }
                    cam.startPreview()
                    DiagLog.log("Scan", "预览已启动，开始解码")
                }
            } catch (e: Exception) {
                Log.e(TAG, "open camera failed", e)
                DiagLog.e("Scan", "打开相机失败: ${e.message}")
                finish()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            if (containerWidth == 0 || containerHeight == 0) {
                containerWidth = w
                containerHeight = h
            }
            adjustPreviewSize()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            releaseCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        previewView = findViewById(R.id.preview)
        tvScanStatus = findViewById(R.id.tvScanStatus)

        val dm = resources.displayMetrics
        containerWidth = dm.widthPixels
        containerHeight = dm.heightPixels

        previewView.holder.addCallback(surfaceCallback)
    }

    /**
     * 调整 SurfaceView 尺寸，使预览画面居中裁剪填满屏幕且不拉伸。
     */
    private fun adjustPreviewSize() {
        if (previewWidth == 0 || previewHeight == 0) return
        if (containerWidth == 0 || containerHeight == 0) return

        val rotatedRatio = previewHeight.toFloat() / previewWidth.toFloat()
        val screenRatio = containerWidth.toFloat() / containerHeight.toFloat()

        val targetW: Int
        val targetH: Int
        if (screenRatio > rotatedRatio) {
            targetW = containerWidth
            targetH = (containerWidth / rotatedRatio).toInt()
        } else {
            targetH = containerHeight
            targetW = (containerHeight * rotatedRatio).toInt()
        }

        val lp = previewView.layoutParams
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW
            lp.height = targetH
            previewView.layoutParams = lp
        }
    }

    /**
     * 从支持的预览尺寸中选择最匹配屏幕比例的，且尺寸 ≤ 640x480。
     */
    private fun choosePreviewSize(
        sizes: List<Camera.Size>,
        screenRatio: Float
    ): Camera.Size? {
        var best: Camera.Size? = null
        var bestDiff = Float.MAX_VALUE
        // 第一轮：优先选 ≤ 640x480 的（ZXing 解码速度关键）
        for (size in sizes) {
            if (size.width > 640 || size.height > 480) continue
            val rotatedRatio = size.height.toFloat() / size.width.toFloat()
            val diff = Math.abs(rotatedRatio - screenRatio)
            if (diff < bestDiff ||
                (diff < 0.01f && (best == null || size.width * size.height > best!!.width * best!!.height))
            ) {
                bestDiff = diff
                best = size
            }
        }
        // 第二轮：放宽到 ≤ 800x600
        if (best == null) {
            for (size in sizes) {
                if (size.width > 800 || size.height > 600) continue
                val rotatedRatio = size.height.toFloat() / size.width.toFloat()
                val diff = Math.abs(rotatedRatio - screenRatio)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = size
                }
            }
        }
        // 兜底：选最小的（保证 ZXing 速度）
        if (best == null) {
            best = sizes.minByOrNull { it.width * it.height }
        }
        return best
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseCamera()
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_CANCELED)
        super.onBackPressed()
    }

    private fun releaseCamera() {
        decodeThread.stopLoop()
        try {
            camera?.setPreviewCallbackWithBuffer(null)
            camera?.stopPreview()
            camera?.release()
        } catch (_: Exception) {
        }
        camera = null
    }

    /**
     * 解码线程：从队列取帧，依次尝试 0/90/180/270 四个方向，命中即返回结果到 UI。
     */
    private inner class DecodeThread : Thread("zxing-decode") {
        @Volatile
        private var running = true

        private val queue = java.util.concurrent.LinkedBlockingQueue<Triple<ByteArray, Int, Int>>(5)

        fun submit(data: ByteArray, w: Int, h: Int) {
            // 队列满则丢弃旧帧，避免积压（解码速度跟不上时只处理最新帧）
            val triple = Triple(data, w, h)
            queue.offer(triple)
            // 队列超过 2 帧时清空旧的
            while (queue.size > 2) {
                queue.poll()
            }
        }

        fun stopLoop() {
            running = false
            interrupt()
        }

        override fun run() {
            var decodeCount = 0
            var lastLogTime = System.currentTimeMillis()
            while (running) {
                val item = try {
                    queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                } ?: continue
                val (data, w, h) = item
                if (data.size < w * h) {
                    // buffer 长度不够，跳过
                    continue
                }
                decodeCount++
                val t0 = System.currentTimeMillis()
                val result = tryDecodeAllAngles(data, w, h)
                val cost = System.currentTimeMillis() - t0
                if (cost > DECODE_SLOW_MS) {
                    DiagLog.log("Scan", "解码偏慢 ${cost}ms (尺寸 ${w}x${h})")
                    uiHandler.post {
                        tvScanStatus.text = "解码偏慢 ${cost}ms，请对准二维码"
                    }
                }
                if (result != null) {
                    DiagLog.log("Scan", "✓ 解码成功：${result.text} (尝试 ${decodeCount} 帧)")
                    val text = result.text
                    uiHandler.post {
                        tvScanStatus.text = "扫描成功"
                        setResult(Activity.RESULT_OK, intent.apply { putExtra(EXTRA_RESULT, text) })
                        finish()
                    }
                    return
                }
                // 每 1 秒更新一次 UI 状态，让用户知道在扫描
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 1000) {
                    DiagLog.log("Scan", "已尝试 ${decodeCount} 帧，未识别 (${w}x${h} cost=${cost}ms)")
                    val frameInfo = decodeCount
                    uiHandler.post {
                        tvScanStatus.text = "正在扫描... 已尝试 $frameInfo 帧"
                    }
                    lastLogTime = now
                }
            }
        }

        /** 依次尝试 4 个旋转方向，命中即返回。 */
        private fun tryDecodeAllAngles(data: ByteArray, w: Int, h: Int): com.google.zxing.Result? {
            // 多数后置摄像头 sensorOrientation=90，需要顺时针旋转 90°
            // 少数设备 sensorOrientation=270，需要旋转 270°
            // 极少数 tablet sensorOrientation=0，不需旋转
            // 4 个方向全试，最稳
            val angles = intArrayOf(90, 270, 0, 180)
            for (angle in angles) {
                val rotated = rotateY(data, w, h, angle) ?: continue
                val (rw, rh) = when (angle) {
                    90, 270 -> h to w
                    else -> w to h
                }
                val r = decode(rotated, rw, rh)
                if (r != null) return r
            }
            return null
        }

        /**
         * 顺时针旋转 Y 平面 [angleDeg] 度（0/90/180/270）。
         * @return 旋转后的 Y 数据；输入尺寸为 0 或不支持的角度返回 null
         */
        private fun rotateY(data: ByteArray, w: Int, h: Int, angleDeg: Int): ByteArray? {
            if (w == 0 || h == 0) return null
            val ySize = w * h
            if (data.size < ySize) return null
            val out = ByteArray(ySize)
            when (angleDeg) {
                0 -> {
                    System.arraycopy(data, 0, out, 0, ySize)
                }
                90 -> {
                    // 顺时针 90°：新[x][y] = 原[y][h-1-x]，按行写入
                    var idx = 0
                    for (i in 0 until w) {
                        for (j in h - 1 downTo 0) {
                            out[idx++] = data[j * w + i]
                        }
                    }
                }
                180 -> {
                    // 顺时针 180°：首尾反转
                    var idx = 0
                    for (j in h - 1 downTo 0) {
                        val rowStart = j * w
                        for (i in w - 1 downTo 0) {
                            out[idx++] = data[rowStart + i]
                        }
                    }
                }
                270 -> {
                    // 顺时针 270°（=逆时针 90°）
                    var idx = 0
                    for (i in w - 1 downTo 0) {
                        for (j in 0 until h) {
                            out[idx++] = data[j * w + i]
                        }
                    }
                }
                else -> return null
            }
            return out
        }

        private fun decode(yData: ByteArray, w: Int, h: Int): com.google.zxing.Result? {
            return try {
                val source = PlanarYUVLuminanceSource(yData, w, h, 0, 0, w, h, false)
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                reader.decode(bitmap)
            } catch (_: NotFoundException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
