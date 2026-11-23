package com.google.weatherapp.data.map

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WeatherTileSourceResolverTest {

    private val fixedNow = Instant.parse("2026-11-23T08:15:00Z")

    @Test
    fun stormsInSouthAfricaLive_routesToEumetsatRainStormCellsAndLightning() {
        val plan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.92,
            centerLon = 18.42,
            hourOffset = 0,
            showLightning = true,
            showStormCells = true,
            nowUtc = fixedNow
        )

        assertEquals(3, plan.rasterLayers.size)
        assertTrue(plan.rasterLayers.any { it.urlTemplate.contains("layers=mtg_fd:h40b") })
        assertTrue(plan.rasterLayers.any { it.urlTemplate.contains("layers=msg_fes:rdt") })
        assertTrue(plan.rasterLayers.any { it.urlTemplate.contains("layers=mtg_fd:li_afa") })
        assertTrue(plan.sourceBadge.contains("Meteosat Live"))
        assertTrue(plan.sourceBadge.contains("Lightning"))
    }

    @Test
    fun stormsInSouthAfricaForecast_switchesToGlobalCompositeAndHonestForecastBadge() {
        val plan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = -33.92,
            centerLon = 18.42,
            hourOffset = 6,
            nowUtc = fixedNow
        )

        assertEquals(1, plan.rasterLayers.size)
        assertTrue(plan.rasterLayers.first().urlTemplate.contains("precipcomposite"))
        assertTrue(plan.rasterLayers.first().urlTemplate.contains("time=2026-11-23T14:00:00Z"))
        assertTrue(plan.sourceBadge.contains("Forecast · +6h"))
        assertTrue(plan.useSmoothOpenMeteoField)
    }

    @Test
    fun stormsInUnitedStatesLive_includesRadarCompositeAndUsLightning() {
        val plan = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.STORMS,
            centerLat = 39.73,
            centerLon = -104.99,
            hourOffset = 0,
            showLightning = true,
            nowUtc = fixedNow
        )

        assertEquals(2, plan.rasterLayers.size)
        assertTrue(plan.rasterLayers.any { it.urlTemplate.contains("precipcomposite") })
        assertTrue(plan.rasterLayers.any { it.urlTemplate.contains("/lightning/") })
        assertTrue(plan.sourceBadge.contains("US Lightning"))
    }

    @Test
    fun satelliteLayer_routesAfricaToMeteosatAmericasToGoesAndFutureToForecastClouds() {
        val zaGeo = WeatherTileSourceResolver.resolve(
            layer = WeatherMapLayer.SATELLITE,
            centerLat = -26.20,
            centerLon = 28.04,
            hourOffset = 0,
            satelliteMode = SatelliteMode.GEOCOLOUR,
            nowUtc = fixedNow
        )
        assertEquals(1, zaGeo.rasterLayers.size)
        assertTrue(zaGeo.rasterLayers.first().urlTemplate.contains("mtg_fd:rgb_geocolour"))
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
}
