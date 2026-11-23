package com.google.weatherapp.data.map

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WeatherTileSourceResolverTest {

    private val fixedNow = Instant.parse("2026-11-23T08:15:00Z")

    @Test
    fun stormsInSouthAfricaLive_routesToSmooth512pxEumetsatH60bAndMtgLightningWithoutBlockyRdt() {
        val plan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.92,
            centerLon = 18.42,
            hourOffset = 0,
            showLightning = true,
            showStormEnergy = false,
            nowUtc = fixedNow
        )

        assertEquals(2, plan.rasterLayers.size)
        val rainLayer = plan.rasterLayers.first { it.urlTemplate.contains("layers=msg_fes:h60b") }
        val lightningLayer = plan.rasterLayers.first { it.urlTemplate.contains("layers=mtg_fd:li_afa") }

        assertEquals(512, rainLayer.tileSize)
        assertEquals(5f, rainLayer.maxZoom, 0.01f)
        assertTrue(rainLayer.urlTemplate.contains("width=512&height=512"))

        assertEquals(512, lightningLayer.tileSize)
        assertEquals(5f, lightningLayer.maxZoom, 0.01f)
        assertTrue(lightningLayer.urlTemplate.contains("width=512&height=512"))

        // Slow/blocky msg_fes:rdt polygons and Visual Crossing precipcomposite must NOT be present
        assertTrue(plan.rasterLayers.none { it.urlTemplate.contains("msg_fes:rdt") })
        assertTrue(plan.rasterLayers.none { it.urlTemplate.contains("precipcomposite") })

        // Clean live satellite mode keeps CAPE off until toggled on
        assertFalse(plan.useSmoothOpenMeteoField)
        assertNotNull("Live EUMETSAT H60B must provide matching H60B legend stops", plan.legendStops)
        assertTrue(plan.legendStops!!.any { it.label == "Bolt" })
        assertTrue(plan.sourceBadge.contains("Meteosat Live"))
        assertTrue(plan.sourceBadge.contains("Lightning"))

        // Toggling Storm Energy ON enables smooth CAPE glow beneath satellite tiles
        val withEnergy = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.92,
            centerLon = 18.42,
            hourOffset = 0,
            showLightning = false,
            showStormEnergy = true,
            nowUtc = fixedNow
        )
        assertEquals(1, withEnergy.rasterLayers.size)
        assertTrue(withEnergy.useSmoothOpenMeteoField)
        assertEquals(StormFieldMode.CAPE_ONLY, withEnergy.stormFieldMode)
        assertFalse(withEnergy.legendStops!!.any { it.label == "Bolt" })
    }

    @Test
    fun stormsForecastWorldwide_usesSmoothOpenMeteoPrecipitationWithZeroBlockyTiles() {
        val plan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.92,
            centerLon = 18.42,
            hourOffset = 6,
            nowUtc = fixedNow
        )

        assertTrue("Forecast storms must use zero blocky external raster tiles", plan.rasterLayers.isEmpty())
        assertTrue(plan.useSmoothOpenMeteoField)
        assertEquals(StormFieldMode.PRECIPITATION_AND_CAPE, plan.stormFieldMode)
        assertNotNull("Forecast storms must provide explicit model legend without Bolt", plan.legendStops)
        assertTrue("Forecast storms have no lightning and must not show Bolt in legend", plan.legendStops!!.none { it.label == "Bolt" })
        assertTrue(plan.sourceBadge.contains("Forecast · +6h"))
        assertTrue(plan.attributionLabel.contains("Open-Meteo"))
    }

    @Test
    fun stormsInUnitedStatesLive_usesSmoothRainViewerRadarAndUsLightning() {
        val sampleFrames = listOf(
            RainViewerRadarFrame(fixedNow.epochSecond - 3600L, "/v2/radar/hour_ago"),
            RainViewerRadarFrame(fixedNow.epochSecond - 300L, "/v2/radar/latest_frame")
        )
        val plan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = 39.73,
            centerLon = -104.99,
            hourOffset = 0,
            showLightning = true,
            rainViewerFrames = sampleFrames,
            nowUtc = fixedNow
        )

        assertEquals(2, plan.rasterLayers.size)
        val radar = plan.rasterLayers.first { it.urlTemplate.contains("tilecache.rainviewer.com") }
        assertEquals(512, radar.tileSize)
        assertEquals(7f, radar.maxZoom, 0.01f)
        assertTrue(radar.urlTemplate.contains("/v2/radar/latest_frame/512/{z}/{x}/{y}/2/1_1.png"))
        assertTrue(plan.rasterLayers.any { it.urlTemplate.contains("/lightning/") })
        assertTrue(plan.rasterLayers.none { it.urlTemplate.contains("precipcomposite") })
        assertTrue(plan.sourceBadge.contains("US Lightning"))

        // If RainViewer frames haven't loaded yet, gracefully render smooth Open-Meteo precipitation
        val fallbackPlan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = 39.73,
            centerLon = -104.99,
            hourOffset = 0,
            showLightning = true,
            rainViewerFrames = emptyList(),
            nowUtc = fixedNow
        )
        assertTrue(fallbackPlan.useSmoothOpenMeteoField)
        assertEquals(StormFieldMode.PRECIPITATION_AND_CAPE, fallbackPlan.stormFieldMode)
    }

    @Test
    fun parseRainViewerFrames_parsesRealPayloadAndSelectsClosestPastOffset() {
        val json = """
            {
              "version": "2.0",
              "host": "https://tilecache.rainviewer.com",
              "radar": {
                "past": [
                  {"time": 1795428600, "path": "/v2/radar/oldest"},
                  {"time": 1795432200, "path": "/v2/radar/mid"},
                  {"time": 1795435800, "path": "/v2/radar/latest"}
                ]
              }
            }
        """.trimIndent()
        val frames = WeatherTileSourceResolver.parseRainViewerFrames(json)
        assertEquals(3, frames.size)
        assertEquals("/v2/radar/latest", frames.last().path)

        val now = Instant.ofEpochSecond(1795436000L)
        assertEquals("/v2/radar/latest", WeatherTileSourceResolver.selectRainViewerFrame(frames, 0, now)?.path)
        assertEquals("/v2/radar/mid", WeatherTileSourceResolver.selectRainViewerFrame(frames, -1, now)?.path)
        assertEquals("/v2/radar/oldest", WeatherTileSourceResolver.selectRainViewerFrame(frames, -2, now)?.path)
        assertNull(WeatherTileSourceResolver.selectRainViewerFrame(frames, 1, now))
        assertTrue(WeatherTileSourceResolver.parseRainViewerFrames("not json").isEmpty())
    }

    @Test
    fun satelliteLayer_routesAfricaTo512pxMeteosatAmericasToGoesAndFutureToForecastClouds() {
        val zaGeo = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.SATELLITE,
            centerLat = -26.20,
            centerLon = 28.04,
            hourOffset = 0,
            satelliteMode = SatelliteMode.GEOCOLOUR,
            nowUtc = fixedNow
        )
        assertEquals(1, zaGeo.rasterLayers.size)
        assertEquals(512, zaGeo.rasterLayers.first().tileSize)
        assertEquals(6f, zaGeo.rasterLayers.first().maxZoom, 0.01f)
        assertTrue(zaGeo.rasterLayers.first().urlTemplate.contains("mtg_fd:rgb_geocolour"))
        assertTrue(zaGeo.rasterLayers.first().urlTemplate.contains("width=512&height=512"))
        assertTrue(zaGeo.rasterLayers.first().urlTemplate.contains("format=image/jpeg"))
        assertFalse(zaGeo.useSmoothOpenMeteoField)

        val usSat = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.SATELLITE,
            centerLat = 40.71,
            centerLon = -74.00,
            hourOffset = 0,
            nowUtc = fixedNow
        )
        assertEquals(1, usSat.rasterLayers.size)
        assertTrue(usSat.rasterLayers.first().urlTemplate.contains("goes-east-ir"))

        val futureClouds = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.SATELLITE,
            centerLat = -26.20,
            centerLon = 28.04,
            hourOffset = 12,
            nowUtc = fixedNow
        )
        assertTrue(futureClouds.rasterLayers.isEmpty())
        assertTrue(futureClouds.useSmoothOpenMeteoField)
        assertTrue(futureClouds.sourceBadge.contains("Forecast Clouds · +12h"))
    }

    @Test
    fun fallbackDarkVectorStyleJson_isValidMapLibreStyleWithLabelAnchor() {
        val root = com.google.gson.JsonParser.parseString(
            WeatherTileSourceResolver.FALLBACK_DARK_VECTOR_STYLE_JSON
        ).asJsonObject
        assertEquals(8, root.get("version").asInt)
        val layers = root.getAsJsonArray("layers")
        assertTrue(layers.size() >= 6)
        val layerIds = (0 until layers.size()).map { layers[it].asJsonObject.get("id").asString }
        assertTrue(WeatherTileSourceResolver.FIORD_LABEL_ANCHOR_LAYER_ID in layerIds)
        assertTrue("place_city" in layerIds)
        assertTrue(
            "Label anchor must come before city labels",
            layerIds.indexOf(WeatherTileSourceResolver.FIORD_LABEL_ANCHOR_LAYER_ID) < layerIds.indexOf("place_city")
        )
    }

    @Test
    fun stormsLegendsAndPastIds_stayHonestAndStableAcrossRegionsAndClockTicks() {
        val frames = listOf(
            RainViewerRadarFrame(1795428600L, "/v2/radar/oldest"),
            RainViewerRadarFrame(1795432200L, "/v2/radar/mid"),
            RainViewerRadarFrame(1795435800L, "/v2/radar/latest")
        )

        // Australia live radar has no US/EUMETSAT lightning -> legend must NOT show Bolt
        val sydneyPlan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.86,
            centerLon = 151.20,
            hourOffset = 0,
            showLightning = true,
            rainViewerFrames = frames,
            nowUtc = fixedNow
        )
        assertNotNull(sydneyPlan.legendStops)
        assertTrue(sydneyPlan.legendStops!!.none { it.label == "Bolt" })

        // US -1h radar has no historical US lightning -> legend must NOT show Bolt
        val usPastPlan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = 39.73,
            centerLon = -104.99,
            hourOffset = -1,
            showLightning = true,
            rainViewerFrames = frames,
            nowUtc = fixedNow
        )
        assertTrue(usPastPlan.legendStops!!.none { it.label == "Bolt" })

        // RainViewer -1h selection is anchored to latest fetched frame and never drifts as wall clock advances
        val frameAtT0 = WeatherTileSourceResolver.selectRainViewerFrame(frames, -1, Instant.ofEpochSecond(1795436000L))
        val frameAtT30m = WeatherTileSourceResolver.selectRainViewerFrame(frames, -1, Instant.ofEpochSecond(1795437800L))
        assertEquals(frameAtT0, frameAtT30m)
        assertEquals("/v2/radar/mid", frameAtT0?.path)

        // EUMETSAT past layer IDs include UTC timestamp so hour rollovers refresh MapLibre sources cleanly
        val past08 = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.92,
            centerLon = 18.42,
            hourOffset = -1,
            nowUtc = Instant.parse("2026-11-23T08:15:00Z")
        )
        val past09 = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.92,
            centerLon = 18.42,
            hourOffset = -1,
            nowUtc = Instant.parse("2026-11-23T09:15:00Z")
        )
        assertNotEquals(past08.rasterLayers.first().id, past09.rasterLayers.first().id)

        // Past offsets falling back to Open-Meteo must not display misleading "Live" badges
        val sydneyPastFallback = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.86,
            centerLon = 151.20,
            hourOffset = -2,
            rainViewerFrames = emptyList(),
            nowUtc = fixedNow
        )
        assertFalse(sydneyPastFallback.sourceBadge.contains("Live"))
        assertTrue(sydneyPastFallback.sourceBadge.contains("-2h"))

        val tokyoPastClouds = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.SATELLITE,
            centerLat = 35.68,
            centerLon = 139.69,
            hourOffset = -1,
            nowUtc = fixedNow
        )
        assertTrue(tokyoPastClouds.useSmoothOpenMeteoField)
        assertTrue(tokyoPastClouds.sourceBadge.contains("-1h"))
    }
}
