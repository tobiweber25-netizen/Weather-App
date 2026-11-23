package com.google.weatherapp.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.weatherapp.data.map.*
import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.data.model.SevereAlertInfo
import com.google.weatherapp.data.model.SeverityRiskLevel
import com.google.weatherapp.data.repository.WeatherMapper
import com.google.weatherapp.theme.*
import com.google.weatherapp.ui.components.luminousGlass
import org.maplibre.android.geometry.LatLng
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen Windy-inspired Weather Map styled natively in Almanac's Luminous Glass theme.
 * Opened from the top-right Options dropdown or contextual expanded-card shortcuts.
 * Displays 100% real data (OpenFreeMap Fiord + EUMETSAT MTG space satellite + Open-Meteo + Visual Crossing).
 */
@Composable
fun WeatherMapScreen(
    initialLatitude: Double,
    initialLongitude: Double,
    initialPlaceName: String,
    initialTimezone: String?,
    initialLayer: WeatherMapLayer = WeatherMapLayer.WIND,
    gpsLocation: SavedLocation?,
    savedLocations: List<SavedLocation>,
    activeAlert: SevereAlertInfo?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val coroutineScope = rememberCoroutineScope()
    val initialZone = remember(initialTimezone) {
        WeatherMapper.parseZoneIdOrNull(initialTimezone) ?: ZoneId.systemDefault()
    }
    val controller = remember(initialLatitude, initialLongitude, initialLayer) {
        WeatherMapStateHolder(
            scope = coroutineScope,
            initialLayer = initialLayer,
            initialLatitude = initialLatitude,
            initialLongitude = initialLongitude,
            initialPlaceName = initialPlaceName,
            initialZoneId = initialZone
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    val uiState by controller.uiState.collectAsStateWithLifecycle()
    var cameraCommand by remember { mutableStateOf<MapCameraCommand?>(null) }
    var showCityJumpMenu by remember { mutableStateOf(false) }
    var projector by remember { mutableStateOf<((LatLng) -> ProjectedScreenPoint?)?>(null) }
    var focusedAnchorLat by remember(initialLatitude) { mutableDoubleStateOf(initialLatitude) }
    var focusedAnchorLon by remember(initialLongitude) { mutableDoubleStateOf(initialLongitude) }

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MapCanvasFallbackBg)
    ) {
        // 1. Full-bleed native GPU Fiord vector map + weather overlays + 60fps wind particles
        MapLibreWeatherCanvas(
            uiState = uiState,
            cameraCommand = cameraCommand,
            onViewportIdle = { bounds -> controller.onViewportIdle(bounds) },
            onMapTapped = { lat, lon -> controller.onMapTapped(lat, lon) },
            onProjectionChanged = { proj -> projector = proj },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Projected Luminous Glass overlays (active city pin, Pressure H/L centers, tapped reticle)
        val currentProjector = projector
        if (currentProjector != null) {
            // Focus city subtle anchor badge
            val focusPt = currentProjector(LatLng(focusedAnchorLat, focusedAnchorLon))
            if (focusPt != null && focusPt.visible) {
                MapCityDotMarker(
                    xPx = focusPt.xPx,
                    yPx = focusPt.yPx
                )
            }

            // Real High / Low sea-level pressure centers on Pressure layer
            if (uiState.selectedLayer == WeatherMapLayer.PRESSURE) {
                uiState.pressureCenters.forEach { center ->
                    val pt = currentProjector(LatLng(center.latitude, center.longitude))
                    if (pt != null && pt.visible) {
                        PressureCenterBadge(
                            center = center,
                            xPx = pt.xPx,
                            yPx = pt.yPx
                        )
                    }
                }
            }

            // Glowing tap-anywhere target reticle
            val tapLat = uiState.tappedLat
            val tapLon = uiState.tappedLon
            if (tapLat != null && tapLon != null) {
                val tapPt = currentProjector(LatLng(tapLat, tapLon))
                if (tapPt != null && tapPt.visible) {
                    val radiusPx = with(density) { MapTokens.ReticleOuterRadius.toPx() }
                    Canvas(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (tapPt.xPx - radiusPx).roundToInt(),
                                    (tapPt.yPx - radiusPx).roundToInt()
                                )
                            }
                            .size(MapTokens.ReticleOuterRadius * 2)
                    ) {
                        val center = Offset(size.width * 0.5f, size.height * 0.5f)
                        drawCircle(color = MapHudReticleGlow, radius = size.minDimension * 0.48f, center = center)
                        drawCircle(
                            color = MapHudReticleCore,
                            radius = size.minDimension * 0.34f,
                            center = center,
                            style = Stroke(width = 2.5f)
                        )
                        drawCircle(
                            color = SunCoreGold,
                            radius = MapTokens.ReticleInnerRadius.toPx(),
                            center = center
                        )
                    }
                }
            }
        }

        // 3. Top Floating Luminous Glass Header & Sub-Mode Chips
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.Small),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                // Back button
                MapCircularGlassButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to weather dashboard",
                    onClick = onBack
                )

                // City & Honest Source Status Pill (tap to quick-jump saved cities)
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .luminousGlass(
                                cornerRadius = CornerTokens.Pill,
                                overrideTopColor = MapHudSurfaceTop,
                                overrideBottomColor = MapHudSurfaceBottom
                            )
                            .clickable { showCityJumpMenu = true }
                            .padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.Small)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
                            ) {
                                Text(
                                    text = uiState.focusedPlaceName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SkyTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Jump to city",
                                    tint = SkyTextSecondary,
                                    modifier = Modifier.size(IconSizeTokens.Micro)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(IconSizeTokens.StatusDot)
                                        .clip(CircleShape)
                                        .background(
                                            if (uiState.hourOffset <= 0) MapHudLivePulseGreen else SunCoreGold
                                        )
                                )
                                Text(
                                    text = uiState.tilePlan.sourceBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SkyTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (uiState.gridStatus is WeatherMapGridStatus.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(IconSizeTokens.Small),
                                strokeWidth = BorderTokens.Thick,
                                color = PrimaryDark
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showCityJumpMenu,
                        onDismissRequest = { showCityJumpMenu = false },
                        shape = RoundedCornerShape(CornerTokens.Large),
                        containerColor = GlassMenuContainer,
                        border = BorderStroke(BorderTokens.Hairline, GlassMenuBorder)
                    ) {
                        val jumpTargets = buildList {
                            gpsLocation?.let { add(it) }
                            savedLocations.forEach { loc ->
                                if (gpsLocation == null ||
                                    kotlin.math.abs(loc.latitude - gpsLocation.latitude) > 0.02 ||
                                    kotlin.math.abs(loc.longitude - gpsLocation.longitude) > 0.02
                                ) {
                                    add(loc)
                                }
                            }
                        }
                        if (jumpTargets.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(initialPlaceName, color = SkyTextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.MyLocation, null, tint = SunCoreGold)
                                },
                                onClick = {
                                    showCityJumpMenu = false
                                    cameraCommand = MapCameraCommand(
                                        latitude = initialLatitude,
                                        longitude = initialLongitude,
                                        zoom = 6.2,
                                        token = System.nanoTime()
                                    )
                                }
                            )
                        } else {
                            jumpTargets.forEach { loc ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = loc.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = SkyTextPrimary
                                            )
                                            if (loc.displaySubtitle.isNotBlank()) {
                                                Text(
                                                    text = loc.displaySubtitle,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SkyTextMuted
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (loc.isCurrentGps) Icons.Default.MyLocation else Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = if (loc.isCurrentGps) SunCoreGold else PrimaryDark
                                        )
                                    },
                                    onClick = {
                                        showCityJumpMenu = false
                                        val z = WeatherMapper.parseZoneIdOrNull(loc.timezone)
                                        focusedAnchorLat = loc.latitude
                                        focusedAnchorLon = loc.longitude
                                        controller.updateFocusLocation(loc.name, loc.latitude, loc.longitude, z)
                                        cameraCommand = MapCameraCommand(
                                            latitude = loc.latitude,
                                            longitude = loc.longitude,
                                            zoom = 6.2,
                                            token = System.nanoTime()
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Flowing Wind Streamlines Toggle (works over ANY layer, Windy-style!)
                MapCircularGlassButton(
                    icon = Icons.Default.Air,
                    contentDescription = if (uiState.showWindStreamlines) "Hide flowing wind lines" else "Show flowing wind lines",
                    isActive = uiState.showWindStreamlines,
                    onClick = { controller.toggleWindStreamlines() }
                )

                // Re-center on starting location
                MapCircularGlassButton(
                    icon = Icons.Default.MyLocation,
                    contentDescription = "Center map on active city",
                    onClick = {
                        focusedAnchorLat = initialLatitude
                        focusedAnchorLon = initialLongitude
                        controller.updateFocusLocation(initialPlaceName, initialLatitude, initialLongitude, initialZone)
                        cameraCommand = MapCameraCommand(
                            latitude = initialLatitude,
                            longitude = initialLongitude,
                            zoom = 6.2,
                            token = System.nanoTime()
                        )
                    }
                )
            }

            // Contextual Sub-Mode Chips + Active Alert / Error Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (uiState.selectedLayer) {
                    WeatherMapLayer.WIND -> {
                        WindMetricMode.entries.forEach { mode ->
                            MapSubModeChip(
                                label = mode.label,
                                selected = uiState.windMetricMode == mode,
                                onClick = { controller.setWindMetricMode(mode) }
                            )
                        }
                    }

                    WeatherMapLayer.STORMS -> {
                        if (uiState.hourOffset <= 0 && (uiState.inEumetsatCoverage || uiState.inUsCoverage)) {
                            MapSubModeChip(
                                label = "Lightning",
                                icon = Icons.Default.ElectricBolt,
                                selected = uiState.showLightning,
                                activeTint = MapHudLightningAccent,
                                onClick = { controller.toggleLightning() }
                            )
                        }
                        if (uiState.hourOffset <= 0 && uiState.inEumetsatCoverage) {
                            MapSubModeChip(
                                label = "Storm Cells",
                                icon = Icons.Default.Thunderstorm,
                                selected = uiState.showStormCells,
                                activeTint = MapHudStormAccent,
                                onClick = { controller.toggleStormCells() }
                            )
                        }
                        if (activeAlert != null && activeAlert.riskLevel.ordinal >= SeverityRiskLevel.HIGH.ordinal) {
                            MapAlertBadgeChip(alert = activeAlert)
                        }
                    }

                    WeatherMapLayer.SATELLITE -> {
                        if (uiState.hourOffset <= 0 && uiState.inEumetsatCoverage) {
                            SatelliteMode.entries.forEach { mode ->
                                MapSubModeChip(
                                    label = mode.label,
                                    selected = uiState.satelliteMode == mode,
                                    onClick = { controller.setSatelliteMode(mode) }
                                )
                            }
                        }
                    }

                    WeatherMapLayer.TEMPERATURE, WeatherMapLayer.PRESSURE -> Unit
                }
            }

            // Honest Error / Rate-Limit Banner (never hides failures behind fake numbers)
            val errStatus = uiState.gridStatus as? WeatherMapGridStatus.Error
            AnimatedVisibility(visible = errStatus != null, enter = fadeIn(), exit = fadeOut()) {
                if (errStatus != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .luminousGlass(
                                cornerRadius = CornerTokens.Medium,
                                accentBorderColor = AlertWarningBorder,
                                overrideTopColor = AlertWarningContainer,
                                overrideBottomColor = MapHudSurfaceBottom
                            )
                            .padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.Small)
                    ) {
                        Text(
                            text = errStatus.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = SkyTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(SpacingTokens.Small))
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = SunCoreGold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(CornerTokens.Pill))
                                .clickable { controller.retryFetch() }
                                .padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.ExtraSmall)
                        )
                    }
                }
            }
        }

        // 4. Right-Edge Vertical Frosted Layer Rail (Windy Ergonomics)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = SpacingTokens.Small)
                .width(MapTokens.LayerRailWidth)
                .luminousGlass(
                    cornerRadius = CornerTokens.Large,
                    overrideTopColor = MapHudSurfaceTop,
                    overrideBottomColor = MapHudSurfaceBottom
                )
                .padding(vertical = SpacingTokens.ExtraSmall, horizontal = SpacingTokens.ExtraSmall),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            WeatherMapLayer.entries.forEach { layer ->
                val isSelected = uiState.selectedLayer == layer
                MapLayerRailItem(
                    layer = layer,
                    selected = isSelected,
                    onClick = { controller.selectLayer(layer) }
                )
            }
        }

        // 5. Bottom Stack: Tap-Anywhere Readout Card + Legend & 48h Playable Timeline Dock
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.Small),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = uiState.tappedReading != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut()
            ) {
                uiState.tappedReading?.let { reading ->
                    MapPointInspectorCard(
                        reading = reading,
                        onDismiss = { controller.dismissTappedReading() },
                        modifier = Modifier.widthIn(max = MapTokens.InspectorMaxWidth)
                    )
                }
            }

            MapBottomTimelineDock(
                uiState = uiState,
                onTogglePlay = { controller.togglePlayback() },
                onScrubHour = { hour -> controller.setHourOffset(hour, fromUserScrub = true) },
                onJumpNow = { controller.setHourOffset(0, fromUserScrub = true) }
            )
        }
    }
}

@Composable
private fun MapCircularGlassButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MapTokens.TopControlButtonSize)
            .luminousGlass(
                cornerRadius = CornerTokens.Pill,
                accentBorderColor = if (isActive) GlassActiveBorderGold else null,
                overrideTopColor = if (isActive) MapHudElevatedTop else MapHudSurfaceTop,
                overrideBottomColor = if (isActive) MapHudElevatedBottom else MapHudSurfaceBottom
            )
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) SunCoreGold else SkyTextPrimary,
            modifier = Modifier.size(IconSizeTokens.Small)
        )
    }
}

@Composable
private fun MapSubModeChip(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    activeTint: Color = SunCoreGold,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
        modifier = Modifier
            .height(MapTokens.SubModeChipHeight)
            .luminousGlass(
                cornerRadius = CornerTokens.Pill,
                accentBorderColor = if (selected) activeTint.copy(alpha = 0.85f) else null,
                overrideTopColor = if (selected) MapHudElevatedTop else MapHudSurfaceTop,
                overrideBottomColor = if (selected) MapHudElevatedBottom else MapHudSurfaceBottom
            )
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.Medium)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) activeTint else SkyTextMuted,
                modifier = Modifier.size(IconSizeTokens.Micro)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) SkyTextPrimary else SkyTextMuted
        )
    }
}

@Composable
private fun MapAlertBadgeChip(alert: SevereAlertInfo) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
        modifier = Modifier
            .height(MapTokens.SubModeChipHeight)
            .luminousGlass(
                cornerRadius = CornerTokens.Pill,
                accentBorderColor = AlertSevereBorder,
                overrideTopColor = AlertSevereContainer,
                overrideBottomColor = MapHudSurfaceBottom
            )
            .padding(horizontal = SpacingTokens.Medium)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = SunCoreGold,
            modifier = Modifier.size(IconSizeTokens.Micro)
        )
        Text(
            text = alert.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = AlertSevereOnContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MapLayerRailItem(
    layer: WeatherMapLayer,
    selected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (layer) {
        WeatherMapLayer.WIND -> Icons.Default.Air
        WeatherMapLayer.STORMS -> Icons.Default.Thunderstorm
        WeatherMapLayer.SATELLITE -> Icons.Default.SatelliteAlt
        WeatherMapLayer.TEMPERATURE -> Icons.Default.Thermostat
        WeatherMapLayer.PRESSURE -> Icons.Default.Compress
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(MapTokens.LayerRailItemHeight)
            .clip(RoundedCornerShape(CornerTokens.Medium))
            .background(if (selected) MapHudActiveChipFill else Transparent)
            .then(
                if (selected) {
                    Modifier.border(
                        width = BorderTokens.Hairline,
                        color = GlassActiveBorderGold,
                        shape = RoundedCornerShape(CornerTokens.Medium)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = SpacingTokens.ExtraSmall)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = layer.fullTitle,
            tint = if (selected) SunCoreGold else SkyTextSecondary,
            modifier = Modifier.size(MapTokens.LayerRailIconSize)
        )
        Spacer(modifier = Modifier.height(SpacingTokens.Micro))
        Text(
            text = layer.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) SkyTextPrimary else SkyTextMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun MapPointInspectorCard(
    reading: MapPointReading,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coordLabel = String.format(
        Locale.US,
        "%.2f°%s, %.2f°%s",
        kotlin.math.abs(reading.latitude),
        if (reading.latitude >= 0) "N" else "S",
        kotlin.math.abs(reading.longitude),
        if (reading.longitude >= 0) "E" else "W"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .luminousGlass(
                cornerRadius = CornerTokens.Large,
                overrideTopColor = MapHudElevatedTop,
                overrideBottomColor = MapHudElevatedBottom
            )
            .padding(horizontal = SpacingTokens.Base, vertical = SpacingTokens.Medium),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = reading.placeLabel ?: coordLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyTextPrimary
                )
                Text(
                    text = "${reading.timeLabel} · Model Grid",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextMuted
                )
            }
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close location readout",
                tint = SkyTextSecondary,
                modifier = Modifier
                    .size(IconSizeTokens.Medium)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(SpacingTokens.ExtraSmall)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            InspectorMetricCell(
                label = "WIND",
                value = "${reading.windSpeedKmH} km/h ${reading.windCompassFrom}",
                sub = "Gusts ${reading.windGustKmH} km/h"
            )
            InspectorMetricCell(
                label = "TEMP",
                value = String.format(Locale.US, "%.1f°C", reading.temperatureC),
                sub = "Clouds ${reading.cloudCoverPct}%"
            )
            InspectorMetricCell(
                label = "PRESSURE",
                value = "${reading.pressureHpa} hPa",
                sub = if (reading.precipitationMmH >= 0.1) {
                    String.format(Locale.US, "Rain %.1f mm/h", reading.precipitationMmH)
                } else {
                    "Dry"
                }
            )
        }

        if (reading.capeJkg >= 150 || reading.isThunderstormCode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Icon(
                    imageVector = Icons.Default.ElectricBolt,
                    contentDescription = null,
                    tint = MapHudLightningAccent,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = "Storm Energy: ${reading.capeJkg} J/kg · ${reading.capeTier.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextSecondary
                )
            }
        }
    }
}

@Composable
private fun InspectorMetricCell(
    label: String,
    value: String,
    sub: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SkyTextMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = SkyTextPrimary
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = SkyTextSecondary
        )
    }
}

@Composable
private fun MapBottomTimelineDock(
    uiState: WeatherMapUiState,
    onTogglePlay: () -> Unit,
    onScrubHour: (Int) -> Unit,
    onJumpNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .luminousGlass(
                cornerRadius = CornerTokens.Large,
                overrideTopColor = MapHudSurfaceTop,
                overrideBottomColor = MapHudSurfaceBottom
            )
            .padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.Compact),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        // 1. Active Layer Color Legend Bar
        val stops = uiState.selectedLayer.legendStops
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MapTokens.LegendBarHeight)
                    .clip(RoundedCornerShape(CornerTokens.Pill))
                    .background(
                        brush = Brush.horizontalGradient(colors = stops.map { it.color })
                    )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                stops.forEach { stop ->
                    Text(
                        text = stop.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyTextMuted
                    )
                }
                Text(
                    text = uiState.selectedLayer.unitLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyTextSecondary
                )
            }
        }

        // 2. Play/Pause + Timeline Scrubber + Live Jump
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MapTokens.TimelinePlayButtonSize)
                    .clip(CircleShape)
                    .background(if (uiState.isPlaying) MapHudActiveChipFill else GlassPillHighlight)
                    .border(
                        width = BorderTokens.Hairline,
                        color = if (uiState.isPlaying) GlassActiveBorderGold else MapHudBorderSubtle,
                        shape = CircleShape
                    )
                    .clickable(onClick = onTogglePlay)
            ) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "Pause forecast animation" else "Play forecast animation",
                    tint = if (uiState.isPlaying) SunCoreGold else SkyTextPrimary,
                    modifier = Modifier.size(IconSizeTokens.Small)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SkyTextPrimary
                    )
                    if (uiState.hourOffset != 0) {
                        Text(
                            text = "Now",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SunCoreGold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(CornerTokens.Pill))
                                .background(MapHudActiveChipFill)
                                .clickable(onClick = onJumpNow)
                                .padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.Micro)
                        )
                    } else {
                        Text(
                            text = uiState.tilePlan.attributionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = SkyTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Slider(
                    value = uiState.hourOffset.toFloat(),
                    onValueChange = { onScrubHour(it.roundToInt()) },
                    valueRange = uiState.minHourOffset.toFloat()..uiState.maxHourOffset.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = SunCoreGold,
                        activeTrackColor = PrimaryDark,
                        inactiveTrackColor = GlassSegmentedTrackBg
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SpacingTokens.ExtraLarge)
                )
            }
        }
    }
}

@Composable
private fun MapCityDotMarker(xPx: Float, yPx: Float) {
    val dotSize = SpacingTokens.Medium
    val halfPx = with(LocalDensity.current) { (dotSize / 2).toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((xPx - halfPx).roundToInt(), (yPx - halfPx).roundToInt()) }
            .size(dotSize)
            .clip(CircleShape)
            .background(SunCoreGold)
            .border(BorderTokens.Thick, MapCanvasFallbackBg, CircleShape)
    )
}

@Composable
private fun PressureCenterBadge(
    center: PressureCenterMarker,
    xPx: Float,
    yPx: Float
) {
    val halfPx = with(LocalDensity.current) { SpacingTokens.Large.toPx() }
    val badgeColor = if (center.isHigh) MapPressureHighBadge else MapPressureLowBadge
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset { IntOffset((xPx - halfPx).roundToInt(), (yPx - halfPx).roundToInt()) }
            .luminousGlass(
                cornerRadius = CornerTokens.Small,
                accentBorderColor = badgeColor.copy(alpha = 0.7f),
                overrideTopColor = MapPinSavedCityBg,
                overrideBottomColor = MapHudSurfaceBottom
            )
            .padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.Micro)
    ) {
        Text(
            text = if (center.isHigh) "H" else "L",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = badgeColor
        )
        Text(
            text = "${center.pressureHpa}",
            style = MaterialTheme.typography.labelSmall,
            color = SkyTextPrimary
        )
    }
}
