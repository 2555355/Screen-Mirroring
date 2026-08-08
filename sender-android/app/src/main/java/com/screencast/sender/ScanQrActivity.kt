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
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码扫描界面：用 Camera1 + SurfaceView 预览，ZXing core 解码。
 *
 * 关键修复：
 * 1. 调用 setDisplayOrientation(90) 让竖屏预览方向正确（不再横躺）
 * 2. 选择与屏幕比例最接近的预览分辨率，居中裁剪填满屏幕，预览不拉伸
 * 3. 添加扫描框引导用户对准二维码
 */
@Suppress("DEPRECATION")
class ScanQrActivity : Activity() {

    companion object {
        private const val TAG = "ScanQrActivity"
        const val EXTRA_RESULT = "scan_result"
    }

    private var camera: Camera? = null
    private lateinit var previewView: SurfaceView
    private var decoding = false

    /** 相机预览分辨率（传感器原生方向，横屏）。 */
    private var previewWidth = 0
    private var previewHeight = 0

    /** SurfaceView 容器（屏幕）尺寸，用于计算居中裁剪比例。 */
    private var containerWidth = 0
    private var containerHeight = 0

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }

    private val previewCallback = Camera.PreviewCallback { data, _ ->
        if (decoding) return@PreviewCallback
        val w = previewWidth
        val h = previewHeight
        if (w == 0 || h == 0) return@PreviewCallback
        decoding = true
        try {
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decode(bitmap)
            val intent = intent.apply { putExtra(EXTRA_RESULT, result.text) }
            setResult(Activity.RESULT_OK, intent)
            finish()
        } catch (_: Exception) {
            // 当前帧没扫到，继续下一帧
        } finally {
            decoding = false
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            try {
                camera = Camera.open().also { cam ->
                    cam.setPreviewDisplay(holder)
                    // 竖屏显示：将传感器横屏画面旋转 90°
                    cam.setDisplayOrientation(90)

                    val params = cam.parameters
                    // 选择与屏幕比例最接近的预览尺寸，避免画面拉伸
                    val screenRatio = containerWidth.toFloat() / containerHeight.toFloat()
                    val best = choosePreviewSize(params.supportedPreviewSizes, screenRatio)
                    if (best != null) {
                        params.setPreviewSize(best.width, best.height)
                        previewWidth = best.width
                        previewHeight = best.height
                    }
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

                    cam.setPreviewCallback(previewCallback)
                    cam.startPreview()
                }
            } catch (e: Exception) {
                Log.e(TAG, "open camera failed", e)
                finish()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            // 首次回调时记录容器（屏幕）尺寸，后续调整 SurfaceView 不再覆盖
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

        // 提前获取屏幕尺寸，供 surfaceCreated 中选择预览分辨率使用
        val dm = resources.displayMetrics
        containerWidth = dm.widthPixels
        containerHeight = dm.heightPixels

        previewView.holder.addCallback(surfaceCallback)
    }

    /**
     * 调整 SurfaceView 尺寸，使预览画面居中裁剪填满屏幕且不拉伸。
     *
     * 预览旋转 90° 后，实际显示比例 = previewHeight : previewWidth（竖屏）。
     * 居中裁剪：让 SurfaceView 比屏幕大，超出部分被屏幕裁掉，保证比例不变形。
     */
    private fun adjustPreviewSize() {
        if (previewWidth == 0 || previewHeight == 0) return
        if (containerWidth == 0 || containerHeight == 0) return

        // 旋转 90° 后的竖屏比例
        val rotatedRatio = previewHeight.toFloat() / previewWidth.toFloat()
        val screenRatio = containerWidth.toFloat() / containerHeight.toFloat()

        val targetW: Int
        val targetH: Int
        if (screenRatio > rotatedRatio) {
            // 屏幕更宽 → 填满宽度，高度溢出裁掉
            targetW = containerWidth
            targetH = (containerWidth / rotatedRatio).toInt()
        } else {
            // 屏幕更高 → 填满高度，宽度溢出裁掉
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
     * 从支持的预览尺寸中选择最匹配屏幕比例的一个。
     * @param screenRatio 屏幕宽高比（竖屏，如 9/16 ≈ 0.5625）
     * 旋转 90° 后预览的竖屏比例 = height / width，选最接近 screenRatio 的。
     */
    private fun choosePreviewSize(
        sizes: List<Camera.Size>,
        screenRatio: Float
    ): Camera.Size? {
        // 优先选不超过 1280x720 的尺寸（兼顾性能与清晰度）
        var best: Camera.Size? = null
        var bestDiff = Float.MAX_VALUE
        for (size in sizes) {
            if (size.width > 1280 || size.height > 720) continue
            val rotatedRatio = size.height.toFloat() / size.width.toFloat()
            val diff = Math.abs(rotatedRatio - screenRatio)
            // 偏好面积更大的（更清晰），但在比例相近时优先选比例匹配的
            if (diff < bestDiff || (diff < 0.01f && (best == null || size.width * size.height > best!!.width * best!!.height))) {
                bestDiff = diff
                best = size
            }
        }
        // 如果没有 <= 1280x720 的，退而求其次选最大的
        if (best == null) {
            best = sizes.maxByOrNull { it.width * it.height }
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
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (_: Exception) {
        }
        camera = null
    }
}
