package com.google.weatherapp.ui.map

import org.junit.Assert.*
import org.junit.Test

class WindParticleMathTest {

    @Test
    fun visualSpeedCurve_isSereneMonotonicAndBounded() {
        // Calm air and invalid numbers must not jitter
        assertEquals(0f, WindParticleMath.visualSpeedDpPerSec(0f), 0.001f)
        assertEquals(0f, WindParticleMath.visualSpeedDpPerSec(0.5f), 0.001f)
        assertEquals(0f, WindParticleMath.visualSpeedDpPerSec(Float.NaN), 0.001f)
        assertEquals(0f, WindParticleMath.visualSpeedDpPerSec(Float.POSITIVE_INFINITY), 0.001f)

        val speeds = listOf(5f, 15f, 30f, 50f, 80f, 120f)
        val visual = speeds.map { WindParticleMath.visualSpeedDpPerSec(it) }

        // Strictly monotonic across realistic meteorological regimes
        for (i in 0 until visual.lastIndex) {
            assertTrue(
                "Expected visual speed at ${speeds[i + 1]} km/h (${visual[i + 1]}) > at ${speeds[i]} km/h (${visual[i]})",
                visual[i + 1] > visual[i]
            )
        }

        // Moderate 30 km/h breeze must stay in calm Windy-like band (well below chaotic >100 dp/s)
        val breeze30 = WindParticleMath.visualSpeedDpPerSec(30f)
        assertTrue("30 km/h visual speed ($breeze30 dp/s) should be serene (18..36 dp/s)", breeze30 in 18f..36f)

        // Extreme hurricane speed remains bounded & legible
        assertTrue(WindParticleMath.visualSpeedDpPerSec(180f) <= WindParticleMath.MAX_VISUAL_SPEED_DP_PER_SEC)
    }

    @Test
    fun frameRateIndependence_producesIdenticalDistanceAt60Hz90HzAnd120Hz() {
        val density = 3.0f
        val windSpeedKmH = 35f

        fun simulateOneSecond(hz: Int): Float {
            val frameNanos = 1_000_000_000L / hz
            var totalPx = 0f
            repeat(hz) {
                val dt = WindParticleMath.clampDeltaSeconds(frameNanos)
                totalPx += WindParticleMath.stepDistancePx(windSpeedKmH, dt, density)
            }
            return totalPx
        }

        val dist60 = simulateOneSecond(60)
        val dist90 = simulateOneSecond(90)
        val dist120 = simulateOneSecond(120)

        assertEquals(dist60, dist90, 0.1f)
        assertEquals(dist60, dist120, 0.1f)
    }

    @Test
    fun deltaTimeClamp_guardsNegativeZeroAndLongPauseJumps() {
        assertEquals(0f, WindParticleMath.clampDeltaSeconds(0L), 0.0001f)
        assertEquals(0f, WindParticleMath.clampDeltaSeconds(-16_000_000L), 0.0001f)
        assertEquals(
            WindParticleMath.MAX_FRAME_DT_SECONDS,
            WindParticleMath.clampDeltaSeconds(5_000_000_000L),
            0.0001f
        )
    }

    @Test
    fun lifeEnvelope_fadesSmoothlyAtBoundariesAndPeaksInMiddle() {
        assertEquals(0f, WindParticleMath.lifeEnvelope(0f), 0.001f)
        assertEquals(1f, WindParticleMath.lifeEnvelope(0.5f), 0.001f)
        assertEquals(0f, WindParticleMath.lifeEnvelope(1f), 0.001f)
        assertTrue(WindParticleMath.lifeEnvelope(0.09f) in 0.2f..0.8f)
        assertTrue(WindParticleMath.lifeEnvelope(0.88f) in 0.2f..0.8f)
    }

    @Test
    fun unwrapLongitudes_keepsAntimeridianInterpolationContinuousAcrossPacific() {
        val warpN = 3
        val lons = doubleArrayOf(
            176.0, 180.0, -176.0,
            176.0, 180.0, -176.0,
            176.0, 180.0, -176.0
        )
        WindParticleMath.unwrapLongitudes(lons, warpN)

        // Right column must unwrap to +184.0 instead of staying at -176.0
        assertEquals(176.0, lons[0], 1e-6)
        assertEquals(180.0, lons[1], 1e-6)
        assertEquals(184.0, lons[2], 1e-6)

        // Bilinear midpoint between 180° and -176° must wrap to -178° (Pacific), never +2° (Prime Meridian)
        val midpoint = (lons[1] + lons[2]) * 0.5
        assertEquals(-178.0, WindParticleMath.wrapLongitude(midpoint), 1e-6)
        assertEquals(180.0, WindParticleMath.wrapLongitude(180.0), 1e-6)
        assertEquals(-180.0, WindParticleMath.wrapLongitude(-180.0), 1e-6)
    }
}
