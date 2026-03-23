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
import android.widget.*
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
    private lateinit var menuContainer: FrameLayout
    
    private var touchX = -1f; private var touchY = -1f
    private val handler = Handler(Looper.getMainLooper())
    private var isMenuOpen = false

    private var screenW = 0; private var screenH = 0; private var screenDensity = 0
    private var bufferBitmap: Bitmap? = null; private var procBitmap: Bitmap? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        isRunning = true
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels; screenH = metrics.heightPixels; screenDensity = metrics.densityDpi

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        if (resultData != null) {
            setupMediaProjection(resultCode, resultData)
            setupOverlay()
            scheduleProcessing()
        } else { stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false; handler.removeCallbacksAndMessages(null)
        if (::virtualDisplay.isInitialized) virtualDisplay.release()
        if (::projection.isInitialized) projection.stop()
        if (::overlayView.isInitialized) wm.removeView(overlayView)
        super.onDestroy()
    }

    private fun setupMediaProjection(code: Int, data: Intent) {
        val mpM = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpM.getMediaProjection(code, data)
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay("CarromCapture", screenW, screenH, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)
    }

    private fun setupOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(this)
        
        val params = LayoutParams(MATCH_PARENT, MATCH_PARENT, TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

        overlayView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                touchX = ev.rawX; touchY = ev.rawY
            }
            false
        }
        wm.addView(overlayView, params)
    }

    private fun scheduleProcessing() {
        if (!isRunning) return
        handler.postDelayed({ processFrame(); scheduleProcessing() }, 35L)
    }

    private fun processFrame() {
        val img = try { imageReader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val pl = img.planes[0]
            val buf = pl.buffer; val rStr = pl.rowStride; val pStr = pl.pixelStride
            if (bufferBitmap == null) {
                bufferBitmap = Bitmap.createBitmap(rStr/pStr, screenH, Bitmap.Config.ARGB_8888)
                procBitmap = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            }
            bufferBitmap!!.copyPixelsFromBuffer(buf)
            Canvas(procBitmap!!).drawBitmap(bufferBitmap!!, 0f, 0f, null)

            Thread {
                try {
                    val res = VisionEngine.detect(procBitmap!!)
                    res.striker?.let { s ->
                        val dx = (if (touchX > 0) touchX else s.cx) - s.cx
                        val dy = (if (touchY > 0) touchY else s.cy - 150f) - s.cy
                        val (nx, ny) = PhysicsEngine.normalize(dx, dy)
                        val traj = PhysicsEngine.trace(s.cx, s.cy, nx, ny, res.coins, s.r)
                        handler.post { overlayView.update(res, traj) }
                    } ?: handler.post { overlayView.update(res, null) }
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {} finally { img.close() }
    }

    private fun buildNotification(): Notification {
        val chan = NotificationChannel(NOTIF_CHANNEL, "Carrom Pro", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL).setContentTitle("bitAIM+ Mode Activated").setContentText("Optimized for Carrom Disc Pool").setSmallIcon(android.R.drawable.ic_menu_view).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kill", stop).build()
    }
}

class OverlayView(context: Context) : View(context) {
    private var detection: VisionEngine.DetectionResult? = null
    private var trajectory: TrajectoryResult? = null
    private val COLOR_MAIN = Color.parseColor("#00FFC8"); private val COLOR_REB = Color.parseColor("#FFB400"); private val COLOR_DEF = Color.parseColor("#FF5050")
    private val aimP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_MAIN; strokeWidth = 5f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(25f, 15f), 0f); setShadowLayer(10f, 0f, 0f, COLOR_MAIN) }
    private val rebP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_REB; strokeWidth = 4f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f); setShadowLayer(8f, 0f, 0f, COLOR_REB) }
    private val defP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_DEF; strokeWidth = 5f; style = Paint.Style.STROKE; setShadowLayer(12f, 0f, 0f, COLOR_DEF) }
    private val ghstP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 0, 255, 200); style = Paint.Style.FILL; setShadowLayer(20f, 0f, 0f, COLOR_MAIN) }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_MAIN; textSize = 40f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setShadowLayer(10f, 0f, 0f, Color.BLACK) }
    private val coinP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255); strokeWidth = 2f; style = Paint.Style.STROKE }

    fun update(d: VisionEngine.DetectionResult, t: TrajectoryResult?) { detection = d; trajectory = t; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val d = detection ?: return
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        d.coins.forEach { c -> canvas.drawCircle(c.cx, c.cy, c.r, coinP) }
        val t = trajectory ?: return
        t.segments.forEachIndexed { i, (a, b) -> canvas.drawLine(a.x, a.y, b.x, b.y, if (i == 0) aimP else rebP) }
        t.ghostBallCenter?.let { g -> d.striker?.let { s -> canvas.drawCircle(g.x, g.y, s.r, ghstP) } }
        t.coinDeflectSegments.forEach { (a, b) -> canvas.drawLine(a.x, a.y, b.x, b.y, defP) }
        if (t.pocketedCoin != null) {
            val bg = Paint().apply { color = Color.argb(200, 0, 0, 0) }
            canvas.drawRoundRect(80f, 100f, 380f, 180f, 20f, 20f, bg)
            canvas.drawText("🎯 POCKET!", 110f, 155f, txtP)
        }
    }
}
