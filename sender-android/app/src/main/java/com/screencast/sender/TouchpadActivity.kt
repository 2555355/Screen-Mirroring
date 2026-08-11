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

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 触摸板界面：投屏时打开，手指滑动通过 [ControlSender] 发送光标位移给 TV 端，
 * 底部按钮发送 CLICK / BACK / HOME。
 *
 * 触摸区域采用相对位移模式（类笔记本触摸板）：
 *   - ACTION_DOWN 记录起点并清零累积量
 *   - ACTION_MOVE 计算自上一帧的位移 × 灵敏度，累积后取整数部分发送，
 *     保留小数部分到下一帧，避免亚像素移动被截断丢失
 */
class TouchpadActivity : AppCompatActivity() {

    private val sender = ControlSender()

    // 上一次触摸事件坐标（View 相对坐标系）
    private var lastX = 0f
    private var lastY = 0f
    // 灵敏度放大后的累积位移，取整后发送，余数留到下一帧
    private var accumDx = 0f
    private var accumDy = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_touchpad)

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        if (host.isEmpty()) {
            Toast.makeText(this, "缺少接收端地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val tvHint = findViewById<TextView>(R.id.tvTouchpadHint)
        val touchpadArea = findViewById<View>(R.id.touchpadArea)
        val btnClick = findViewById<Button>(R.id.btnClick)
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnHome = findViewById<Button>(R.id.btnHome)

        title = "触摸板"

        // 后台连接 TV 端控制端口（NetworkOnMainThread 限制）
        Thread {
            val ok = sender.connect(host)
            runOnUiThread {
                if (ok) {
                    tvHint.text = "触摸板 - 已连接 $host\n滑动移动光标，点击=单击"
                } else {
                    tvHint.text = "触摸板 - 连接失败：${sender.lastError ?: "未知"}"
                    Toast.makeText(
                        this,
                        "连接失败：${sender.lastError ?: "未知"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()

        touchpadArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    accumDx = 0f
                    accumDy = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawDx = event.x - lastX
                    val rawDy = event.y - lastY
                    accumDx += rawDx * SENSITIVITY
                    accumDy += rawDy * SENSITIVITY
                    val sendDx = accumDx.toInt()
                    val sendDy = accumDy.toInt()
                    if (sendDx != 0 || sendDy != 0) {
                        sender.sendMove(sendDx, sendDy)
                        // 只扣除已发送的整数部分，保留小数累积
                        accumDx -= sendDx
                        accumDy -= sendDy
                    }
                    lastX = event.x
                    lastY = event.y
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    accumDx = 0f
                    accumDy = 0f
                    true
                }
                else -> false
            }
        }

        btnClick.setOnClickListener { sender.sendClick() }
        btnBack.setOnClickListener { sender.sendBack() }
        btnHome.setOnClickListener { sender.sendHome() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 后台断开，避免 connect() 仍在进行时阻塞主线程
        val s = sender
        Thread { s.disconnect() }.start()
    }

    companion object {
        /** 接收端 host 的 Intent extra key。 */
        const val EXTRA_HOST = "host"

        /** 灵敏度系数：手指移动 1px → 光标移动 1.5px。 */
        private const val SENSITIVITY = 1.5f
    }
}
