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

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 旋转渲染器：把手机竖屏画面旋转 90° 后渲染到 MediaCodec 的 inputSurface，
 * 使编码输出的 H.264 为横屏 16:9，TV 端可全屏显示，类似扩展屏效果。
 *
 * 渲染管线：
 *   VirtualDisplay(竖屏 inWidth×inHeight) → SurfaceTexture(OES纹理) → 旋转90° → MediaCodec inputSurface(横屏 outWidth×outHeight)
 *
 * @param codecInputSurface MediaCodec.createInputSurface() 返回的 Surface
 * @param inWidth  VirtualDisplay 输出宽度（竖屏传感器方向，如 720）
 * @param inHeight VirtualDisplay 输出高度（竖屏传感器方向，如 1280）
 * @param outWidth 编码输出宽度（横屏，如 1280）
 * @param outHeight 编码输出高度（横屏，如 720）
 * @param rotateAngle 旋转角度（90 或 270）。不同设备传感器方向不同，
 *                    若 90° 投出方向颠倒，改用 270°。
 */
class RotationRenderer(
    private val codecInputSurface: Surface,
    private val inWidth: Int = 720,
    private val inHeight: Int = 1280,
    private val outWidth: Int = 1280,
    private val outHeight: Int = 720,
    private val rotateAngle: Int = 90
) {

    companion object {
        private const val TAG = "RotationRenderer"

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoord);
            }
        """

        // 全屏四边形（TRIANGLE_STRIP）
        private val VERTEX = floatArrayOf(
            -1f, -1f, 0f,
             1f, -1f, 0f,
            -1f,  1f, 0f,
             1f,  1f, 0f
        )
        private val TEX_COORD = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
    }

    private var egl: EglCore? = null
    private var outputEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    /** VirtualDisplay 渲染到此 Surface（内容来自 OES 纹理）。 */
    @Volatile var inputSurface: Surface? = null
        private set

    /** 初始化完成（成功或失败）的同步点：外部等待 inputSurface 就绪。 */
    private val initLatch = CountDownLatch(1)
    /** 初始化是否成功（inputSurface 已创建，可创建 VirtualDisplay）。 */
    @Volatile private var initOk = false

    private var program = 0
    private var aPositionLoc = 0
    private var aTextureCoordLoc = 0
    private var uMVPMatrixLoc = 0
    private var uSTMatrixLoc = 0
    private var uTextureLoc = 0

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    /** 帧同步：SurfaceTexture 新帧到达时唤醒渲染线程。 */
    private val frameLock = ReentrantLock()
    private val frameCondition = frameLock.newCondition()
    @Volatile private var frameAvailable = false

    private var thread: Thread? = null
    @Volatile private var running = false

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(VERTEX) }
    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORD.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(TEX_COORD) }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ renderLoop() }, "gl-rotate").also { it.start() }
    }

    /**
     * 阻塞等待渲染线程初始化完成（inputSurface 就绪或失败）。
     * @return true 表示 inputSurface 已就绪，可创建 VirtualDisplay。
     */
    fun awaitReady(timeoutMs: Long = 3000): Boolean {
        initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return initOk
    }

    fun stop() {
        running = false
        signalFrame() // 唤醒可能阻塞的渲染线程
        try { thread?.join(2000) } catch (_: Exception) {}
        thread = null
        release()
    }

    private fun renderLoop() {
        var renderedCount = 0L
        var frameRecvCount = 0L
        try {
            // 1. 初始化 EGL，绑定到 MediaCodec inputSurface（GL 渲染目标）
            DiagLog.log("EGL", "初始化中...")
            egl = EglCore()
            outputEglSurface = egl!!.createWindowSurface(codecInputSurface)
            egl!!.makeCurrent(outputEglSurface)
            DiagLog.log("EGL", "就绪，输出 ${outWidth}x${outHeight}")

            // 2. 创建 OES 纹理 + SurfaceTexture（VirtualDisplay 的内容会更新此纹理）
            textureId = createOESTexture()
            surfaceTexture = SurfaceTexture(textureId).apply {
                // 【关键修复】buffer size 必须匹配 VirtualDisplay 的输出分辨率（竖屏传感器方向），
                // 否则部分设备上 VirtualDisplay 会按 buffer size 渲染，导致竖屏内容塞进横屏 buffer → 帧不更新/方向错乱。
                setDefaultBufferSize(inWidth, inHeight)
                setOnFrameAvailableListener { _ -> signalFrame() }
            }
            inputSurface = Surface(surfaceTexture)
            initOk = true
            initLatch.countDown()
            DiagLog.log("SurfaceTex", "就绪 buffer=${inWidth}x${inHeight} tex=$textureId")

            // 3. 编译着色器
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
            DiagLog.log("Shader", "program=$program locs OK")

            // MVP 矩阵：旋转 rotateAngle°（竖屏变横屏，方向由用户选择）。
            Matrix.setRotateM(mvpMatrix, 0, rotateAngle.toFloat(), 0f, 0f, 1f)
            DiagLog.log("Render", "MVP 旋转角度=$rotateAngle°")

            DiagLog.log("Render", "进入渲染循环，等待 VirtualDisplay 帧...")

            // 4. 渲染循环
            while (running) {
                waitForFrame()
                if (!running) break

                frameRecvCount++
                if (frameRecvCount == 1L) {
                    DiagLog.log("Render", "收到第1帧 → 开始旋转渲染")
                }
                surfaceTexture!!.updateTexImage()
                surfaceTexture!!.getTransformMatrix(stMatrix)

                egl!!.makeCurrent(outputEglSurface)
                GLES20.glViewport(0, 0, outWidth, outHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glUniform1i(uTextureLoc, 0)

                vertexBuffer.position(0)
                GLES20.glEnableVertexAttribArray(aPositionLoc)
                GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

                texCoordBuffer.position(0)
                GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
                GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

                GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
                GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                GLES20.glDisableVertexAttribArray(aPositionLoc)
                GLES20.glDisableVertexAttribArray(aTextureCoordLoc)

                // 提交到 MediaCodec input surface（编码器消费此帧）
                egl!!.swapBuffers(outputEglSurface)
                renderedCount++
                if (renderedCount == 1L) {
                    DiagLog.log("Render", "已渲染第1帧到编码器")
                } else if (renderedCount % 300 == 0L) {
                    DiagLog.log("Render", "已渲染 $renderedCount 帧 (收 $frameRecvCount)")
                }
            }
            DiagLog.log("Render", "循环退出，共渲染 $renderedCount 帧")
        } catch (e: Throwable) {
            // 初始化失败也要释放 latch，避免外部永久阻塞
            initLatch.countDown()
            DiagLog.e("Render", "渲染异常 (rendered=$renderedCount)", e)
        } finally {
            release()
        }
    }

    private fun signalFrame() {
        frameLock.lock()
        try {
            frameAvailable = true
            frameCondition.signalAll()
        } finally {
            frameLock.unlock()
        }
    }

    private fun waitForFrame(timeoutMs: Long = 5000) {
        frameLock.lock()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable && running) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    DiagLog.e("Render", "等待帧超时 ${timeoutMs}ms，VirtualDisplay 未产生画面")
                    return
                }
                try {
                    frameCondition.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                }
            }
            frameAvailable = false
        } finally {
            frameLock.unlock()
        }
    }

    private fun createOESTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(p)
            DiagLog.e("Shader", "program link 失败: $info")
            throw RuntimeException("program link failed: $info")
        }
        DiagLog.log("Shader", "program=$p link OK")
        return p
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            val err = GLES20.glGetError()
            DiagLog.e("Shader", "glCreateShader(${if (type == GLES20.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"}) 返回 0, glError=0x${Integer.toHexString(err)}")
            throw RuntimeException("glCreateShader returned 0, glError=0x${Integer.toHexString(err)}")
        }
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetShaderInfoLog(shader)
            val typeName = if (type == GLES20.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"
            DiagLog.e("Shader", "$typeName 编译失败: $info")
            throw RuntimeException("$typeName shader compile failed: $info")
        }
        return shader
    }

    private fun release() {
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (outputEglSurface !== EGL14.EGL_NO_SURFACE) {
            egl?.releaseSurface(outputEglSurface)
            outputEglSurface = EGL14.EGL_NO_SURFACE
        }
        egl?.release()
        egl = null
    }
}
