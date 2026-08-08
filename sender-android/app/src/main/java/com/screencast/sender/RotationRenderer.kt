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
import java.util.concurrent.locks.ReentrantLock

/**
 * 旋转渲染器：把手机竖屏画面旋转 90° 后渲染到 MediaCodec 的 inputSurface，
 * 使编码输出的 H.264 为横屏 16:9，TV 端可全屏显示，类似扩展屏效果。
 *
 * 渲染管线：
 *   VirtualDisplay → SurfaceTexture(OES纹理) → 旋转90°+缩放 → MediaCodec inputSurface
 *
 * @param codecInputSurface MediaCodec.createInputSurface() 返回的 Surface
 * @param outWidth 编码输出宽度（横屏，如 1280）
 * @param outHeight 编码输出高度（横屏，如 720）
 */
class RotationRenderer(
    private val codecInputSurface: Surface,
    private val outWidth: Int = 1280,
    private val outHeight: Int = 720
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
    var inputSurface: Surface? = null
        private set

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

    fun stop() {
        running = false
        signalFrame() // 唤醒可能阻塞的渲染线程
        try { thread?.join(2000) } catch (_: Exception) {}
        thread = null
        release()
    }

    private fun renderLoop() {
        try {
            // 1. 初始化 EGL，绑定到 MediaCodec inputSurface（GL 渲染目标）
            egl = EglCore()
            outputEglSurface = egl!!.createWindowSurface(codecInputSurface)
            egl!!.makeCurrent(outputEglSurface)

            // 2. 创建 OES 纹理 + SurfaceTexture（VirtualDisplay 的内容会更新此纹理）
            textureId = createOESTexture()
            surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(outWidth, outHeight)
                setOnFrameAvailableListener { _ -> signalFrame() }
            }
            inputSurface = Surface(surfaceTexture)

            // 3. 编译着色器
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")

            // MVP 矩阵：先旋转 90 度（竖屏变横屏），再缩放填满 16:9 区域
            // 旋转后原竖屏的宽变成了高，高变成了宽，需要按比例缩放
            Matrix.setRotateM(mvpMatrix, 0, 90f, 0f, 0f, 1f)

            Log.i(TAG, "renderer started ${outWidth}x${outHeight}, tex=$textureId")

            // 4. 渲染循环
            while (running) {
                waitForFrame()
                if (!running) break

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
            }
        } catch (e: Exception) {
            Log.e(TAG, "render loop error", e)
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
                if (remaining <= 0) return
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
            throw RuntimeException("program link failed: ${GLES20.glGetProgramInfoLog(p)}")
        }
        return p
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            throw RuntimeException("shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
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
