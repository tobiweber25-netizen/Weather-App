package com.google.weatherapp.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.google.weatherapp.data.map.WeatherFieldMath
import com.google.weatherapp.data.map.WeatherGridComposite
import com.google.weatherapp.data.map.WindMetricMode
import com.google.weatherapp.theme.MapWindParticleCalm
import com.google.weatherapp.theme.MapWindParticleGale
import com.google.weatherapp.theme.MapWindParticleStrong
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure JVM-safe kinematics for wind streamline particles so velocity curves and frame-rate
 * independence (60Hz / 90Hz / 120Hz) are verified deterministically in unit tests.
 */
object WindParticleMath {
    const val MIN_FLOW_SPEED_KMH = 0.8f
    const val MAX_VISUAL_SPEED_DP_PER_SEC = 64f
    const val MAX_FRAME_DT_SECONDS = 0.05f

    /**
     * Converts meteorological wind speed (km/h) into a serene, readable Windy-style visual glide
     * speed in dp/second. Strictly monotonic so stronger winds visibly flow faster without ever
     * turning into chaotic streaks on high-refresh-rate displays.
     */
    fun visualSpeedDpPerSec(speedKmH: Float): Float {
        if (!speedKmH.isFinite() || speedKmH < MIN_FLOW_SPEED_KMH) return 0f
        val clamped = speedKmH.coerceAtMost(200f)
        return (5.5f + 14.5f * sqrt(clamped / 20f) + 0.19f * clamped)
            .coerceAtMost(MAX_VISUAL_SPEED_DP_PER_SEC)
    }

    /**
     * Converts elapsed Choreographer frame nanos into clamped physics delta-seconds so
     * 60Hz, 90Hz, and 120Hz displays animate at the exact same physical speed.
     */
    fun clampDeltaSeconds(elapsedNanos: Long): Float {
        if (elapsedNanos <= 0L) return 0f
        return (elapsedNanos / 1_000_000_000.0).toFloat().coerceIn(0.001f, MAX_FRAME_DT_SECONDS)
    }

    fun stepDistancePx(speedKmH: Float, dtSeconds: Float, density: Float): Float {
        if (dtSeconds <= 0f || density <= 0f) return 0f
        return visualSpeedDpPerSec(speedKmH) * dtSeconds * density
    }

    /**
     * Smooth C1 fade-in (first 18%) and fade-out (last 24%) envelope over particle lifecycle [0..1].
     */
    fun lifeEnvelope(lifeNorm: Float): Float {
        val n = lifeNorm.coerceIn(0f, 1f)
        val raw = when {
            n < 0.18f -> n / 0.18f
            n > 0.76f -> (1f - n) / 0.24f
            else -> 1f
        }.coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)
    }

    /**
     * Unwraps a [warpN]x[warpN] grid of longitudes in-place outward from its center cell so
     * bilinear interpolation across the ±180° antimeridian stays continuous instead of cutting
     * across 0° longitude.
     */
    fun unwrapLongitudes(lons: DoubleArray, warpN: Int) {
        if (warpN <= 0 || lons.size < warpN * warpN) return
        val mid = warpN / 2

        fun unwrapRelative(value: Double, ref: Double): Double {
            var v = value
            while (v - ref > 180.0) v -= 360.0
            while (v - ref < -180.0) v += 360.0
            return v
        }

        // Unwrap center row outward from center cell
        val midRowBase = mid * warpN
        for (c in (mid + 1) until warpN) {
            lons[midRowBase + c] = unwrapRelative(lons[midRowBase + c], lons[midRowBase + c - 1])
        }
        for (c in (mid - 1) downTo 0) {
            lons[midRowBase + c] = unwrapRelative(lons[midRowBase + c], lons[midRowBase + c + 1])
        }
        // Unwrap each column upward and downward from the center row
        for (c in 0 until warpN) {
            for (r in (mid + 1) until warpN) {
                lons[r * warpN + c] = unwrapRelative(lons[r * warpN + c], lons[(r - 1) * warpN + c])
            }
            for (r in (mid - 1) downTo 0) {
                lons[r * warpN + c] = unwrapRelative(lons[r * warpN + c], lons[(r + 1) * warpN + c])
            }
        }
    }

    /** Wraps an unwrapped longitude back into [-180.0, 180.0]. */
    fun wrapLongitude(lon: Double): Double {
        if (!lon.isFinite()) return 0.0
        var wrapped = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (wrapped == -180.0 && lon > 0.0) wrapped = 180.0
        return wrapped
    }
}

/**
 * Hardware-accelerated, frame-rate-independent (60Hz/90Hz/120Hz) Windy-style wind streamline overlay.
 *
 * Design guarantees:
 * - Zero Compose recompositions (runs purely via Choreographer on its own Android View).
 * - Zero MapLibre JNI calls inside the frame loop (uses a cached 9x9 screen-to-latlon warp grid
 *   updated only when the map camera settles).
 * - Ring-buffer tapered vector streamlines rendered directly at native 1.0x DPI with zero
 *   offscreen bitmap blur, zero DST_IN ghosting, and zero per-frame heap allocations.
 * - Never intercepts touch events, so MapLibre pan/pinch/rotate/tap stay 100% native.
 */
class WindParticleOverlayView(context: Context) : View(context), Choreographer.FrameCallback {

    private val random = Random(System.nanoTime())
    private val maxParticles = 180
    private val subtleParticleCount = 115
    private val trailCapacity = 16
    private val sampleIntervalSec = 0.055f

    private val px = FloatArray(maxParticles)
    private val py = FloatArray(maxParticles)
    private val ageSec = FloatArray(maxParticles)
    private val maxAgeSec = FloatArray(maxParticles)
    private val sampleAccumSec = FloatArray(maxParticles)
    private val lastSpeedKmH = FloatArray(maxParticles)

    // Circular trail history per particle: [particleIndex * trailCapacity + slot]
    private val trailX = FloatArray(maxParticles * trailCapacity)
    private val trailY = FloatArray(maxParticles * trailCapacity)
    private val trailHead = IntArray(maxParticles)
    private val trailCount = IntArray(maxParticles)

    // Reusable zero-allocation scratch buffers for hot-path drawing and wind sampling
    private val sampleOut = FloatArray(4)
    private val orderedX = FloatArray(trailCapacity + 1)
    private val orderedY = FloatArray(trailCapacity + 1)
    private val tierPath = Path()

    // Cached 9x9 screen-to-geographic lookup grid (updated on camera settle; zero JNI per frame!)
    private val warpN = 9
    private val warpLats = DoubleArray(warpN * warpN)
    private val warpLons = DoubleArray(warpN * warpN)
    private var hasWarpCalibration = false
    val isCalibrated: Boolean get() = hasWarpCalibration
    private var mapBearingRad = 0.0

    private var gridComposite: WeatherGridComposite? = null
    private var detailHourIndices: IntArray = IntArray(0)
    private var hourIndex: Int = 0
    private var windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED
    private var streamlinesEnabled: Boolean = false
    private var isCameraMoving: Boolean = false
    private var subtleOverlayMode: Boolean = false
    private var isRunning: Boolean = false
    private var lastFrameNanos: Long = 0L

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

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
        composite: WeatherGridComposite?,
        hourIndex: Int,
        windMetricMode: WindMetricMode,
        enabled: Boolean,
        subtleMode: Boolean
    ) {
        val hourChanged = this.hourIndex != hourIndex
        val gridChanged = this.gridComposite !== composite
        this.gridComposite = composite
        this.hourIndex = hourIndex
        this.windMetricMode = windMetricMode
        this.streamlinesEnabled = enabled
        this.subtleOverlayMode = subtleMode
        this.detailHourIndices = if (composite != null) {
            WeatherFieldMath.resolveDetailHourIndices(composite, hourIndex)
        } else {
            IntArray(0)
        }

        if (!enabled) {
            stopLoop()
            clearAllTrails()
            invalidate()
            return
        }
        if (hourChanged || (gridChanged && composite != null && !hasActiveTrails())) {
            respawnAllParticles()
        }
        maybeStartLoop()
    }

    fun setCameraMoving(moving: Boolean) {
        if (this.isCameraMoving == moving) return
        this.isCameraMoving = moving
        if (moving) {
            lastFrameNanos = 0L
            clearAllTrails()
            invalidate()
        } else if (streamlinesEnabled && hasWarpCalibration) {
            lastFrameNanos = 0L
            respawnAllParticles()
            maybeStartLoop()
        }
    }

    /**
     * Calibrates the 9x9 screen-to-lat/lon warp grid once after camera settles.
     */
    fun updateProjectionCalibration(
        bearingDegrees: Double,
        @Suppress("UNUSED_PARAMETER") zoom: Double,
        @Suppress("UNUSED_PARAMETER") centerLatitude: Double,
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
        if (valid) {
            WindParticleMath.unwrapLongitudes(warpLons, warpN)
        }
        hasWarpCalibration = valid
        mapBearingRad = Math.toRadians(bearingDegrees)
        if (valid && streamlinesEnabled && !isCameraMoving) {
            lastFrameNanos = 0L
            respawnAllParticles()
            maybeStartLoop()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            hasWarpCalibration = false
        }
        if (w > 0 && h > 0) {
            respawnAllParticles()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        maybeStartLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        if (streamlinesEnabled && !isCameraMoving && hasWarpCalibration && gridComposite != null && width > 1 && height > 1) {
            if (lastFrameNanos != 0L) {
                val dtSec = WindParticleMath.clampDeltaSeconds(frameTimeNanos - lastFrameNanos)
                if (dtSec > 0f) {
                    stepParticles(dtSec)
                    invalidate()
                }
            }
            lastFrameNanos = frameTimeNanos
        } else {
            lastFrameNanos = 0L
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        if (!streamlinesEnabled || isCameraMoving || !hasWarpCalibration || gridComposite == null) return
        val activeCount = if (subtleOverlayMode) subtleParticleCount else maxParticles
        val density = resources.displayMetrics.density
        val minStroke = 1.05f * density
        val maxStroke = 1.95f * density
        val modeAlphaScale = if (subtleOverlayMode) 0.62f else 0.94f

        for (i in 0 until activeCount) {
            val count = trailCount[i]
            if (count < 1) continue
            val speed = lastSpeedKmH[i]
            if (speed < WindParticleMath.MIN_FLOW_SPEED_KMH) continue
            val curMax = maxAgeSec[i]
            if (curMax <= 0f) continue
            val envelope = WindParticleMath.lifeEnvelope(ageSec[i] / curMax)
            if (envelope <= 0.02f) continue

            // Assemble ordered trail vertices from oldest (0) to current head (totalPts - 1)
            val baseSlot = i * trailCapacity
            val oldestIdx = (trailHead[i] - count + 1 + trailCapacity * 2) % trailCapacity
            var totalPts = 0
            for (k in 0 until count) {
                val slot = (oldestIdx + k) % trailCapacity
                orderedX[totalPts] = trailX[baseSlot + slot]
                orderedY[totalPts] = trailY[baseSlot + slot]
                totalPts++
            }
            // Append live sub-frame head position for butter-smooth 120Hz motion
            orderedX[totalPts] = px[i]
            orderedY[totalPts] = py[i]
            totalPts++

            // Smoothly retract oldest tail vertex when ring buffer is full so tail glides without snapping
            if (count == trailCapacity && totalPts >= 3) {
                val frac = (sampleAccumSec[i] / sampleIntervalSec).coerceIn(0f, 1f)
                orderedX[0] = orderedX[0] + (orderedX[1] - orderedX[0]) * frac
                orderedY[0] = orderedY[0] + (orderedY[1] - orderedY[0]) * frac
            }

            val segCount = totalPts - 1
            if (segCount <= 0) continue

            val baseColor = when {
                speed >= 62f -> galeColor
                speed >= 32f -> strongColor
                else -> calmColor
            }
            val rgb = baseColor and 0x00FFFFFF
            val baseAlpha = (baseColor ushr 24) * envelope * modeAlphaScale *
                (0.55f + (speed / 60f).coerceIn(0f, 0.45f))
            val headStroke = minStroke + ((speed / 75f).coerceIn(0f, 1f) * (maxStroke - minStroke))

            // Draw streamline in 4 tapered continuous Path tiers (zero vertex cap double-blending)
            for (tier in 0 until 4) {
                val startSeg = (tier * segCount) / 4
                val endSeg = ((tier + 1) * segCount) / 4
                if (endSeg <= startSeg) continue

                val tierAlpha = (baseAlpha * TIER_ALPHA_WEIGHTS[tier]).toInt().coerceIn(0, 255)
                if (tierAlpha < 6) continue

                tierPath.rewind()
                tierPath.moveTo(orderedX[startSeg], orderedY[startSeg])
                for (s in (startSeg + 1)..endSeg) {
                    tierPath.lineTo(orderedX[s], orderedY[s])
                }
                particlePaint.strokeCap = if (tier == 3) Paint.Cap.ROUND else Paint.Cap.BUTT
                particlePaint.color = (tierAlpha shl 24) or rgb
                particlePaint.strokeWidth = headStroke * TIER_STROKE_WEIGHTS[tier]
                canvas.drawPath(tierPath, particlePaint)
            }
        }
    }

    private fun maybeStartLoop() {
        if (isRunning || !isAttachedToWindow) return
        if (!streamlinesEnabled) return
        lastFrameNanos = 0L
        isRunning = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        if (!isRunning) return
        isRunning = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun hasActiveTrails(): Boolean {
        for (i in 0 until maxParticles) {
            if (trailCount[i] > 0) return true
        }
        return false
    }

    private fun clearAllTrails() {
        for (i in 0 until maxParticles) {
            trailCount[i] = 0
            sampleAccumSec[i] = 0f
        }
    }

    private fun respawnAllParticles() {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        for (i in 0 until maxParticles) {
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            val life = 3.6f + random.nextFloat() * 2.8f
            px[i] = x
            py[i] = y
            maxAgeSec[i] = life
            ageSec[i] = random.nextFloat() * life
            sampleAccumSec[i] = random.nextFloat() * sampleIntervalSec
            lastSpeedKmH[i] = 0f
            resetTrailAt(i, x, y)
        }
    }

    private fun respawnParticle(i: Int) {
        val x = random.nextFloat() * width
        val y = random.nextFloat() * height
        px[i] = x
        py[i] = y
        maxAgeSec[i] = 3.6f + random.nextFloat() * 2.8f
        ageSec[i] = 0f
        sampleAccumSec[i] = 0f
        lastSpeedKmH[i] = 0f
        resetTrailAt(i, x, y)
    }

    private fun resetTrailAt(i: Int, x: Float, y: Float) {
        val baseSlot = i * trailCapacity
        trailX[baseSlot] = x
        trailY[baseSlot] = y
        trailHead[i] = 0
        trailCount[i] = 1
    }

    private fun pushTrailPoint(i: Int, x: Float, y: Float) {
        val nextHead = (trailHead[i] + 1) % trailCapacity
        val baseSlot = i * trailCapacity
        trailX[baseSlot + nextHead] = x
        trailY[baseSlot + nextHead] = y
        trailHead[i] = nextHead
        val curCount = trailCount[i]
        if (curCount < trailCapacity) {
            trailCount[i] = curCount + 1
        }
    }

    private fun stepParticles(dtSec: Float) {
        val composite = gridComposite ?: return
        val base = composite.baseGrid
        if (base.hourCount <= 0 || base.rows < 2 || base.cols < 2 || width <= 1 || height <= 1) return

        val safeHour = hourIndex.coerceIn(0, base.hourCount - 1)
        val activeCount = if (subtleOverlayMode) subtleParticleCount else maxParticles
        val cosBear = cos(mapBearingRad).toFloat()
        val sinBear = sin(mapBearingRad).toFloat()
        val density = resources.displayMetrics.density
        val useGusts = windMetricMode == WindMetricMode.GUSTS
        val invW = 1f / width
        val invH = 1f / height
        val warpSpan = warpN - 1
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        for (i in 0 until activeCount) {
            val nextAge = ageSec[i] + dtSec
            if (nextAge >= maxAgeSec[i]) {
                respawnParticle(i)
                continue
            }

            val oldX = px[i]
            val oldY = py[i]
            val sxNorm = (oldX * invW).coerceIn(0f, 1f)
            val syNorm = (oldY * invH).coerceIn(0f, 1f)

            // Fast bilinear lookup inside 9x9 screen->lat/lon warp grid
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
            val lon = WindParticleMath.wrapLongitude(lonTop + (lonBot - lonTop) * wty)

            if (!WeatherFieldMath.sampleWindInto(composite, safeHour, detailHourIndices, lat, lon, sampleOut)) {
                respawnParticle(i)
                continue
            }

            val u = sampleOut[0]
            val v = sampleOut[1]
            val vectorMag = hypot(u.toDouble(), v.toDouble()).toFloat()
            val metricSpeed = if (useGusts) sampleOut[3] else sampleOut[2]

            if (metricSpeed < WindParticleMath.MIN_FLOW_SPEED_KMH || vectorMag < 0.05f) {
                lastSpeedKmH[i] = 0f
                ageSec[i] = nextAge + dtSec
                continue
            }
            lastSpeedKmH[i] = metricSpeed

            // Direction unit vector from real meteorological (u, v); magnitude from serene Windy speed curve
            val dirEast = u / vectorMag
            val dirSouth = -v / vectorMag
            val distPx = WindParticleMath.stepDistancePx(metricSpeed, dtSec, density)

            val eastDx = dirEast * distPx
            val southDy = dirSouth * distPx
            val stepX = eastDx * cosBear + southDy * sinBear
            val stepY = -eastDx * sinBear + southDy * cosBear

            val newX = oldX + stepX
            val newY = oldY + stepY
            val offscreenMargin = 36f * density

            if (newX < -offscreenMargin || newX >= viewW + offscreenMargin ||
                newY < -offscreenMargin || newY >= viewH + offscreenMargin
            ) {
                respawnParticle(i)
                continue
            }

            px[i] = newX
            py[i] = newY
            ageSec[i] = nextAge

            val accum = sampleAccumSec[i] + dtSec
            if (accum >= sampleIntervalSec) {
                pushTrailPoint(i, newX, newY)
                sampleAccumSec[i] = accum - sampleIntervalSec
            } else {
                sampleAccumSec[i] = accum
            }
        }
    }

    companion object {
        private val TIER_ALPHA_WEIGHTS = floatArrayOf(0.16f, 0.38f, 0.68f, 1.00f)
        private val TIER_STROKE_WEIGHTS = floatArrayOf(0.50f, 0.70f, 0.88f, 1.00f)
    }
}
