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
import androidx.core.app.NotificationCompat
import kotlin.math.*

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIF_CHANNEL    = "carrom_assist"
        var isRunning = false
    }

    // Screen capture
    private lateinit var projection: MediaProjection
    private lateinit var imageReader: ImageReader
    private lateinit var virtualDisplay: VirtualDisplay

    // Overlay
    private lateinit var wm: WindowManager
    private lateinit var overlayView: OverlayView

    // Touch tracking (for aim direction from striker)
    private var touchX = 0f
    private var touchY = 0f

    // Processing
    private val handler = Handler(Looper.getMainLooper())
    private val processInterval = 60L   // ms between frames (~16fps)

    private var screenW = 0
    private var screenH = 0
    private var screenDensity = 0

    private var lastDetection: VisionEngine.DetectionResult? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

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

    // ── Screen capture setup ──────────────────────────────────────────────────

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

    // ── Overlay view setup ────────────────────────────────────────────────────

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

        // Forward touches to detect aim direction
        overlayView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touchX = ev.rawX; touchY = ev.rawY
                }
            }
            false   // pass through to game
        }

        wm.addView(overlayView, params)
    }

    // ── Processing loop ───────────────────────────────────────────────────────

    private fun scheduleProcessing() {
        handler.postDelayed({
            processFrame()
            scheduleProcessing()
        }, processInterval)
    }

    private fun processFrame() {
        val image = imageReader.acquireLatestImage() ?: return
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

            // Run detection on background thread
            Thread {
                val result = VisionEngine.detect(croppedBmp)
                croppedBmp.recycle()
                lastDetection = result

                // Compute trajectory from striker toward touch point
                result.striker?.let { striker ->
                    val aimX = if (touchX > 0) touchX else striker.cx
                    val aimY = if (touchY > 0) touchY else striker.cy - 100f
                    val (dx, dy) = PhysicsEngine.normalize(aimX - striker.cx, aimY - striker.cy)
                    val traj = PhysicsEngine.trace(striker.cx, striker.cy, dx, dy,
                        result.coins, striker.r)
                    handler.post { overlayView.update(result, traj) }
                } ?: handler.post { overlayView.update(result, null) }
            }.start()

        } finally {
            image.close()
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

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
            .setContentText("Overlay running — tap to stop")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }
}

// ── OverlayView ───────────────────────────────────────────────────────────────

class OverlayView(context: Context) : View(context) {

    private var detection: VisionEngine.DetectionResult? = null
    private var trajectory: TrajectoryResult? = null

    // Paints
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 200)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val reboundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 180, 0)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val deflectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 80, 80)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 255, 200)
        style = Paint.Style.FILL
    }
    private val ghostStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 200)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 220, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val coinDetectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val strikerDetectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 220, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 200)
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 200)
        style = Paint.Style.FILL
    }

    fun update(det: VisionEngine.DetectionResult, traj: TrajectoryResult?) {
        detection = det
        trajectory = traj
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val det  = detection  ?: return
        val traj = trajectory ?: return

        // Draw detected coin outlines (debug layer)
        for (coin in det.coins) {
            canvas.drawCircle(coin.cx, coin.cy, coin.r, coinDetectPaint)
        }

        // Striker highlight
        det.striker?.let {
            canvas.drawCircle(it.cx, it.cy, it.r + 4f, strikerDetectPaint)
        }

        // Pocket highlights
        for (p in det.pockets) {
            canvas.drawCircle(p.x, p.y, 22f, pocketPaint)
        }

        // Aim trajectory
        traj.segments.forEachIndexed { idx, (a, b) ->
            val paint = if (idx == 0) aimPaint else reboundPaint
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            if (idx == 0) drawArrow(canvas, a, b)
        }

        // Ghost ball at coin impact
        traj.ghostBallCenter?.let { g ->
            det.striker?.let { s ->
                canvas.drawCircle(g.x, g.y, s.r, ghostPaint)
                canvas.drawCircle(g.x, g.y, s.r, ghostStrokePaint)
            }
        }

        // Coin deflection path
        traj.coinDeflectSegments.forEach { (a, b) ->
            canvas.drawLine(a.x, a.y, b.x, b.y, deflectPaint)
        }

        // Pocket prediction indicator
        if (traj.pocketedCoin != null) {
            val last = traj.coinDeflectSegments.lastOrNull()?.second
            last?.let {
                canvas.drawCircle(it.x, it.y, 30f, pocketPaint)
                canvas.drawText("POCKET!", it.x - 40f, it.y - 35f, textPaint)
            }
        }

        // HUD
        drawHUD(canvas, traj)
    }

    private fun drawArrow(canvas: Canvas, from: PointF, to: PointF) {
        val dx = to.x - from.x; val dy = to.y - from.y
        val len = hypot(dx, dy); if (len < 1f) return
        val nx = dx / len; val ny = dy / len
        val midX = from.x + nx * len * 0.5f
        val midY = from.y + ny * len * 0.5f
        val size = 18f
        val path = Path().apply {
            moveTo(midX + nx * size, midY + ny * size)
            lineTo(midX - nx * size * 0.5f + ny * size * 0.8f,
                   midY - ny * size * 0.5f - nx * size * 0.8f)
            lineTo(midX - nx * size * 0.5f - ny * size * 0.8f,
                   midY - ny * size * 0.5f + nx * size * 0.8f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawHUD(canvas: Canvas, traj: TrajectoryResult) {
        val lines = mutableListOf<String>()
        lines += "BOUNCES: ${traj.bounceCount}"
        lines += if (traj.hitCoin != null) "HIT: COIN" else "HIT: NONE"
        if (traj.pocketedCoin != null) lines += "⬤ POCKET SHOT!"
        val shotType = when {
            traj.pocketedCoin != null -> "TRICK/POCKET"
            traj.hitCoin != null && traj.bounceCount > 0 -> "BANK + HIT"
            traj.hitCoin != null -> "DIRECT"
            traj.bounceCount > 0 -> "BANK SHOT"
            else -> "MISS"
        }
        lines += "SHOT: $shotType"

        val padX = 20f; var padY = 80f
        val bg = Paint().apply { color = Color.argb(130, 0, 0, 0) }
        canvas.drawRoundRect(padX - 8f, padY - 30f, padX + 240f,
            padY + lines.size * 34f, 10f, 10f, bg)
        for (line in lines) {
            canvas.drawText(line, padX, padY, textPaint)
            padY += 34f
        }
    }
}
