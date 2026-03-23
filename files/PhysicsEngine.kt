package com.carromassist

import android.graphics.PointF
import kotlin.math.*

data class Circle(val cx: Float, val cy: Float, val r: Float)

data class TrajectoryResult(
    val segments: List<Pair<PointF, PointF>>,   // line segments to draw
    val hitCoin: Circle?,                         // coin that will be hit (if any)
    val coinDeflectSegments: List<Pair<PointF, PointF>>, // coin path after impact
    val ghostBallCenter: PointF?,                // striker position at coin impact
    val bounceCount: Int,
    val pocketedCoin: Circle?                    // coin heading into a pocket
)

object PhysicsEngine {

    private const val STEP = 4f          // ray march step in px
    private const val MAX_STEPS = 3000
    private const val MAX_BOUNCES = 5

    /** Board bounds — set once from VisionEngine */
    var boardLeft   = 0f
    var boardTop    = 0f
    var boardRight  = 0f
    var boardBottom = 0f
    var pockets: List<PointF> = emptyList()

    /**
     * Trace striker trajectory from (sx,sy) in direction (dx,dy).
     * coins = detected coin circles, strikerR = striker radius.
     */
    fun trace(
        sx: Float, sy: Float,
        dx: Float, dy: Float,
        coins: List<Circle>,
        strikerR: Float
    ): TrajectoryResult {

        val segments = mutableListOf<Pair<PointF, PointF>>()
        var x = sx; var y = sy
        var vx = dx; var vy = dy
        var bounces = 0
        var prevX = x; var prevY = y
        var hitCoin: Circle? = null
        var ghostBall: PointF? = null
        var coinDeflectSegs = emptyList<Pair<PointF, PointF>>()
        var pocketedCoin: Circle? = null

        for (step in 0..MAX_STEPS) {
            x += vx * STEP
            y += vy * STEP

            // Wall bounces
            var bounced = false
            if (x < boardLeft + strikerR)   { x = boardLeft + strikerR;   vx = abs(vx);  bounced = true }
            if (x > boardRight - strikerR)  { x = boardRight - strikerR;  vx = -abs(vx); bounced = true }
            if (y < boardTop + strikerR)    { y = boardTop + strikerR;    vy = abs(vy);  bounced = true }
            if (y > boardBottom - strikerR) { y = boardBottom - strikerR; vy = -abs(vy); bounced = true }

            if (bounced) {
                segments += PointF(prevX, prevY) to PointF(x, y)
                prevX = x; prevY = y
                bounces++
                if (bounces >= MAX_BOUNCES) break
                continue
            }

            // Coin collision check
            for (coin in coins) {
                val dist = hypot(x - coin.cx, y - coin.cy)
                if (dist < coin.r + strikerR * 0.9f) {
                    segments += PointF(prevX, prevY) to PointF(x, y)
                    ghostBall = PointF(x, y)
                    hitCoin = coin

                    // Coin deflection direction (normal from striker center to coin center)
                    val nx = (coin.cx - x) / dist
                    val ny = (coin.cy - y) / dist

                    // Coin travels along normal, striker deflects perpendicular
                    coinDeflectSegs = traceCoinPath(
                        coin.cx, coin.cy, nx, ny, coin.r, coins - coin
                    )
                    pocketedCoin = if (coinDeflectSegs.isNotEmpty()) {
                        val endPt = coinDeflectSegs.last().second
                        if (isNearPocket(endPt.x, endPt.y, coin.r)) coin else null
                    } else null

                    return TrajectoryResult(
                        segments, hitCoin, coinDeflectSegs, ghostBall, bounces, pocketedCoin
                    )
                }
            }
        }

        segments += PointF(prevX, prevY) to PointF(x, y)
        return TrajectoryResult(segments, null, emptyList(), null, bounces, null)
    }

    /** Trace a coin's path after being struck */
    private fun traceCoinPath(
        sx: Float, sy: Float,
        dx: Float, dy: Float,
        coinR: Float,
        otherCoins: List<Circle>
    ): List<Pair<PointF, PointF>> {
        val segs = mutableListOf<Pair<PointF, PointF>>()
        var x = sx; var y = sy; var vx = dx; var vy = dy
        var prevX = x; var prevY = y
        var bounces = 0

        for (step in 0..800) {
            x += vx * STEP * 0.8f
            y += vy * STEP * 0.8f

            var bounced = false
            if (x < boardLeft + coinR)   { x = boardLeft + coinR;   vx = abs(vx);  bounced = true }
            if (x > boardRight - coinR)  { x = boardRight - coinR;  vx = -abs(vx); bounced = true }
            if (y < boardTop + coinR)    { y = boardTop + coinR;    vy = abs(vy);  bounced = true }
            if (y > boardBottom - coinR) { y = boardBottom - coinR; vy = -abs(vy); bounced = true }

            if (bounced) {
                segs += PointF(prevX, prevY) to PointF(x, y)
                prevX = x; prevY = y
                if (++bounces >= 2) break
            }

            if (isNearPocket(x, y, coinR)) {
                segs += PointF(prevX, prevY) to PointF(x, y)
                break
            }
        }
        if (segs.isEmpty()) segs += PointF(prevX, prevY) to PointF(x, y)
        return segs
    }

    private fun isNearPocket(x: Float, y: Float, coinR: Float): Boolean {
        return pockets.any { p -> hypot(x - p.x, y - p.y) < coinR * 3.5f }
    }

    /** Normalize a direction vector */
    fun normalize(dx: Float, dy: Float): Pair<Float, Float> {
        val len = hypot(dx, dy)
        return if (len < 0.001f) 0f to -1f else dx / len to dy / len
    }

    /** Angle in degrees from positive X axis */
    fun angleDeg(dx: Float, dy: Float) = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
}
