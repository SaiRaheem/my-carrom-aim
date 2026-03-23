package com.carromassist

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.view.WindowManager.LayoutParams
import android.view.WindowManager.LayoutParams.*
import android.view.Gravity
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.*

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIF_CHANNEL    = "carrom_assist"
        var isRunning = false
    }

    private lateinit var projection: MediaProjection
    private lateinit var imageReader: ImageReader
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var wm: WindowManager
    private lateinit var overlayView: OverlayView
    
    private var touchX = 0f
    private var touchY = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val processInterval = 60L

    private var screenW = 0
    private var screenH = 0
    private var screenDensity = 0
    private var lastDetection: VisionEngine.DetectionResult? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        
        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, buildNotification())
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        val metrics = resources.displayMetrics
        screenW       = metrics.widthPixels
        screenH       = metrics.heightPixels
        screenDensity = metrics.densityDpi

        if (resultData != null) {
            setupMediaProjection(resultCode, resultData)
            setupOverlay()
            scheduleProcessing()
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        if (::virtualDisplay.isInitialized) virtualDisplay.release()
        if (::projection.isInitialized) projection.stop()
        if (::overlayView.isInitialized) wm.removeView(overlayView)
        super.onDestroy()
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpManager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "CarromCapture",
            screenW, screenH, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )
    }

    private fun setupOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(this)

        val params = LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = Gravity.TOP or Gravity.START
        }

        overlayView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touchX = ev.rawX; touchY = ev.rawY
                }
            }
            false
        }

        wm.addView(overlayView, params)
    }

    private fun scheduleProcessing() {
        handler.postDelayed({
            processFrame()
            scheduleProcessing()
        }, processInterval)
    }

    private fun processFrame() {
        val image = try { imageReader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * screenW

            val bmp = Bitmap.createBitmap(
                screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            val croppedBmp = Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
            bmp.recycle()

            Thread {
                try {
                    val result = VisionEngine.detect(croppedBmp)
                    croppedBmp.recycle()
                    lastDetection = result

                    result.striker?.let { striker ->
                        val aimX = if (touchX > 0) touchX else striker.cx
                        val aimY = if (touchY > 0) touchY else striker.cy - 100f
                        val (dx, dy) = PhysicsEngine.normalize(aimX - striker.cx, aimY - striker.cy)
                        val traj = PhysicsEngine.trace(striker.cx, striker.cy, dx, dy,
                            result.coins, striker.r)
                        handler.post { overlayView.update(result, traj) }
                    } ?: handler.post { overlayView.update(result, null) }
                } catch (e: Exception) {
                    handler.post { Toast.makeText(this@OverlayService, "Vision Engine error", Toast.LENGTH_SHORT).show() }
                }
            }.start()

        } catch (e: Exception) {
            // Error handling
        } finally {
            image.close()
        }
    }

    private fun buildNotification(): Notification {
        val chan = NotificationChannel(NOTIF_CHANNEL, "Carrom Assist", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Carrom Assist Active")
            .setContentText("Tap to stop")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }
}

class OverlayView(context: Context) : View(context) {
    private var detection: VisionEngine.DetectionResult? = null
    private var trajectory: TrajectoryResult? = null

    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 200)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val reboundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 180, 0)
        val size = 12f
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(size, 8f), 0f)
        style = Paint.Style.STROKE
    }
    private val deflectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 80, 80)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 255, 200)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 200)
        textSize = 30f
        typeface = Typeface.MONOSPACE
    }

    fun update(det: VisionEngine.DetectionResult, traj: TrajectoryResult?) {
        detection = det
        trajectory = traj
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val det  = detection  ?: return
        val traj = trajectory ?: return

        det.striker?.let {
             // Striker Highlight
        }

        traj.segments.forEachIndexed { idx, (a, b) ->
            canvas.drawLine(a.x, a.y, b.x, b.y, if (idx == 0) aimPaint else reboundPaint)
        }

        traj.ghostBallCenter?.let { g ->
            det.striker?.let { s ->
                canvas.drawCircle(g.x, g.y, s.r, ghostPaint)
            }
        }

        traj.coinDeflectSegments.forEach { (a, b) ->
            canvas.drawLine(a.x, a.y, b.x, b.y, deflectPaint)
        }

        if (traj.pocketedCoin != null) {
            canvas.drawText("⬤ POCKET!", 100f, 150f, textPaint)
        }
    }
}
