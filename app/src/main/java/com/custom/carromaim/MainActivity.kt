package com.custom.carromaim

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple Layout with a Start Button
        val startButton = Button(this).apply {
            text = "Start Aim Assistant"
        }
        setContentView(startButton)

        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        // 1. Check for Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 101)
            Toast.makeText(this, "Please enable Screen Overlay permission", Toast.LENGTH_LONG).show()
        } else {
            startAimService()
        }
    }

    private fun startAimService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Aim Assistant Started!", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            if (Settings.canDrawOverlays(this)) {
                startAimService()
            }
        }
    }
}
