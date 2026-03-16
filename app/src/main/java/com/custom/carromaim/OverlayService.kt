package com.custom.carromaim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        // 1. Create a Foreground Notification (Required by Android)
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "aim_channel")
            .setContentTitle("Carrom Aim Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)

        // 2. Setup the WindowManager for the Overlay
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Define how the window should behave
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        // 3. Create a Custom View to draw the lines
        // For now, we just create a simple view. Later we will use a custom Canvas view.
        overlayView = View(this)
        overlayView.setBackgroundColor(0x2200FF00) // Very faint green tint to show it's working

        windowManager.addView(overlayView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "aim_channel", "Aim Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}
