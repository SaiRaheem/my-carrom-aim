package com.carromassist

import android.graphics.PointF
import kotlin.math.*

data class Circle(val cx: Float, val cy: Float, val r: Float)

data class TrajectoryResult(
    val segments: List<Pair<PointF, PointF>>,
    val hitCoin: Circle?,
    val coinDeflectSegments: List<Pair<PointF, PointF>>,
    val ghostBallCenter: PointF?,
    val bounceCount: Int,
    val pocketedCoin: Circle?
)

object PhysicsEngine {

    private const val STEP = 3f
    private const val MAX_STEPS = 4000
    private const val MAX_BOUNCES = 6

    var boardLeft   = 0f
    var boardTop    = 0f
    var boardRight  = 0f
    var boardBottom = 0f
    var pockets: List<PointF> = emptyList()

    fun trace(sx: Float, sy: Float, dx: Float, dy: Float, coins: List<Circle>, strikerR: Float): TrajectoryResult {
        val segments = mutableListOf<Pair<PointF, PointF>>()
        var x = sx; var y = sy
        var vx = dx; var vy = dy
        var bounces = 0
        var prevX = x; var prevY = y
        var hitCoin: Circle? = null
        var ghostBall: PointF? = null
        var deflectSegs = mutableListOf<Pair<PointF, PointF>>()
        var pocketed: Circle? = null

        for (step in 0..MAX_STEPS) {
            val nextX = x + vx * STEP
            val nextY = y + vy * STEP

            var wallBounced = false
            if (nextX < boardLeft + strikerR) { vx = abs(vx); wallBounced = true }
            else if (nextX > boardRight - strikerR) { vx = -abs(vx); wallBounced = true }
            if (nextY < boardTop + strikerR) { vy = abs(vy); wallBounced = true }
            else if (nextY > boardBottom - strikerR) { vy = -abs(vy); wallBounced = true }

            if (wallBounced) {
                segments += PointF(prevX, prevY) to PointF(x, y)
                prevX = x; prevY = y
                if (++bounces >= MAX_BOUNCES) break
                continue
            }
            x = nextX; y = nextY

            for (coin in coins) {
                val d = sqrt((x - coin.cx).pow(2) + (y - coin.cy).pow(2))
                if (d < coin.r + strikerR) {
                    segments += PointF(prevX, prevY) to PointF(x, y)
                    ghostBall = PointF(x, y)
                    hitCoin = coin
                    val nx = (coin.cx - x) / d
                    val ny = (coin.cy - y) / d
                    deflectSegs.addAll(traceCoinPath(coin.cx, coin.cy, nx, ny, coin.r, coins - coin))
                    if (deflectSegs.isNotEmpty() && isNearPocket(deflectSegs.last().second.x, deflectSegs.last().second.y, coin.r)) {
                        pocketed = coin
                    }
                    return TrajectoryResult(segments, hitCoin, deflectSegs, ghostBall, bounces, pocketed)
                }
            }
        }
        segments += PointF(prevX, prevY) to PointF(x, y)
        return TrajectoryResult(segments, null, emptyList(), null, bounces, null)
    }

    private fun traceCoinPath(sx: Float, sy: Float, dx: Float, dy: Float, coinR: Float, others: List<Circle>): List<Pair<PointF, PointF>> {
        val segs = mutableListOf<Pair<PointF, PointF>>()
        var x = sx; var y = sy; var vx = dx; var vy = dy
        var pX = x; var pY = y
        var b = 0
        for (step in 0..1500) {
            x += vx * STEP; y += vy * STEP
            var wallB = false
            if (x < boardLeft + coinR) { vx = abs(vx); wallB = true }
            else if (x > boardRight - coinR) { vx = -abs(vx); wallB = true }
            if (y < boardTop + coinR) { vy = abs(vy); wallB = true }
            else if (y > boardBottom - coinR) { vy = -abs(vy); wallB = true }
            
            if (wallB) {
                segs += PointF(pX, pY) to PointF(x, y)
                pX = x; pY = y
                if (++b >= 3) break
            }
            if (isNearPocket(x, y, coinR)) {
                segs += PointF(pX, pY) to PointF(x, y)
                break
            }
        }
        if (segs.isEmpty()) segs += PointF(pX, pY) to PointF(x, y)
        return segs
    }

    private fun isNearPocket(x: Float, y: Float, coinR: Float): Boolean =
        pockets.any { sqrt((x - it.x).pow(2) + (y - it.y).pow(2)) < coinR * 4.0f }

    fun normalize(dx: Float, dy: Float): Pair<Float, Float> {
        val l = sqrt(dx*dx + dy*dy)
        return if (l < 0.001f) 0f to -1f else dx / l to dy / l
    }
}
