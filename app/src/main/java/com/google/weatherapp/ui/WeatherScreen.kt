package com.google.weatherapp.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.SkyThemeType
import com.google.weatherapp.data.model.WeatherEffectType
import com.google.weatherapp.theme.*
import com.google.weatherapp.ui.components.*
import com.google.weatherapp.update.AppUpdateManager
import com.google.weatherapp.update.UpdateCheckState
import com.google.weatherapp.update.UpdateDownloadState
import kotlinx.coroutines.launch
import java.time.LocalTime
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class DashboardSection {
    HERO, ALERT, HOURLY, DAILY, TELEMETRY
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WeatherScreen(
    viewModel: WeatherViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val savedLocations by viewModel.savedLocations.collectAsStateWithLifecycle()
    val selectedCustomLocation by viewModel.selectedCustomLocation.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPrefs = remember { com.google.weatherapp.notification.NotificationPreferences(context) }

    var selectedDay by remember { mutableStateOf<DailyItem?>(null) }
    var showLocationPicker by remember { mutableStateOf(false) }
    var showNotificationSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val updateManager = remember { AppUpdateManager(context) }
    var updateCheckState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var updateDownloadState by remember { mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadedApkFile by remember { mutableStateOf<java.io.File?>(null) }
    var easterEggTapCount by remember { mutableIntStateOf(0) }
    var lastEasterEggTapTime by remember { mutableLongStateOf(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.onLocationPermissionGranted()
        }
        val notifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
        if (notifGranted) {
            com.google.weatherapp.notification.WeatherNotificationScheduler.ensureScheduled(context)
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (!viewModel.hasLocationPermission()) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!notifGranted && notificationPrefs.isWeatherNotificationsEnabled) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    var activeEffectOverride by remember { mutableStateOf<WeatherEffectType?>(null) }

    val defaultEffect = remember { getDefaultWeatherEffect() }
    val resolvedEffect = when (val state = uiState) {
        is WeatherUiState.Success -> activeEffectOverride ?: resolveActiveEffect(state.data.hero)
        else -> defaultEffect
    }

    val isDaytime = when (val state = uiState) {
        is WeatherUiState.Success -> {
            if (activeEffectOverride == WeatherEffectType.CLEAR_NIGHT) false
            else if (activeEffectOverride == WeatherEffectType.CLEAR_DAY) true
            else state.data.hero.isDaytime
        }
        else -> defaultEffect != WeatherEffectType.CLEAR_NIGHT
    }

    val targetSkyPalette = remember(resolvedEffect, isDaytime) {
        resolveSkyGradientStopColors(resolvedEffect, isDaytime)
    }

    val skyTop by animateColorAsState(
        targetValue = targetSkyPalette.first,
        animationSpec = tween(durationMillis = 900),
        label = "sky_top"
    )
    val skyMid by animateColorAsState(
        targetValue = targetSkyPalette.second,
        animationSpec = tween(durationMillis = 900),
        label = "sky_mid"
    )
    val skyBottom by animateColorAsState(
        targetValue = targetSkyPalette.third,
        animationSpec = tween(durationMillis = 900),
        label = "sky_bottom"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(skyTop, skyMid, skyBottom)
                )
            )
    ) {
        if (uiState is WeatherUiState.Success) {
            WeatherEffectOverlay(
                effectType = resolvedEffect,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            SkyContrastScrimTop,
                            SkyContrastScrimMidLight,
                            SkyContrastScrimBottomLight
                        )
                    )
                )
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                            modifier = Modifier
                                .clip(RoundedCornerShape(CornerTokens.Small))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastEasterEggTapTime > 2000L) {
                                        easterEggTapCount = 1
                                    } else {
                                        easterEggTapCount++
                                    }
                                    lastEasterEggTapTime = now

                                    if (easterEggTapCount in 3..4) {
                                        Toast.makeText(
                                            context,
                                            "${5 - easterEggTapCount} taps to test OTA updates",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (easterEggTapCount >= 5) {
                                        easterEggTapCount = 0
                                        Toast.makeText(
                                            context,
                                            "OTA Update Test Mode Activated!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        showUpdateDialog = true
                                        updateCheckState = UpdateCheckState.Checking
                                        updateDownloadState = UpdateDownloadState.Idle
                                        coroutineScope.launch {
                                            updateCheckState = updateManager.checkForUpdates(forceUpdate = true)
                                        }
                                    }
                                }
                        ) {
                            Text(
                                text = "Almanac",
                                fontFamily = EditorialSerifFont,
                                fontStyle = FontStyle.Italic,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Normal,
                                color = SkyTextPrimary
                            )
                            Surface(
                                shape = RoundedCornerShape(CornerTokens.Pill),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                border = BorderStroke(BorderTokens.Hairline, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                modifier = Modifier.clickable {
                                    Toast.makeText(
                                        context,
                                        "🎉 OTA Update Successful! Running v1.0.1 wirelessly 🚀",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            ) {
                                Text(
                                    text = "v1.0.1 🚀",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.ExtraSmall)
                                )
                            }
                            if (activeEffectOverride != null) {
                                Surface(
                                    color = GlassCardBackgroundDark,
                                    shape = RoundedCornerShape(CornerTokens.Pill),
                                    border = BorderStroke(BorderTokens.Hairline, GlassCardBorderLight)
                                ) {
                                    Text(
                                        text = "Preview",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SkyTextSecondary,
                                        modifier = Modifier.padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.ExtraSmall)
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options menu",
                                    tint = SkyTextPrimary
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                shape = RoundedCornerShape(CornerTokens.Large),
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = ElevationTokens.Flat,
                                shadowElevation = ElevationTokens.Level2,
                                border = BorderStroke(BorderTokens.Hairline, MaterialTheme.colorScheme.outline)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Locations",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.LocationCity,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showLocationPicker = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Notifications & Briefings",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Notifications,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showNotificationSheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Check for Updates",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.SystemUpdate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showUpdateDialog = true
                                        updateCheckState = UpdateCheckState.Checking
                                        updateDownloadState = UpdateDownloadState.Idle
                                        coroutineScope.launch {
                                            updateCheckState = updateManager.checkForUpdates()
                                        }
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize()
        ) { paddingValues ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (val state = uiState) {
                    is WeatherUiState.Loading -> {
                        WeatherSkeletonPlaceholder()
                    }
                    is WeatherUiState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(SpacingTokens.Base),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(CornerTokens.Large))
                                    .background(GlassCardBackgroundDark)
                                    .border(
                                        width = BorderTokens.Hairline,
                                        color = GlassCardBorderLight,
                                        shape = RoundedCornerShape(CornerTokens.Large)
                                    )
                                    .padding(SpacingTokens.ExtraLarge),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Unable to load weather",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = SkyTextPrimary
                                )
                                Spacer(modifier = Modifier.height(SpacingTokens.Small))
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SkyTextSecondary
                                )
                                Spacer(modifier = Modifier.height(SpacingTokens.Base))
                                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)) {
                                    Button(onClick = { viewModel.loadWeatherData() }) {
                                        Text("Retry")
                                    }
                                    OutlinedButton(onClick = { showLocationPicker = true }) {
                                        Text("Pick a City", color = SkyTextPrimary)
                                    }
                                }
                            }
                        }
                    }
                    is WeatherUiState.Success -> {
                        val data = state.data
                        val listState = rememberLazyListState()
                        var widgetOrder by remember {
                            mutableStateOf(
                                listOf(
                                    DashboardSection.HERO,
                                    DashboardSection.ALERT,
                                    DashboardSection.HOURLY,
                                    DashboardSection.DAILY,
                                    DashboardSection.TELEMETRY
                                )
                            )
                        }
                        val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
                            widgetOrder = widgetOrder.toMutableList().apply {
                                add(to.index, removeAt(from.index))
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
                        ) {
                            items(widgetOrder, key = { it.name }) { widget ->
                                ReorderableItem(reorderableState, key = widget.name) { isDragging ->
                                    val baseModifier = Modifier.longPressDraggableHandle()
                                    val widgetModifier = if (isDragging) {
                                        baseModifier
                                            .padding(horizontal = SpacingTokens.ExtraSmall)
                                            .border(
                                                width = BorderTokens.Subtle,
                                                color = GlassCardBorderLight,
                                                shape = RoundedCornerShape(CornerTokens.Large)
                                            )
                                    } else {
                                        baseModifier
                                    }

                                    when (widget) {
                                        DashboardSection.HERO -> HeroWeatherSection(
                                            hero = data.hero,
                                            hourlyList = data.hourlyList,
                                            nowcast = data.nowcast,
                                            onLocationClick = { showLocationPicker = true },
                                            modifier = widgetModifier
                                        )
                                        DashboardSection.ALERT -> SevereWeatherAlertPill(
                                            alert = data.alert,
                                            modifier = widgetModifier
                                        )
                                        DashboardSection.HOURLY -> HourlyForecastStrip(
                                            hourlyItems = data.hourlyList,
                                            modifier = widgetModifier
                                        )
                                        DashboardSection.DAILY -> DailyForecastList(
                                            dailyList = data.dailyList,
                                            onDayClick = { selectedDay = it },
                                            modifier = widgetModifier
                                        )
                                        DashboardSection.TELEMETRY -> BentoTelemetryGrid(
                                            telemetry = data.telemetry,
                                            hourlyList = data.hourlyList,
                                            isDaytime = data.hero.isDaytime,
                                            modifier = widgetModifier
                                        )
                                    }
                                }
                            }
                            item {
                                Spacer(modifier = Modifier.height(SpacingTokens.Huge))
                            }
                        }

                        selectedDay?.let { currentDay ->
                            DayDetailModalSheet(
                                day = currentDay,
                                allDays = data.dailyList,
                                onSelectDay = { selectedDay = it },
                                onDismiss = { selectedDay = null }
                            )
                        }
                    }
                }

                if (showLocationPicker) {
                    LocationPickerSheet(
                        savedLocations = savedLocations,
                        selectedCustomLocation = selectedCustomLocation,
                        searchResults = searchResults,
                        onSearchQueryChanged = { viewModel.searchCities(it) },
                        onSelectLocation = { loc ->
                            viewModel.selectLocation(loc)
                            showLocationPicker = false
                        },
                        onRemoveSavedLocation = { viewModel.removeSavedLocation(it) },
                        onDismiss = { showLocationPicker = false }
                    )
                }

                if (showNotificationSheet) {
                    val successData = (uiState as? WeatherUiState.Success)?.data
                    EveningForecastSheet(
                        weatherData = successData,
                        onDismiss = { showNotificationSheet = false }
                    )
                }

                if (showUpdateDialog) {
                    UpdateDialog(
                        checkState = updateCheckState,
                        downloadState = updateDownloadState,
                        canInstallUnknownApps = updateManager.canInstallUnknownApps(),
                        onDismiss = {
                            showUpdateDialog = false
                            updateDownloadState = UpdateDownloadState.Idle
                        },
                        onForceUpdateCheck = {
                            updateCheckState = UpdateCheckState.Checking
                            updateDownloadState = UpdateDownloadState.Idle
                            coroutineScope.launch {
                                updateCheckState = updateManager.checkForUpdates(forceUpdate = true)
                            }
                        },
                        onStartDownload = { url ->
                            coroutineScope.launch {
                                updateDownloadState = UpdateDownloadState.Downloading(0f, 0L, 0L)
                                val result = updateManager.downloadApk(url) { progress, read, total ->
                                    updateDownloadState = UpdateDownloadState.Downloading(progress, read, total)
                                }
                                result.onSuccess { file ->
                                    downloadedApkFile = file
                                    updateDownloadState = UpdateDownloadState.ReadyToInstall(file.absolutePath)
                                }.onFailure { err ->
                                    updateDownloadState = UpdateDownloadState.Error(err.message ?: "Download failed")
                                }
                            }
                        },
                        onRequestInstallPermission = {
                            updateManager.openUnknownAppInstallSettings()
                        },
                        onInstallApk = {
                            downloadedApkFile?.let { updateManager.installApk(it) }
                        }
                    )
                }
            }
        }
    }
}

internal fun getDefaultWeatherEffect(currentTime: LocalTime = LocalTime.now()): WeatherEffectType {
    val hour = currentTime.hour
    return if (hour in 6..18) WeatherEffectType.CLEAR_DAY else WeatherEffectType.CLEAR_NIGHT
}

internal fun resolveSkyGradientStopColors(
    effectType: WeatherEffectType,
    isDaytime: Boolean = true
): Triple<Color, Color, Color> {
    if (effectType == WeatherEffectType.CLEAR_NIGHT || !isDaytime) {
        return when (effectType) {
            WeatherEffectType.CLOUDY -> Triple(SkyNightOvercastStart, SkyNightOvercastMid, SkyNightOvercastEnd)
            WeatherEffectType.RAIN -> Triple(SkyNightRainStart, SkyNightRainMid, SkyNightRainEnd)
            WeatherEffectType.SNOW -> Triple(SkyNightSnowStart, SkyNightSnowMid, SkyNightSnowEnd)
            WeatherEffectType.THUNDERSTORM -> Triple(SkyNightStormStart, SkyNightStormMid, SkyNightStormEnd)
            else -> Triple(SkyNightClearStart, SkyNightClearMid, SkyNightClearEnd)
        }
    }
    return when (effectType) {
        WeatherEffectType.CLEAR_DAY -> Triple(SkyDayClearStart, SkyDayClearMid, SkyDayClearEnd)
        WeatherEffectType.CLOUDY -> Triple(SkyOvercastStart, SkyOvercastMid, SkyOvercastEnd)
        WeatherEffectType.RAIN -> Triple(SkyRainStart, SkyRainMid, SkyRainEnd)
        WeatherEffectType.SNOW -> Triple(SkySnowStart, SkySnowMid, SkySnowEnd)
        WeatherEffectType.THUNDERSTORM -> Triple(SkyStormStart, SkyStormMid, SkyStormEnd)
        else -> Triple(SkyDayClearStart, SkyDayClearMid, SkyDayClearEnd)
    }
}

internal fun resolveActiveEffect(hero: HeroWeather, choice: WeatherEffectType = WeatherEffectType.AUTO): WeatherEffectType {
    if (choice != WeatherEffectType.AUTO) return choice
    val cond = hero.conditionText.lowercase()
    val isClearOrSunny = cond.contains("clear") || cond.contains("sun")
    return when {
        cond.contains("thunder") || cond.contains("storm") || cond.contains("lightning") || (!isClearOrSunny && hero.skyTheme == SkyThemeType.THUNDERSTORM) ->
            WeatherEffectType.THUNDERSTORM
        cond.contains("snow") || cond.contains("sleet") || cond.contains("blizzard") || cond.contains("flurr") || hero.skyTheme == SkyThemeType.SNOW ->
            WeatherEffectType.SNOW
        cond.contains("rain") || cond.contains("drizzle") || cond.contains("shower") || (!isClearOrSunny && hero.skyTheme == SkyThemeType.RAIN) ->
            WeatherEffectType.RAIN
        cond.contains("cloud") || cond.contains("overcast") || cond.contains("fog") || cond.contains("mist") || (!isClearOrSunny && hero.skyTheme == SkyThemeType.OVERCAST) ->
            WeatherEffectType.CLOUDY
        !hero.isDaytime || hero.skyTheme == SkyThemeType.CLEAR_NIGHT ->
            WeatherEffectType.CLEAR_NIGHT
        else ->
            WeatherEffectType.CLEAR_DAY
    }
}

internal fun resolveActiveEffectForDay(day: DailyItem, choice: WeatherEffectType = WeatherEffectType.AUTO): WeatherEffectType {
    if (choice != WeatherEffectType.AUTO) return choice
    val cond = day.conditionText.ifBlank { day.dayConditionText }.lowercase()
    val isClearOrSunny = (cond.contains("clear") || cond.contains("sun")) && !cond.contains("cloud")
    return when {
        cond.contains("thunder") || cond.contains("storm") || cond.contains("lightning") || (!isClearOrSunny && day.thunderstormProbability >= 40) ->
            WeatherEffectType.THUNDERSTORM
        cond.contains("snow") || cond.contains("sleet") || cond.contains("blizzard") || cond.contains("ice") || cond.contains("flurr") ->
            WeatherEffectType.SNOW
        cond.contains("rain") || cond.contains("drizzle") || cond.contains("shower") || (!isClearOrSunny && day.rainProbability >= 40) ->
            WeatherEffectType.RAIN
        cond.contains("cloud") || cond.contains("overcast") || cond.contains("fog") || cond.contains("mist") || (!isClearOrSunny && day.cloudCoverPercent >= 60) ->
            WeatherEffectType.CLOUDY
        else ->
            WeatherEffectType.CLEAR_DAY
    }
}

internal fun resolveActiveEffectForNight(day: DailyItem, choice: WeatherEffectType = WeatherEffectType.AUTO): WeatherEffectType {
    if (choice != WeatherEffectType.AUTO) return choice
    val cond = (if (day.nightConditionText.isNotBlank()) day.nightConditionText else day.conditionText).lowercase()
    val isClearOrSunny = (cond.contains("clear") || cond.contains("sun")) && !cond.contains("cloud")
    return when {
        cond.contains("thunder") || cond.contains("storm") || cond.contains("lightning") || (!isClearOrSunny && day.thunderstormProbability >= 40) ->
            WeatherEffectType.THUNDERSTORM
        cond.contains("snow") || cond.contains("sleet") || cond.contains("blizzard") || cond.contains("ice") || cond.contains("flurr") ->
            WeatherEffectType.SNOW
        cond.contains("rain") || cond.contains("drizzle") || cond.contains("shower") || (!isClearOrSunny && day.rainProbability >= 40) ->
            WeatherEffectType.RAIN
        cond.contains("cloud") || cond.contains("overcast") || cond.contains("fog") || cond.contains("mist") || (!isClearOrSunny && day.cloudCoverPercent >= 60) ->
            WeatherEffectType.CLOUDY
        else ->
            WeatherEffectType.CLEAR_NIGHT
    }
}

internal fun resolveActiveEffectForHourly(hour: HourlyItem, choice: WeatherEffectType = WeatherEffectType.AUTO): WeatherEffectType {
    if (choice != WeatherEffectType.AUTO) return choice
    val cond = hour.conditionText.lowercase()
    val isClearOrSunny = cond.contains("clear") || cond.contains("sun")
    return when {
        cond.contains("thunder") || cond.contains("storm") || cond.contains("lightning") ->
            WeatherEffectType.THUNDERSTORM
        cond.contains("snow") || cond.contains("sleet") || cond.contains("blizzard") || cond.contains("flurr") ->
            WeatherEffectType.SNOW
        cond.contains("rain") || cond.contains("drizzle") || cond.contains("shower") || (!isClearOrSunny && hour.rainProbability >= 50) ->
            WeatherEffectType.RAIN
        cond.contains("cloud") || cond.contains("overcast") || cond.contains("fog") || cond.contains("mist") ->
            WeatherEffectType.CLOUDY
        !hour.isDaytime ->
            WeatherEffectType.CLEAR_NIGHT
        else ->
            WeatherEffectType.CLEAR_DAY
    }
}
