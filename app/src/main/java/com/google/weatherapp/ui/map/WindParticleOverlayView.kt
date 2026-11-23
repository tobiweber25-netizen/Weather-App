package com.google.weatherapp.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.google.weatherapp.data.map.WeatherGridField
import com.google.weatherapp.data.map.WindMetricMode
import com.google.weatherapp.theme.MapWindParticleCalm
import com.google.weatherapp.theme.MapWindParticleGale
import com.google.weatherapp.theme.MapWindParticleStrong
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight 60fps hardware-accelerated Windy-style flowing wind streamline overlay.
 *
 * Key smoothness guarantees:
 * - Zero Compose recompositions (runs purely via Choreographer on its own Android View).
 * - Zero MapLibre JNI calls inside the 60fps loop (uses a cached 5x5 screen-to-latlon warp grid
 *   updated only when the map camera settles).
 * - Uses an offscreen fading-trail Bitmap with DST_IN alpha decay for silky tapered wind wakes.
 * - Never intercepts touch events, so MapLibre pan/pinch/rotate/tap stay 100% native.
 */
class WindParticleOverlayView(context: Context) : View(context), Choreographer.FrameCallback {

    private val random = Random(System.nanoTime())
    private val particleCount = 380
    private val px = FloatArray(particleCount)
    private val py = FloatArray(particleCount)
    private val age = IntArray(particleCount)
    private val maxAge = IntArray(particleCount)

    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var bufferScale = 0.75f
    private var bufWidth = 0
    private var bufHeight = 0

    // Cached 5x5 screen-to-geographic lookup grid (updated on camera settle; zero JNI per frame!)
    private val warpN = 5
    private val warpLats = DoubleArray(warpN * warpN)
    private val warpLons = DoubleArray(warpN * warpN)
    private var hasWarpCalibration = false
    val isCalibrated: Boolean get() = hasWarpCalibration
    private var mapBearingRad = 0.0
    private var metersPerPixel = 1500.0

    private var gridField: WeatherGridField? = null
    private var hourIndex: Int = 0
    private var windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED
    private var streamlinesEnabled: Boolean = false
    private var isCameraMoving: Boolean = false
    private var subtleOverlayMode: Boolean = false
    private var isRunning: Boolean = false

    private val fadePaint = Paint().apply {
        color = 0xDE000000.toInt() // ~0.87 retention per frame for luminous flowing wakes
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val calmColor = MapWindParticleCalm.toArgb()
    private val strongColor = MapWindParticleStrong.toArgb()
    private val galeColor = MapWindParticleGale.toArgb()

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    fun updateWeatherState(
        grid: WeatherGridField?,
        hourIndex: Int,
        windMetricMode: WindMetricMode,
        enabled: Boolean,
        subtleMode: Boolean
    ) {
        val hourOrGridChanged = this.gridField !== grid || this.hourIndex != hourIndex
        this.gridField = grid
        this.hourIndex = hourIndex
        this.windMetricMode = windMetricMode
        this.streamlinesEnabled = enabled
        this.subtleOverlayMode = subtleMode
        blitPaint.alpha = if (subtleMode) 176 else 242

        if (!enabled) {
            clearTrailBuffer()
            stopLoop()
            invalidate()
            return
        }
        if (hourOrGridChanged) {
            clearTrailBuffer()
            respawnAllParticles()
        }
        maybeStartLoop()
    }

    fun setCameraMoving(moving: Boolean) {
        if (this.isCameraMoving == moving) return
        this.isCameraMoving = moving
        if (moving) {
            clearTrailBuffer()
            invalidate()
        } else if (streamlinesEnabled && hasWarpCalibration) {
            respawnAllParticles()
            maybeStartLoop()
        }
    }

    /**
     * Calibrates the 5x5 screen-to-lat/lon warp grid once after camera settles.
     */
    fun updateProjectionCalibration(
        bearingDegrees: Double,
        zoom: Double,
        centerLatitude: Double,
        projectScreenToLatLon: (screenX: Float, screenY: Float) -> Pair<Double, Double>?
    ) {
        if (width <= 0 || height <= 0) return
        var valid = true
        for (r in 0 until warpN) {
            val sy = (r.toFloat() / (warpN - 1)) * height
            for (c in 0 until warpN) {
                val sx = (c.toFloat() / (warpN - 1)) * width
                val ll = projectScreenToLatLon(sx, sy)
                if (ll == null) {
                    valid = false
                    break
                }
                warpLats[r * warpN + c] = ll.first
                warpLons[r * warpN + c] = ll.second
            }
            if (!valid) break
        }
        hasWarpCalibration = valid
        mapBearingRad = Math.toRadians(bearingDegrees)
        metersPerPixel = (156543.03392 * cos(Math.toRadians(centerLatitude.coerceIn(-80.0, 80.0)))) /
            Math.pow(2.0, zoom.coerceIn(1.0, 16.0))
        if (valid && streamlinesEnabled && !isCameraMoving) {
            clearTrailBuffer()
            respawnAllParticles()
            maybeStartLoop()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        allocateBuffer(w, h)
        respawnAllParticles()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        maybeStartLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        releaseBuffer()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        if (streamlinesEnabled && !isCameraMoving && hasWarpCalibration && gridField != null && trailCanvas != null) {
            stepAndDrawToBuffer()
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        if (!streamlinesEnabled || isCameraMoving) return
        val bmp = trailBitmap ?: return
        if (bmp.isRecycled) return
        canvas.save()
        canvas.scale(1f / bufferScale, 1f / bufferScale)
        canvas.drawBitmap(bmp, 0f, 0f, blitPaint)
        canvas.restore()
    }

    private fun maybeStartLoop() {
        if (isRunning || !isAttachedToWindow) return
        if (!streamlinesEnabled) return
        isRunning = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        if (!isRunning) return
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun allocateBuffer(w: Int, h: Int) {
        releaseBuffer()
        if (w <= 0 || h <= 0) return
        val bw = (w * bufferScale).toInt().coerceAtLeast(1)
        val bh = (h * bufferScale).toInt().coerceAtLeast(1)
        bufWidth = bw
        bufHeight = bh
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        trailBitmap = bmp
        trailCanvas = Canvas(bmp)
    }

    private fun clearTrailBuffer() {
        trailCanvas?.drawColor(0, PorterDuff.Mode.CLEAR)
    }

    private fun releaseBuffer() {
        trailCanvas = null
        trailBitmap?.let { if (!it.isRecycled) it.recycle() }
        trailBitmap = null
        bufWidth = 0
        bufHeight = 0
    }

    private fun respawnAllParticles() {
        val bw = bufWidth.coerceAtLeast(1).toFloat()
        val bh = bufHeight.coerceAtLeast(1).toFloat()
        for (i in 0 until particleCount) {
            px[i] = random.nextFloat() * bw
            py[i] = random.nextFloat() * bh
            val life = 45 + random.nextInt(55)
            maxAge[i] = life
            age[i] = random.nextInt(life)
        }
    }

    private fun respawnParticle(i: Int) {
        px[i] = random.nextFloat() * bufWidth
        py[i] = random.nextFloat() * bufHeight
        maxAge[i] = 45 + random.nextInt(55)
        age[i] = 0
    }

    private fun stepAndDrawToBuffer() {
        val canvas = trailCanvas ?: return
        val grid = gridField ?: return
        if (grid.hourCount <= 0 || grid.rows < 2 || grid.cols < 2 || bufWidth <= 1 || bufHeight <= 1) return

        // 1. Fade previous frame's trails smoothly
        canvas.drawRect(0f, 0f, bufWidth.toFloat(), bufHeight.toFloat(), fadePaint)

        val safeHour = hourIndex.coerceIn(0, grid.hourCount - 1)
        val hourBase = safeHour * grid.pointCount
        val latSpan = (grid.northLat - grid.southLat).coerceAtLeast(1e-6)
        val lonSpan = (grid.eastLon - grid.westLon).coerceAtLeast(1e-6)
        val cosBear = cos(mapBearingRad).toFloat()
        val sinBear = sin(mapBearingRad).toFloat()
        val density = resources.displayMetrics.density
        val minStroke = 1.15f * density * bufferScale
        val maxStroke = 2.05f * density * bufferScale
        // Visual velocity step tuned for pleasant Windy-like flow across regional & city zooms
        val speedPixelScale = 0.082f * density * bufferScale
        val useGusts = windMetricMode == WindMetricMode.GUSTS

        val invBufW = 1f / bufWidth
        val invBufH = 1f / bufHeight
        val maxGridC = grid.cols - 2
        val maxGridR = grid.rows - 2
        val warpSpan = warpN - 1

        for (i in 0 until particleCount) {
            val curAge = age[i]
            val curMax = maxAge[i]
            if (curAge >= curMax) {
                respawnParticle(i)
                continue
            }

            val oldX = px[i]
            val oldY = py[i]
            val sxNorm = (oldX * invBufW).coerceIn(0f, 1f)
            val syNorm = (oldY * invBufH).coerceIn(0f, 1f)

            // Fast bilinear lookup inside 5x5 screen->lat/lon warp grid
            val wx = sxNorm * warpSpan
            val wy = syNorm * warpSpan
            val wc0 = floor(wx).toInt().coerceIn(0, warpSpan - 1)
            val wr0 = floor(wy).toInt().coerceIn(0, warpSpan - 1)
            val wtx = wx - wc0
            val wty = wy - wr0
            val w00 = wr0 * warpN + wc0
            val w10 = w00 + 1
            val w01 = w00 + warpN
            val w11 = w01 + 1

            val latTop = warpLats[w00] + (warpLats[w10] - warpLats[w00]) * wtx
            val latBot = warpLats[w01] + (warpLats[w11] - warpLats[w01]) * wtx
            val lat = latTop + (latBot - latTop) * wty

            val lonTop = warpLons[w00] + (warpLons[w10] - warpLons[w00]) * wtx
            val lonBot = warpLons[w01] + (warpLons[w11] - warpLons[w01]) * wtx
            val lon = lonTop + (lonBot - lonTop) * wty

            if (lat < grid.southLat || lat > grid.northLat || lon < grid.westLon || lon > grid.eastLon) {
                respawnParticle(i)
                continue
            }

            // Fast inline bilinear wind u/v lookup directly in FloatArray
            val gx = ((lon - grid.westLon) / lonSpan) * (grid.cols - 1)
            val gy = ((lat - grid.southLat) / latSpan) * (grid.rows - 1)
            val c0 = floor(gx).toInt().coerceIn(0, maxGridC)
            val r0 = floor(gy).toInt().coerceIn(0, maxGridR)
            val tx = (gx - c0).toFloat()
            val ty = (gy - r0).toFloat()

            val idx00 = hourBase + r0 * grid.cols + c0
            val idx10 = idx00 + 1
            val idx01 = idx00 + grid.cols
            val idx11 = idx01 + 1

            val uTop = grid.windU[idx00] + (grid.windU[idx10] - grid.windU[idx00]) * tx
            val uBot = grid.windU[idx01] + (grid.windU[idx11] - grid.windU[idx01]) * tx
            var u = uTop + (uBot - uTop) * ty

            val vTop = grid.windV[idx00] + (grid.windV[idx10] - grid.windV[idx00]) * tx
            val vBot = grid.windV[idx01] + (grid.windV[idx11] - grid.windV[idx01]) * tx
            var v = vTop + (vBot - vTop) * ty

            var speed = hypot(u.toDouble(), v.toDouble()).toFloat()
            if (useGusts && speed > 0.5f) {
                val gTop = grid.windGustsKmH[idx00] + (grid.windGustsKmH[idx10] - grid.windGustsKmH[idx00]) * tx
                val gBot = grid.windGustsKmH[idx01] + (grid.windGustsKmH[idx11] - grid.windGustsKmH[idx01]) * tx
                val gust = gTop + (gBot - gTop) * ty
                val ratio = (gust / speed).coerceIn(1f, 2.2f)
                u *= ratio
                v *= ratio
                speed *= ratio
            }

            if (speed < 0.6f) {
                age[i] = curAge + 2
                continue
            }

            // Screen-space delta respecting MapLibre clockwise bearing (screen +Y is South, so -v is North)
            val eastDx = u * speedPixelScale
            val southDy = -v * speedPixelScale
            val stepX = eastDx * cosBear + southDy * sinBear
            val stepY = -eastDx * sinBear + southDy * cosBear

            val newX = oldX + stepX
            val newY = oldY + stepY

            if (newX < 0f || newX >= bufWidth || newY < 0f || newY >= bufHeight) {
                respawnParticle(i)
                continue
            }

            // Smooth life-cycle envelope (soft fade-in + gentle fade-out)
            val lifeNorm = curAge.toFloat() / curMax.toFloat()
            val envelope = when {
                lifeNorm < 0.16f -> lifeNorm / 0.16f
                lifeNorm > 0.80f -> (1f - lifeNorm) / 0.20f
                else -> 1f
            }.coerceIn(0f, 1f)

            val baseColor = when {
                speed >= 62f -> galeColor
                speed >= 32f -> strongColor
                else -> calmColor
            }
            val speedAlphaBoost = (0.48f + (speed / 55f).coerceIn(0f, 0.52f))
            val finalAlpha = ((baseColor ushr 24) * envelope * speedAlphaBoost).toInt().coerceIn(18, 255)
            particlePaint.color = (finalAlpha shl 24) or (baseColor and 0x00FFFFFF)
            particlePaint.strokeWidth = minStroke + ((speed / 70f).coerceIn(0f, 1f) * (maxStroke - minStroke))

            canvas.drawLine(oldX, oldY, newX, newY, particlePaint)

            px[i] = newX
            py[i] = newY
            age[i] = curAge + 1
        }
    }
}
