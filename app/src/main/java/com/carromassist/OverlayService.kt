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
        const val NOTIF_CHANNEL    = "carrom_pro"
        var isRunning = false
    }

    private lateinit var wm: WindowManager
    private lateinit var overlayView: OverlayView
    private lateinit var aimHandle: ImageView
    
    private lateinit var projection: MediaProjection
    private lateinit var imageReader: ImageReader
    private lateinit var virtualDisplay: VirtualDisplay
    
    private var aimX = -1f; private var aimY = -1f
    private val handler = Handler(Looper.getMainLooper())
    private var lastMatchTime = 0L

    private var screenW = 0; private var screenH = 0; private var screenDensity = 0
    private var bufferBmp: Bitmap? = null; private var procBmp: Bitmap? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        isRunning = true
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels; screenH = metrics.heightPixels; screenDensity = metrics.densityDpi

        val resultCode = intent!!.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)!!

        setupMediaProjection(resultCode, resultData)
        setupOverlays()
        scheduleLoop()
        
        return START_STICKY
    }

    private fun setupMediaProjection(code: Int, data: Intent) {
        val mpM = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpM.getMediaProjection(code, data)
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay("CarromPro", screenW, screenH, screenDensity, 
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)
    }

    private fun setupOverlays() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(this)
        val laserParams = LayoutParams(MATCH_PARENT, MATCH_PARENT, TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(overlayView, laserParams)

        aimHandle = ImageView(this).apply {
            setImageResource(android.R.drawable.presence_online)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.argb(80, 0, 255, 200))
        }
        
        val handleParams = LayoutParams(150, 150, TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW / 2 - 75; y = screenH - 350
        }

        aimHandle.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f; private var startY = 0f; private var initialX = 0; private var initialY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = handleParams.x; initialY = handleParams.y
                        startX = event.rawX; startY = event.rawY; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        handleParams.x = initialX + (event.rawX - startX).toInt()
                        handleParams.y = initialY + (event.rawY - startY).toInt()
                        aimX = handleParams.x.toFloat() + 75f; aimY = handleParams.y.toFloat() + 75f
                        wm.updateViewLayout(aimHandle, handleParams); return true
                    }
                }
                return false
            }
        })
        wm.addView(aimHandle, handleParams)
    }

    private fun scheduleLoop() {
        if (!isRunning) return
        // bitAIM+ logic: if no board seen for 2 seconds, slow down the loop to save battery
        val interval = if (SystemClock.uptimeMillis() - lastMatchTime > 2000) 500L else 38L
        handler.postDelayed({ processFrame(); scheduleLoop() }, interval)
    }

    private fun processFrame() {
        val image = try { imageReader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val pl = image.planes[0]
            val buf = pl.buffer; val rStr = pl.rowStride; val pStr = pl.pixelStride
            if (bufferBmp == null) {
                bufferBmp = Bitmap.createBitmap(rStr/pStr, screenH, Bitmap.Config.ARGB_8888)
                procBmp = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            }
            bufferBmp!!.copyPixelsFromBuffer(buf)
            Canvas(procBmp!!).drawBitmap(bufferBmp!!, 0f, 0f, null)

            Thread {
                try {
                    val result = VisionEngine.detect(procBmp!!)
                    if (result.boardRect != null) {
                        lastMatchTime = SystemClock.uptimeMillis() // Board detected! Start AI
                    }

                    result.striker?.let { striker ->
                        val dx = (if (aimX > 0) aimX else striker.cx) - striker.cx
                        val dy = (if (aimY > 0) aimY else striker.cy - 200f) - striker.cy
                        val (nx, ny) = PhysicsEngine.normalize(dx, dy)
                        val traj = PhysicsEngine.trace(striker.cx, striker.cy, nx, ny, result.coins, striker.r)
                        handler.post { overlayView.update(result, traj) }
                    } ?: handler.post { overlayView.update(result, null) }
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {} finally { image.close() }
    }

    private fun buildNotification(): Notification {
        val chan = NotificationChannel(NOTIF_CHANNEL, "Carrom Assist", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Sentry Mode: ACTIVE")
            .setContentText("Watching for board...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kill", stop)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        if (::aimHandle.isInitialized) wm.removeView(aimHandle)
        if (::overlayView.isInitialized) wm.removeView(overlayView)
        super.onDestroy()
    }
}

class OverlayView(context: Context) : View(context) {
    private var detection: VisionEngine.DetectionResult? = null
    private var trajectory: TrajectoryResult? = null
    private val COLOR_MAIN = Color.parseColor("#00FFC8")
    private val aimP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_MAIN; strokeWidth = 5f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(30f, 20f), 0f); setShadowLayer(15f, 0f, 0f, COLOR_MAIN) }
    private val rebP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB400"); strokeWidth = 5f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f); setShadowLayer(10f, 0f, 0f, Color.YELLOW) }
    private val defP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 4f; style = Paint.Style.STROKE; setShadowLayer(15f, 0f, 0f, Color.RED) }
    private val ghstP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 255, 200); style = Paint.Style.FILL }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_MAIN; textSize = 40f; typeface = Typeface.DEFAULT_BOLD }

    fun update(d: VisionEngine.DetectionResult, t: TrajectoryResult?) { detection = d; trajectory = t; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val det = detection ?: return
        if (det.boardRect == null) return // bitAIM+ logic: Don't draw if not in a match
        
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val traj = trajectory ?: return
        traj.segments.forEachIndexed { i, (a, b) -> canvas.drawLine(a.x, a.y, b.x, b.y, if (i == 0) aimP else rebP) }
        traj.ghostBallCenter?.let { g -> det.striker?.let { s -> canvas.drawCircle(g.x, g.y, s.r, ghstP) } }
        traj.coinDeflectSegments.forEach { (a, b) -> canvas.drawLine(a.x, a.y, b.x, b.y, defP) }
        if (traj.pocketedCoin != null) canvas.drawText("POCKET!", 100f, 200f, txtP)
    }
}
