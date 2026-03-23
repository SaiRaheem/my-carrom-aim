package com.carromassist

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object VisionEngine {

    // Tighter, more professional HSV ranges (Match-Day Tuning)
    private val STRIKER_LOWER = Scalar(15.0, 110.0, 140.0) // Stronger Yellow/Gold
    private val STRIKER_UPPER = Scalar(40.0, 255.0, 255.0)
    
    // Board detection (looking for the actual match surface green)
    private val BOARD_LOWER = Scalar(35.0, 40.0, 30.0)
    private val BOARD_UPPER = Scalar(85.0, 255.0, 250.0)

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
        
        // bitAIM+ logic: If we don't have a solid board, stop processing to save battery!
        if (boardRect == null) {
            src.release(); hsv.release()
            return DetectionResult(null, emptyList(), null, emptyList())
        }

        val pockets = derivePockets(boardRect)
        
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.medianBlur(gray, gray, 5)

        val circles = Mat()
        // Hough tuning: param2=25 is sensitive, 30 is strict. Using 32 for pro-accuracy.
        Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT,
            1.2, 35.0, 110.0, 32.0, 14, 42)

        var striker: Circle? = null
        val coins = mutableListOf<Circle>()

        if (!circles.empty()) {
            for (i in 0 until circles.cols()) {
                val data = circles.get(0, i) ?: continue
                val cx = data[0].toFloat(); val cy = data[1].toFloat(); val r = data[2].toFloat()
                
                // IGNORE everything outside the board bounds! (Crucial fix for menu clutter)
                if (cx < boardRect.left || cx > boardRect.right || cy < boardRect.top || cy > boardRect.bottom) continue

                val hsvValue = hsv.get(cy.toInt(), cx.toInt()) ?: doubleArrayOf(0.0, 0.0, 0.0)
                if (isStriker(hsvValue[0], hsvValue[1], hsvValue[2])) {
                    striker = Circle(cx, cy, r)
                } else {
                    coins.add(Circle(cx, cy, r))
                }
            }
        }

        src.release(); gray.release(); hsv.release(); circles.release()
        return DetectionResult(striker, coins, boardRect, pockets)
    }

    private fun detectBoard(hsv: Mat, w: Int, h: Int): android.graphics.RectF? {
        val mask = Mat()
        Core.inRange(hsv, BOARD_LOWER, BOARD_UPPER, mask)
        val ctrs = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, ctrs, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()

        val largest = ctrs.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val area = Imgproc.contourArea(largest)
        if (area < w * h * 0.15) return null // Must be at least 15% of screen

        val br = Imgproc.boundingRect(largest)
        
        // NEW Pro Check: Carrom boards are roughly SQUARE (ratio 0.8 to 1.25)
        val ratio = br.width.toFloat() / br.height.toFloat()
        if (ratio < 0.7f || ratio > 1.4f) return null

        return android.graphics.RectF(
            br.x.toFloat(), br.y.toFloat(),
            (br.x + br.width).toFloat(), (br.y + br.height).toFloat()
        )
    }

    private fun derivePockets(board: android.graphics.RectF): List<PointF> {
        val p = board.width() * 0.05f
        return listOf(
            PointF(board.left + p, board.top + p),
            PointF(board.right - p, board.top + p),
            PointF(board.left + p, board.bottom - p),
            PointF(board.right - p, board.bottom - p)
        )
    }

    private fun isStriker(h: Double, s: Double, v: Double): Boolean {
        return h in 12.0..48.0 && s > 90.0 && v > 120.0
    }
}
