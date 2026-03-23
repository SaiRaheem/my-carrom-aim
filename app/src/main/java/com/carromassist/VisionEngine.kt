package com.carromassist

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object VisionEngine {

    // HSV ranges maintained for color labeling
    private val STRIKER_LOWER = Scalar(15.0, 100.0, 100.0)
    private val STRIKER_UPPER = Scalar(40.0, 255.0, 255.0)
    private val WHITE_LOWER = Scalar(0.0, 0.0, 180.0)
    private val WHITE_UPPER = Scalar(180.0, 50.0, 255.0)
    private val RED_LOWER1 = Scalar(0.0, 100.0, 100.0)
    private val RED_UPPER1 = Scalar(10.0, 255.0, 255.0)
    private val RED_LOWER2 = Scalar(165.0, 100.0, 100.0)
    private val RED_UPPER2 = Scalar(180.0, 255.0, 255.0)
    private val BOARD_LOWER = Scalar(30.0, 40.0, 40.0)
    private val BOARD_UPPER = Scalar(100.0, 255.0, 240.0)

    data class DetectionResult(
        val striker: Circle?,
        val coins: List<Circle>,
        val boardRect: android.graphics.RectF?,
        val pockets: List<PointF>
    )

    fun detect(bitmap: Bitmap): DetectionResult {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.medianBlur(gray, gray, 5)

        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV)

        val boardRect = detectBoard(hsv, src.width(), src.height())
        val pockets   = boardRect?.let { derivePockets(it) } ?: emptyList()

        // Robust Circle Detection (Shape-based, not just color)
        // This is where bitAIM+ logic is mirrored
        val circles = Mat()
        Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT,
            1.0, 30.0, 100.0, 25.0, 15, 45)

        var striker: Circle? = null
        val coins = mutableListOf<Circle>()

        if (!circles.empty()) {
            for (i in 0 until circles.cols()) {
                val data = circles.get(0, i) ?: continue
                val cx = data[0].toFloat()
                val cy = data[1].toFloat()
                val r  = data[2].toFloat()
                val c  = Circle(cx, cy, r)

                // Sample the HSV color at circle center to identify type
                val hsvValue = hsv.get(cy.toInt(), cx.toInt()) ?: doubleArrayOf(0.0, 0.0, 0.0)
                val h = hsvValue[0]; val s = hsvValue[1]; val v = hsvValue[2]

                // Labeling Logic
                if (isStriker(h, s, v)) {
                    striker = c
                } else {
                    coins.add(c)
                }
            }
        }

        // Feed board bounds into PhysicsEngine
        boardRect?.let {
            PhysicsEngine.boardLeft   = it.left
            PhysicsEngine.boardTop    = it.top
            PhysicsEngine.boardRight  = it.right
            PhysicsEngine.boardBottom = it.bottom
            PhysicsEngine.pockets     = pockets
        }

        src.release(); gray.release(); hsv.release(); circles.release()
        return DetectionResult(striker, coins, boardRect, pockets)
    }

    private fun detectBoard(hsv: Mat, w: Int, h: Int): android.graphics.RectF? {
        val mask = Mat()
        Core.inRange(hsv, BOARD_LOWER, BOARD_UPPER, mask)
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()

        val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(largest) < w * h * 0.15) return null

        val br = Imgproc.boundingRect(largest)
        return android.graphics.RectF(
            br.x.toFloat(), br.y.toFloat(),
            (br.x + br.width).toFloat(), (br.y + br.height).toFloat()
        )
    }

    private fun derivePockets(board: android.graphics.RectF): List<PointF> {
        val p = board.width() * 0.04f
        return listOf(
            PointF(board.left + p, board.top + p),
            PointF(board.right - p, board.top + p),
            PointF(board.left + p, board.bottom - p),
            PointF(board.right - p, board.bottom - p)
        )
    }

    private fun isStriker(h: Double, s: Double, v: Double): Boolean {
        return h in 15.0..45.0 && s > 80.0 && v > 100.0
    }
}
