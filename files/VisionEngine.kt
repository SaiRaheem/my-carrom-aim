package com.carromassist

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * VisionEngine
 * Detects board bounds, pockets, striker, and coins from a screen bitmap
 * using OpenCV HoughCircles.
 *
 * Colour ranges are tuned for Carrom Pool's default board skin.
 * Adjust HSV ranges in companion object if using a different skin.
 */
object VisionEngine {

    // ── HSV colour ranges (Carrom Pool default skin) ──────────────────────────
    // Striker (gold/yellow)
    private val STRIKER_LOWER = Scalar(18.0, 100.0, 150.0)
    private val STRIKER_UPPER = Scalar(35.0, 255.0, 255.0)

    // White coins
    private val WHITE_LOWER = Scalar(0.0, 0.0, 200.0)
    private val WHITE_UPPER = Scalar(180.0, 40.0, 255.0)

    // Black coins
    private val BLACK_LOWER = Scalar(0.0, 0.0, 0.0)
    private val BLACK_UPPER = Scalar(180.0, 255.0, 60.0)

    // Red queen
    private val RED_LOWER1 = Scalar(0.0,  120.0, 100.0)
    private val RED_UPPER1 = Scalar(10.0, 255.0, 255.0)
    private val RED_LOWER2 = Scalar(160.0, 120.0, 100.0)
    private val RED_UPPER2 = Scalar(180.0, 255.0, 255.0)

    // Board green (to find board bounds)
    private val BOARD_LOWER = Scalar(35.0, 60.0, 60.0)
    private val BOARD_UPPER = Scalar(90.0, 255.0, 220.0)
    // ──────────────────────────────────────────────────────────────────────────

    data class DetectionResult(
        val striker: Circle?,
        val coins: List<Circle>,
        val boardRect: android.graphics.RectF?,
        val pockets: List<PointF>
    )

    fun detect(bitmap: Bitmap): DetectionResult {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV)

        val boardRect = detectBoard(hsv, src.width(), src.height())
        val pockets   = boardRect?.let { derivePockets(it) } ?: emptyList()

        val striker = detectByColor(hsv, STRIKER_LOWER, STRIKER_UPPER,
            minR = 18f, maxR = 40f, isSingle = true).firstOrNull()
            ?.let { Circle(it.cx, it.cy, it.r) }

        val whiteCoins = detectByColor(hsv, WHITE_LOWER, WHITE_UPPER, minR = 12f, maxR = 28f)
        val blackCoins = detectByColor(hsv, BLACK_LOWER, BLACK_UPPER, minR = 12f, maxR = 28f)
        val redCoins   = detectRedCoins(hsv)

        val allCoins = (whiteCoins + blackCoins + redCoins)
            .filter { c -> striker == null || dist(c, striker) > striker.r + c.r + 4 }

        // Feed board bounds into PhysicsEngine
        boardRect?.let {
            PhysicsEngine.boardLeft   = it.left
            PhysicsEngine.boardTop    = it.top
            PhysicsEngine.boardRight  = it.right
            PhysicsEngine.boardBottom = it.bottom
            PhysicsEngine.pockets     = pockets
        }

        src.release(); hsv.release()
        return DetectionResult(striker, allCoins, boardRect, pockets)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun detectBoard(hsv: Mat, w: Int, h: Int): android.graphics.RectF? {
        val mask = Mat()
        Core.inRange(hsv, BOARD_LOWER, BOARD_UPPER, mask)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(20.0, 20.0)))

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()

        val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(largest) < w * h * 0.1) return null

        val br = Imgproc.boundingRect(largest)
        return android.graphics.RectF(
            br.x.toFloat(), br.y.toFloat(),
            (br.x + br.width).toFloat(), (br.y + br.height).toFloat()
        )
    }

    private fun derivePockets(board: android.graphics.RectF): List<PointF> {
        val pad = (board.width() * 0.02f)
        return listOf(
            PointF(board.left  + pad, board.top    + pad),
            PointF(board.right - pad, board.top    + pad),
            PointF(board.left  + pad, board.bottom - pad),
            PointF(board.right - pad, board.bottom - pad)
        )
    }

    private fun detectByColor(
        hsv: Mat,
        lower: Scalar, upper: Scalar,
        minR: Float, maxR: Float,
        isSingle: Boolean = false
    ): List<Circle> {
        val mask = Mat()
        Core.inRange(hsv, lower, upper, mask)
        Imgproc.GaussianBlur(mask, mask, Size(9.0, 9.0), 2.0)

        val circles = Mat()
        Imgproc.HoughCircles(
            mask, circles, Imgproc.HOUGH_GRADIENT,
            1.2,                  // dp
            minR.toDouble() * 2,  // minDist between circles
            80.0,                 // param1 (Canny)
            18.0,                 // param2 (accumulator)
            minR.toInt(), maxR.toInt()
        )

        val result = mutableListOf<Circle>()
        if (!circles.empty()) {
            for (i in 0 until circles.cols()) {
                val data = circles.get(0, i) ?: continue
                result += Circle(data[0].toFloat(), data[1].toFloat(), data[2].toFloat())
                if (isSingle) break
            }
        }
        mask.release(); circles.release()
        return dedup(result, minR)
    }

    private fun detectRedCoins(hsv: Mat): List<Circle> {
        val mask1 = Mat(); val mask2 = Mat(); val mask = Mat()
        Core.inRange(hsv, RED_LOWER1, RED_UPPER1, mask1)
        Core.inRange(hsv, RED_LOWER2, RED_UPPER2, mask2)
        Core.add(mask1, mask2, mask)
        mask1.release(); mask2.release()

        val circles = Mat()
        Imgproc.GaussianBlur(mask, mask, Size(9.0, 9.0), 2.0)
        Imgproc.HoughCircles(mask, circles, Imgproc.HOUGH_GRADIENT,
            1.2, 30.0, 80.0, 18.0, 10, 30)

        val result = mutableListOf<Circle>()
        if (!circles.empty()) {
            val data = circles.get(0, 0) ?: return emptyList()
            result += Circle(data[0].toFloat(), data[1].toFloat(), data[2].toFloat())
        }
        mask.release(); circles.release()
        return result
    }

    /** Remove duplicate circles that are too close together */
    private fun dedup(circles: List<Circle>, minR: Float): List<Circle> {
        val kept = mutableListOf<Circle>()
        for (c in circles) {
            if (kept.none { dist(it, c) < minR * 1.8f }) kept += c
        }
        return kept
    }

    private fun dist(a: Circle, b: Circle) =
        kotlin.math.hypot((a.cx - b.cx).toDouble(), (a.cy - b.cy).toDouble()).toFloat()
}
