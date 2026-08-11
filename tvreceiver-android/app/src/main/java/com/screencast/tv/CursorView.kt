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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 在投屏画面上叠加的白色箭头光标。
 *
 * - [moveBy] 接收来自触摸板的相对位移，自动夹到 View 边界内
 * - [moveTo] 设置绝对位置
 * - [visible] 控制是否绘制（不影响自身布局，仅控制 onDraw 行为）
 *
 * 初始位置默认居中（首次 layout 完成后），约 32x32 像素。
 */
class CursorView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val CURSOR_SIZE = 32f // px
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val arrowPath = Path().apply {
        // 简单箭头三角形：tip 在左上，左下竖边，右侧斜边收拢
        moveTo(0f, 0f)
        lineTo(0f, CURSOR_SIZE)
        lineTo(CURSOR_SIZE * 0.6f, CURSOR_SIZE * 0.6f)
        close()
    }

    /** 光标左上角坐标（相对本 View）。 */
    private var cx: Float = Float.NaN
    private var cy: Float = Float.NaN

    /** 外部可见性，false 时即便 VISIBLE 也不绘制。 */
    var visible: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 点击时的简单闪烁反馈：短暂放大描边宽度。 */
    private var clickPulse = 0f
    private val pulseRunnable = Runnable {
        clickPulse = 0f
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 首次 layout 把光标放到中心
        if (changed && cx.isNaN()) {
            cx = (width - CURSOR_SIZE) / 2f
            cy = (height - CURSOR_SIZE) / 2f
        }
    }

    /** 相对移动，自动夹到 View 边界内。 */
    fun moveBy(dx: Int, dy: Int) {
        if (width == 0 || height == 0) return
        if (cx.isNaN()) {
            cx = (width - CURSOR_SIZE) / 2f
            cy = (height - CURSOR_SIZE) / 2f
        }
        cx = (cx + dx).coerceIn(0f, (width - CURSOR_SIZE).coerceAtLeast(0f))
        cy = (cy + dy).coerceIn(0f, (height - CURSOR_SIZE).coerceAtLeast(0f))
        invalidate()
    }

    /** 绝对位置（自动夹到边界内）。 */
    fun moveTo(x: Float, y: Float) {
        if (width == 0 || height == 0) {
            cx = x
            cy = y
            return
        }
        cx = x.coerceIn(0f, (width - CURSOR_SIZE).coerceAtLeast(0f))
        cy = y.coerceIn(0f, (height - CURSOR_SIZE).coerceAtLeast(0f))
        invalidate()
    }

    /** 触发一次点击反馈：放大描边宽度，约 150ms 后还原。 */
    fun clickCursor() {
        clickPulse = 6f
        invalidate()
        removeCallbacks(pulseRunnable)
        postDelayed(pulseRunnable, 150)
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible || cx.isNaN()) return
        canvas.save()
        canvas.translate(cx, cy)
        // 闪烁期间加粗描边，体现“被按下”的感觉
        val savedWidth = strokePaint.strokeWidth
        strokePaint.strokeWidth = savedWidth + clickPulse
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)
        strokePaint.strokeWidth = savedWidth
        canvas.restore()
    }
}
