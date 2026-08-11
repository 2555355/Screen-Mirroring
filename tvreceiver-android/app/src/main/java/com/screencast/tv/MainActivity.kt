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
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PORT = 8855
        private const val CONTROL_PORT = 8856
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var displayList: LinearLayout
    private lateinit var tvInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMirrorAll: Button
    private lateinit var btnRefresh: Button
    private lateinit var tvPairCode: TextView
    private lateinit var ivQr: ImageView

    /** 当前活跃的 CastPresentation，按 displayId 索引。镜像模式下会有多个。 */
    private val presentations = linkedMapOf<Int, CastPresentation>()
    private var server: ScreenReceiverServer? = null
    private var pairingServer: PairingServer? = null
    private var controlServer: ControlServer? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.i(TAG, "display added: $displayId")
            refreshDisplayList()
        }

        override fun onDisplayChanged(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {
            Log.i(TAG, "display removed: $displayId")
            // 如果该屏正在投屏，自动停掉
            presentations.remove(displayId)?.release()
            refreshDisplayList()
            updateStatusText()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keepScreenOn(window)

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayList = findViewById(R.id.displayList)
        tvInfo = findViewById(R.id.tvInfo)
        tvStatus = findViewById(R.id.tvStatus)
        btnMirrorAll = findViewById(R.id.btnMirrorAll)
        btnRefresh = findViewById(R.id.btnRefresh)
        tvPairCode = findViewById(R.id.tvPairCode)
        ivQr = findViewById(R.id.ivQr)

        tvInfo.text = "本机IP: ${getLocalIp()}\nTCP端口: $PORT（视频）/ $CONTROL_PORT（控制）"

        btnMirrorAll.setOnClickListener { startMirrorAll() }
        btnRefresh.setOnClickListener { refreshDisplayList() }

        // 启动 TCP 接收 + 配对码发现服务（无需先选显示器）
        ensureServer()
        ensureControlServer()
        startPairing()

        refreshDisplayList()
    }

    /** 启动配对码服务，并在 UI 上显示配对码与二维码。 */
    private fun startPairing() {
        if (pairingServer != null) return
        val name = "${Build.MANUFACTURER} ${Build.MODEL}"
        val p = PairingServer(
            context = this,
            tcpPort = PORT,
            deviceName = name,
            onCodeGenerated = { code ->
                runOnUiThread {
                    tvPairCode.text = code
                    // 二维码内容：配对码（手机端扫码后即等同于输入配对码）
                    QrUtil.generate(code, 512)?.let { bmp ->
                        ivQr.setImageBitmap(bmp)
                    }
                    tvStatus.text = "状态：等待手机连接（配对码 $code）"
                }
            }
        )
        p.start()
        pairingServer = p
    }

    override fun onResume() {
        super.onResume()
        displayManager.registerDisplayListener(displayListener, null)
        refreshDisplayList()
    }

    override fun onPause() {
        super.onPause()
        displayManager.unregisterDisplayListener(displayListener)
    }

    // -------------------------------------------------------------- 显示器列表
    private fun refreshDisplayList() {
        displayList.removeAllViews()
        val displays = displayManager.getDisplays()
        if (displays.isEmpty()) {
            addHintRow("未检测到显示器")
            return
        }
        displays.forEachIndexed { index, display ->
            val name = displayLabel(display)
            val res = displayRes(display)
            val label = "屏幕${index + 1}: $name ($res)"
            val btn = Button(this, null, 0, R.style.Widget_ScreenCast_Button).apply {
                text = label
                // 遥控器 D-Pad 上下可聚焦到此按钮
                isFocusable = true
                isFocusableInTouchMode = true
                // 选中态（蓝色高亮）由 updateButtonSelection 统一维护
                setOnClickListener { startSingle(display) }
                tag = display.displayId
            }
            displayList.addView(btn)
        }
        updateButtonSelection()
        // 让首个显示器按钮获得焦点，遥控器一进来即可用 D-Pad 操作
        if (displayList.childCount > 0) {
            displayList.getChildAt(0).requestFocus()
        }
    }

    private fun addHintRow(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0x80FFFFFF.toInt())
            textSize = 14f
            setPadding(8, 16, 8, 16)
        }
        displayList.addView(tv)
    }

    // -------------------------------------------------------------- 投屏控制
    /** 单屏模式：只在选定的 [display] 上投屏，停掉其他屏。 */
    private fun startSingle(display: Display) {
        stopAllCasts()
        ensureServer()
        val id = display.displayId
        val name = displayLabel(display)
        val p = CastPresentation(
            context = this,
            display = display,
            displayId = id,
            displayName = name,
            onReady = { presentation ->
                presentation.setOverlay("$name · 等待手机画面 ...")
                updateStatusText()
            },
            onExit = { stopAllCasts() }
        )
        try {
            p.show()
            presentations[id] = p
            updateStatusText()
            updateButtonSelection()
        } catch (e: Exception) {
            Log.e(TAG, "show presentation failed", e)
            Toast.makeText(this, "无法在该显示器显示: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 镜像模式：在所有显示器上同时投屏同一画面。 */
    private fun startMirrorAll() {
        stopAllCasts()
        ensureServer()
        val displays = displayManager.getDisplays()
        if (displays.isEmpty()) {
            Toast.makeText(this, "未检测到显示器", Toast.LENGTH_SHORT).show()
            return
        }
        displays.forEach { display ->
            val id = display.displayId
            val name = displayLabel(display)
            val p = CastPresentation(
                context = this,
                display = display,
                displayId = id,
                displayName = name,
                onReady = { it.setOverlay("$name · 镜像中 ...") },
                onExit = { stopAllCasts() }
            )
            try {
                p.show()
                presentations[id] = p
            } catch (e: Exception) {
                Log.e(TAG, "show presentation failed on $id", e)
            }
        }
        updateStatusText()
        updateButtonSelection()
    }

    /** 停掉所有 CastPresentation，但保留 TCP server 以便快速重投。 */
    private fun stopAllCasts() {
        presentations.values.forEach { it.release() }
        presentations.clear()
        updateStatusText()
        updateButtonSelection()
    }

    /**
     * 同步显示器按钮的 selected 状态：
     * 正在投屏的屏按钮置 selected=true（显示蓝色高亮），其余置 false。
     * 让用户一眼看出当前投到了哪个屏。
     */
    private fun updateButtonSelection() {
        val active = presentations.keys
        for (i in 0 until displayList.childCount) {
            val child = displayList.getChildAt(i)
            if (child is Button) {
                val id = child.tag as? Int
                child.isSelected = id != null && active.contains(id)
            }
        }
    }

    private fun ensureServer() {
        if (server != null) return
        val s = ScreenReceiverServer(
            port = PORT,
            onState = { msg -> runOnUiThread {
                // 把状态同时刷到所有活跃 Presentation 的浮层和主界面
                presentations.values.forEach { it.setOverlay(msg) }
                tvStatus.text = msg
            } },
            onFrame = { ts, data ->
                // 同一份数据分发给所有活跃解码器，实现镜像
                presentations.values.forEach { it.feed(ts, data) }
            }
        )
        s.start()
        server = s
    }

    /**
     * 启动触摸板控制服务（端口 8856），接收手机端的 MOVE/CLICK/BACK/HOME 消息。
     * 回调在 IO 线程，UI 操作需切回主线程。
     */
    private fun ensureControlServer() {
        if (controlServer != null) return
        val c = ControlServer(
            port = CONTROL_PORT,
            onMove = { dx, dy ->
                runOnUiThread {
                    // 镜像模式：把光标位移应用到所有活跃 Presentation
                    presentations.values.forEach { it.moveCursor(dx, dy) }
                }
            },
            onClick = {
                runOnUiThread {
                    // 无障碍服务授权前不做真实点击注入，只让光标闪烁反馈
                    presentations.values.forEach { it.clickCursor() }
                }
            },
            onBack = {
                runOnUiThread { onControlBack() }
            },
            onHome = {
                runOnUiThread { onControlHome() }
            },
            onState = { msg -> runOnUiThread {
                tvStatus.text = msg
            } }
        )
        c.start()
        controlServer = c
    }

    /** 控制端 BACK：优先停掉投屏，否则按返回退出。 */
    private fun onControlBack() {
        if (presentations.isNotEmpty()) {
            stopAllCasts()
        } else {
            onBackPressed()
        }
    }

    /** 控制端 HOME：回到系统 Launcher。 */
    private fun onControlHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "start HOME intent failed: ${e.message}")
        }
    }

    private fun updateStatusText() {
        val n = presentations.size
        tvStatus.text = if (n == 0) {
            "状态：未投屏"
        } else {
            "状态：投屏中（$n 个显示器）"
        }
    }

    // -------------------------------------------------------------- 生命周期
    override fun onDestroy() {
        super.onDestroy()
        stopAllCasts()
        pairingServer?.stop()
        pairingServer = null
        server?.stop()
        server = null
        controlServer?.stop()
        controlServer = null
    }

    // 在主界面按 BACK：若正在投屏先停投屏，再退出
    override fun onBackPressed() {
        if (presentations.isNotEmpty()) {
            stopAllCasts()
            return
        }
        super.onBackPressed()
    }

    // -------------------------------------------------------------- 工具
    /** 兼容获取显示器名称（Display.displayName 在 API 30+ 已废弃且非公开）。 */
    private fun displayLabel(display: Display): String {
        val name = display.getName()
        val realName = if (name.isNullOrEmpty()) "Display ${display.displayId}" else name
        return realName
    }

    /** 兼容获取显示器分辨率字符串。 */
    @Suppress("DEPRECATION")
    private fun displayRes(display: Display): String {
        val pt = android.graphics.Point()
        try {
            display.getRealSize(pt)
        } catch (_: Throwable) {
            pt.x = 0; pt.y = 0
        }
        return "${pt.x}x${pt.y}"
    }

    private fun getLocalIp(): String {
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            var result = "未知"
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) {
                        result = a.hostAddress ?: result
                    }
                }
            }
            result
        } catch (e: Exception) {
            "未知"
        }
    }

    private fun keepScreenOn(window: android.view.Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
