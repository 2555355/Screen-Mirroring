package com.screencast.sender

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private var pendingHost = ""
    private var pendingPort = 8855

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCastService::class.java).apply {
                action = ScreenCastService.ACTION_START
                putExtra(ScreenCastService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCastService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCastService.EXTRA_HOST, pendingHost)
                putExtra(ScreenCastService.EXTRA_PORT, pendingPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateStatus(true)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        etPort.setText(pendingPort.toString())

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
            pendingHost = host
            pendingPort = etPort.text.toString().trim().toIntOrNull() ?: 8855

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

        btnStop.setOnClickListener {
            val intent = Intent(this, ScreenCastService::class.java).apply {
                action = ScreenCastService.ACTION_STOP
            }
            startService(intent)
            updateStatus(false)
        }

        updateStatus(ScreenCastService.isRunning)
    }

    private fun requestProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun updateStatus(running: Boolean) {
        tvStatus.text = if (running) "状态：投屏中" else "状态：未投屏"
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }
}
