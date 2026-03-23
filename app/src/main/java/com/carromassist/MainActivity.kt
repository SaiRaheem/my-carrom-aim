package com.carromassist

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
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

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "Vision Engine Error", Toast.LENGTH_LONG).show()
        }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)
        btnStart   = findViewById(R.id.btnStart)
        btnStop    = findViewById(R.id.btnStop)

        btnStart.setOnClickListener { checkAndStart() }
        btnStop.setOnClickListener  { stopAssist() }

        updateUI(OverlayService.isRunning)
    }

    private fun checkAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY && Settings.canDrawOverlays(this)) requestScreenCapture()
        else if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            startOverlayService(resultCode, data)
        }
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        updateUI(true)
        launchCarromMatch()
    }

    private fun stopAssist() {
        stopService(Intent(this, OverlayService::class.java))
        updateUI(false)
    }

    private fun updateUI(running: Boolean) {
        statusText.text = if (running) "SENTRY: [ACTIVE]" else "SENTRY: [INACTIVE]"
        btnStart.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility  = if (running) View.VISIBLE else View.GONE
    }

    private fun launchCarromMatch() {
        // bitAIM+ logic: Exhaustive search for ALL carrom packages to ensure launch!
        val packages = listOf(
            "com.miniclip.carrom",
            "com.miniclip.carrompool",
            "com.miniclip.carrom.pool",
            "com.miniclip.carrom.disc",
            "com.miniclip.carromplay"
        )
        
        var launched = false
        for (pkg in packages) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(intent)
                    launched = true
                    break
                }
            } catch (_: Exception) {}
        }

        if (!launched) {
            Toast.makeText(this, "Launch failed! Please start the game manually.", Toast.LENGTH_LONG).show()
        }
    }
}
