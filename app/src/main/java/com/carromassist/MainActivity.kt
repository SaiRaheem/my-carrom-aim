package com.carromassist

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY = 1001
        const val REQUEST_SCREEN_CAPTURE = 1002
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV Load Failed — critical error!", Toast.LENGTH_LONG).show()
        }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        statusText = findViewById(R.id.statusText)
        btnStart   = findViewById(R.id.btnStart)
        btnStop    = findViewById(R.id.btnStop)

        btnStart.setOnClickListener { checkAndStart() }
        btnStop.setOnClickListener  { stopAssist() }

        updateUI(OverlayService.isRunning)
    }

    private fun checkAndStart() {
        // Step 1: overlay permission
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "Grant 'Display over other apps' permission…"
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }
        // Step 2: screen capture permission
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        statusText.text = "Requesting screen capture…"
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestScreenCapture()
                else Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
            REQUEST_SCREEN_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startOverlayService(resultCode, data)
                } else {
                    Toast.makeText(this, "Screen capture denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        updateUI(true)
        // Bring the game to foreground
        launchCarromPool()
    }

    private fun stopAssist() {
        stopService(Intent(this, OverlayService::class.java))
        updateUI(false)
    }

    private fun updateUI(running: Boolean) {
        statusText.text = if (running) "✅ Assist ACTIVE — switch to Carrom Pool" else "⬤ Inactive"
        btnStart.isEnabled = !running
        btnStop.isEnabled  = running
    }

    /** Tries to open Carrom Pool directly */
    private fun launchCarromPool() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.miniclip.carrom")
                ?: packageManager.getLaunchIntentForPackage("com.miniclip.carrompool")
            intent?.let { startActivity(it) }
        } catch (e: Exception) {
             Toast.makeText(this, "Could not open game: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
