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

    @Test
    fun tuneBasemapStyleForWeatherLegibility_enforcesProgressiveZoomTiersCollisionPaddingAndDarkHalos() {
        val rawFiordSnippet = """
            {
              "version": 8,
              "layers": [
                {
                  "id": "water_name",
                  "type": "symbol",
                  "layout": {
                    "text-field": "{name:latin}\n{name:nonlatin}"
                  },
                  "paint": {
                    "text-color": "#516075",
                    "text-halo-width": 1.0
                  }
                },
                {
                  "id": "highway_name_other",
                  "type": "symbol",
                  "layout": {
                    "text-field": "{name:latin}"
                  },
                  "paint": {}
                },
                {
                  "id": "place_other",
                  "type": "symbol",
                  "layout": { "text-field": "{name:latin}\n{name:nonlatin}", "text-padding": 2 },
                  "paint": { "text-halo-width": 1.0 }
                },
                {
                  "id": "place_suburb",
                  "type": "symbol",
                  "layout": { "text-field": "{name:latin}\n{name:nonlatin}", "text-padding": 2 },
                  "paint": { "text-halo-width": 1.0 }
                },
                {
                  "id": "place_village",
                  "type": "symbol",
                  "layout": { "text-field": "{name:latin}\n{name:nonlatin}", "text-padding": 2 },
                  "paint": { "text-halo-width": 1.0 }
                },
                {
                  "id": "place_town",
                  "type": "symbol",
                  "layout": {
                    "icon-image": "dot_9",
                    "text-anchor": "left",
                    "text-offset": [0.5, 0.2],
                    "text-field": "{name:latin}\n{name:nonlatin}",
                    "text-padding": 2
                  },
                  "paint": { "text-halo-width": 1.0 }
                },
                {
                  "id": "place_city",
                  "type": "symbol",
                  "layout": {
                    "icon-image": "dot_9",
                    "text-anchor": "bottom",
                    "text-field": "{name:latin}\n{name:nonlatin}",
                    "text-padding": 2
                  },
                  "paint": { "text-halo-width": 1.0 }
                },
                {
                  "id": "place_city_large",
                  "type": "symbol",
                  "layout": {
                    "text-field": "{name:latin}\n{name:nonlatin}",
                    "text-padding": 2
                  },
                  "paint": { "text-halo-width": 1.0 }
                },
                {
                  "id": "place_state",
                  "type": "symbol",
                  "layout": {
                    "text-field": "{name:latin}\n{name:nonlatin}",
                    "text-padding": 2
                  },
                  "paint": { "text-halo-width": 1.0 }
                },
                {
                  "id": "place_country_minor",
                  "type": "symbol",
                  "layout": { "text-field": "{name:latin}\n{name:nonlatin}" },
                  "paint": {}
                },
                {
                  "id": "place_country_major",
                  "type": "symbol",
                  "layout": { "text-field": "{name:latin}\n{name:nonlatin}" },
                  "paint": {}
                },
                {
                  "id": "place_continent",
                  "type": "symbol",
                  "layout": { "text-field": "{name:latin}\n{name:nonlatin}" },
                  "paint": {}
                }
              ]
            }
        """.trimIndent()

        val tunedJson = WeatherTileSourceResolver.tuneBasemapStyleForWeatherLegibility(rawFiordSnippet)
        assertFalse("Multi-line \\n label concatenation must be removed", tunedJson.contains("\\n"))

        val root = com.google.gson.JsonParser.parseString(tunedJson).asJsonObject
        val layersById = root.getAsJsonArray("layers").associate {
            val obj = it.asJsonObject
            obj.get("id").asString to obj
        }

        // Verify progressive minzoom / maxzoom hierarchy prevents zoom-out label pileups
        assertEquals(1.0, layersById.getValue("place_continent").get("minzoom").asDouble, 0.01)
        assertEquals(3.5, layersById.getValue("place_continent").get("maxzoom").asDouble, 0.01)
        assertEquals(2.0, layersById.getValue("place_country_major").get("minzoom").asDouble, 0.01)
        assertEquals(6.5, layersById.getValue("place_country_major").get("maxzoom").asDouble, 0.01)
        assertEquals(3.5, layersById.getValue("place_country_minor").get("minzoom").asDouble, 0.01)
        assertEquals(7.5, layersById.getValue("place_country_minor").get("maxzoom").asDouble, 0.01)
        assertEquals(5.2, layersById.getValue("place_state").get("minzoom").asDouble, 0.01)
        assertEquals(8.5, layersById.getValue("place_state").get("maxzoom").asDouble, 0.01)
        assertEquals(3.2, layersById.getValue("place_city_large").get("minzoom").asDouble, 0.01)
        assertEquals(13.5, layersById.getValue("place_city_large").get("maxzoom").asDouble, 0.01)
        assertEquals(5.8, layersById.getValue("place_city").get("minzoom").asDouble, 0.01)
        assertEquals(14.0, layersById.getValue("place_city").get("maxzoom").asDouble, 0.01)
        assertEquals(7.5, layersById.getValue("place_town").get("minzoom").asDouble, 0.01)
        assertEquals(9.5, layersById.getValue("place_village").get("minzoom").asDouble, 0.01)
        assertEquals(10.5, layersById.getValue("place_suburb").get("minzoom").asDouble, 0.01)
        assertEquals(11.5, layersById.getValue("place_other").get("minzoom").asDouble, 0.01)
        assertEquals(9.0, layersById.getValue("water_name").get("minzoom").asDouble, 0.01)
        assertEquals(9.0, layersById.getValue("highway_name_other").get("minzoom").asDouble, 0.01)

        // Verify wide collision padding, centered anchors, removed dot icons, and strong dark halos
        val townLayout = layersById.getValue("place_town").getAsJsonObject("layout")
        assertNull("Off-center dot icon must be stripped from place_town", townLayout.get("icon-image"))
        assertEquals("center", townLayout.get("text-anchor").asString)
        assertEquals(10, townLayout.get("text-padding").asInt)
        assertFalse(townLayout.get("text-allow-overlap").asBoolean)
        assertFalse(townLayout.get("text-ignore-placement").asBoolean)

        val stateLayout = layersById.getValue("place_state").getAsJsonObject("layout")
        val statePaint = layersById.getValue("place_state").getAsJsonObject("paint")
        assertEquals(16, stateLayout.get("text-padding").asInt)
        assertEquals("uppercase", stateLayout.get("text-transform").asString)
        assertEquals("#CBD5E1", statePaint.get("text-color").asString)
        assertEquals("rgba(6, 13, 24, 0.95)", statePaint.get("text-halo-color").asString)
        assertEquals(2.0, statePaint.get("text-halo-width").asDouble, 0.01)

        val largeCityLayout = layersById.getValue("place_city_large").getAsJsonObject("layout")
        val largeCityPaint = layersById.getValue("place_city_large").getAsJsonObject("paint")
        assertEquals(14, largeCityLayout.get("text-padding").asInt)
        assertEquals("none", largeCityLayout.get("text-transform").asString)
        assertEquals("Noto Sans Bold", largeCityLayout.getAsJsonArray("text-font")[0].asString)
        assertEquals("#F8FAFC", largeCityPaint.get("text-color").asString)
        assertEquals("rgba(6, 13, 24, 0.95)", largeCityPaint.get("text-halo-color").asString)
        assertEquals(2.2, largeCityPaint.get("text-halo-width").asDouble, 0.01)
        assertEquals(0.4, largeCityPaint.get("text-halo-blur").asDouble, 0.01)
    }
}
