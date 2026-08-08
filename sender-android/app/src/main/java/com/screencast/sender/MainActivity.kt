package com.screencast.sender

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.IntentIntegrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var etCode: EditText
    private lateinit var btnPair: Button
    private lateinit var btnScan: Button
    private lateinit var pairBox: LinearLayout
    private lateinit var manualBox: LinearLayout
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvToggle: TextView
    private lateinit var tvStatus: TextView

    // 配对成功后写入这两个字段，供投屏授权回来后启动服务使用
    private var resolvedHost = ""
    private var resolvedPort = 8855

    private val uiScope = CoroutineScope(Dispatchers.Main)

    /** 接收 ScreenCastService 发来的连接状态（成功/失败/断开）。 */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenCastService.EXTRA_STATE_TEXT) ?: return
            tvStatus.text = text
            when {
                text.startsWith("连接失败") || text.startsWith("已断开") -> updateStatus(false)
                text.startsWith("已连接") -> updateStatus(true)
            }
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val intent = Intent(this, ScreenCastService::class.java).apply {
                    action = ScreenCastService.ACTION_START
                    putExtra(ScreenCastService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCastService.EXTRA_RESULT_DATA, result.data)
                    putExtra(ScreenCastService.EXTRA_HOST, resolvedHost)
                    putExtra(ScreenCastService.EXTRA_PORT, resolvedPort)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                updateStatus(true)
            } catch (e: Throwable) {
                tvStatus.text = "启动投屏服务失败：${e.javaClass.simpleName}: ${e.message}"
                updateStatus(false)
            }
        } else {
            Toast.makeText(this, "未授权投屏", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 授权完成后再请求投屏授权
        requestProjection()
    }

    /** 扫码结果回调：扫描到的内容即为 6 位配对码。 */
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val res = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        val code = res?.contents?.trim()
        if (!code.isNullOrEmpty()) {
            // 兼容二维码内容可能带多余字符，只取数字部分
            val digits = code.filter { it.isDigit() }
            if (digits.length == 6) {
                etCode.setText(digits)
                startPairing(digits)
            } else {
                Toast.makeText(this, "二维码内容不是有效配对码：$code", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 摄像头权限回调：授权后启动扫码。 */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startScan()
        else Toast.makeText(this, "需要摄像头权限才能扫码", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        etCode = findViewById(R.id.etCode)
        btnPair = findViewById(R.id.btnPair)
        btnScan = findViewById(R.id.btnScan)
        pairBox = findViewById(R.id.pairBox)
        manualBox = findViewById(R.id.manualBox)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvToggle = findViewById(R.id.tvToggle)
        tvStatus = findViewById(R.id.tvStatus)

        etPort.setText(resolvedPort.toString())

        // 配对码连接：后台 UDP 发现 → 拿到 IP 后请求投屏授权
        btnPair.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "请输入 6 位配对码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ScreenCastService.isRunning) {
                Toast.makeText(this, "已在投屏中", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startPairing(code)
        }

        // 扫描接收端屏幕上的二维码
        btnScan.setOnClickListener {
            if (ScreenCastService.isRunning) {
                Toast.makeText(this, "已在投屏中", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            } else {
                startScan()
            }
        }

        // 手动 IP 连接
        btnStart.setOnClickListener {
            val host = etHost.text.toString().trim()
            if (host.isEmpty()) {
                Toast.makeText(this, "请输入接收端 IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ScreenCastService.isRunning) {
                Toast.makeText(this, "已在投屏中", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            resolvedHost = host
            resolvedPort = etPort.text.toString().trim().toIntOrNull() ?: 8855
            requestProjectionWithPermissionCheck()
        }

        // 切换配对码 / 手动 IP
        tvToggle.setOnClickListener {
            if (pairBox.visibility == View.VISIBLE) {
                pairBox.visibility = View.GONE
                manualBox.visibility = View.VISIBLE
                tvToggle.text = "改用配对码连接 ▾"
            } else {
                pairBox.visibility = View.VISIBLE
                manualBox.visibility = View.GONE
                tvToggle.text = "改用手动 IP 连接 ▾"
            }
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, ScreenCastService::class.java).apply {
                action = ScreenCastService.ACTION_STOP
            }
            startService(intent)
            updateStatus(false)
        }

        updateStatus(ScreenCastService.isRunning)

        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(ScreenCastService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 如果上次崩溃过，弹窗展示崩溃日志，便于排查
        showLastCrashIfAny()
    }

    /** 读取内部目录的崩溃日志并弹窗显示，用户可截图发我。 */
    private fun showLastCrashIfAny() {
        val crashFile = File(filesDir, "screencast_crash.log")
        if (!crashFile.exists()) return
        val text = runCatching { crashFile.readText() }.getOrNull() ?: return
        if (text.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("上次崩溃日志")
            .setMessage(text)
            .setPositiveButton("知道了") { _, _ -> crashFile.delete() }
            .setNegativeButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    /** 后台用配对码发现接收端，成功后请求投屏授权。 */
    private fun startPairing(code: String) {
        tvStatus.text = "正在搜索配对码 $code ..."
        btnPair.isEnabled = false
        uiScope.launch {
            val receiver = withContext(Dispatchers.IO) {
                PairingClient(this@MainActivity).discover(code)
            }
            btnPair.isEnabled = true
            if (receiver == null) {
                tvStatus.text = "未找到配对码 $code 的接收端\n请确认手机与接收端在同一 WiFi"
                return@launch
            }
            resolvedHost = receiver.ip
            resolvedPort = receiver.tcpPort
            tvStatus.text = "已发现 ${receiver.name} (${receiver.ip})\n请求投屏授权..."
            requestProjectionWithPermissionCheck()
        }
    }

    private fun requestProjectionWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun requestProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun updateStatus(running: Boolean) {
        tvStatus.text = if (running) "状态：投屏中" else "状态：未投屏"
        btnPair.isEnabled = !running
        btnScan.isEnabled = !running
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    /** 启动二维码扫描界面。 */
    private fun startScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("将接收端屏幕上的二维码对准取景框")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(false)
        scanLauncher.launch(integrator.createScanIntent())
    }
}
