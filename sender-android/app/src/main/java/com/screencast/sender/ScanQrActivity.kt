package com.screencast.sender

import android.app.Activity
import android.content.Context
import android.hardware.Camera
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.DecodeHintType

/**
 * 二维码扫描界面：用 Camera1 + SurfaceView 预览，ZXing core 解码。
 *
 * 不依赖 zxing-android-embedded，直接用 com.google.zxing:core（与 TV 端生成二维码同库），
 * 避免 zxing-android-embedded 在部分 AGP/Gradle 版本下的编译问题。
 *
 * 扫到二维码后通过 setResult 返回内容，调用方用 registerForActivityResult 接收。
 */
class ScanQrActivity : Activity() {

    companion object {
        const val EXTRA_RESULT = "scan_result"
    }

    private var camera: Camera? = null
    private var previewView: SurfaceView? = null
    private var decoding = false
    private val reader = MultiFormatReader().apply {
        // 只识别 QR Code，提升速度与准确度
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }

    private val previewCallback = Camera.PreviewCallback { data, cam ->
        if (decoding) return@PreviewCallback
        val size = cam.parameters.previewSize ?: return@PreviewCallback
        decoding = true
        try {
            // 横向宽度对应 width，高度对应 height
            val width = size.width
            val height = size.height
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decode(bitmap)
            // 扫到结果，回传
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
                    cam.parameters = cam.parameters.apply {
                        // 尽量用较高分辨率的预览帧，扫码更准
                        val best = supportedPreviewSizes.maxByOrNull { it.width * it.height }
                        if (best != null) setPreviewSize(best.width, best.height)
                        // 持续对焦，提升二维码识别率
                        val modes = supportedFocusModes
                        when {
                            modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                                focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                            modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                                focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                            modes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ->
                                focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                        }
                    }
                    cam.setPreviewCallback(previewCallback)
                    cam.startPreview()
                }
            } catch (e: Exception) {
                android.util.Log.e("ScanQrActivity", "open camera failed", e)
                finish()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            releaseCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不用 XML 布局，直接代码创建 SurfaceView，保持轻量
        val sv = SurfaceView(this)
        sv.holder.addCallback(surfaceCallback)
        setContentView(sv)
        previewView = sv
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
