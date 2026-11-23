package com.google.weatherapp.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.data.model.SkyThemeType
import com.google.weatherapp.data.model.WeatherEffectType
import com.google.weatherapp.theme.*
import com.google.weatherapp.ui.components.*
import com.google.weatherapp.update.AppUpdateManager
import com.google.weatherapp.update.UpdateCheckState
import com.google.weatherapp.update.UpdateDownloadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val pageStates by viewModel.pageStates.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val savedLocations by viewModel.savedLocations.collectAsStateWithLifecycle()
    val selectedCustomLocation by viewModel.selectedCustomLocation.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()

    val pages = remember(savedLocations) {
        listOf<SavedLocation?>(null) + savedLocations
    }
    val targetPageIndex = remember(selectedCustomLocation, savedLocations) {
        viewModel.activePageIndex(selectedCustomLocation, savedLocations)
            .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }
    val pagerState = rememberPagerState(
        initialPage = targetPageIndex,
        pageCount = { pages.size }
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPrefs = remember { com.google.weatherapp.notification.NotificationPreferences(context) }

    var selectedDay by remember { mutableStateOf<DailyItem?>(null) }
    var showLocationPicker by remember { mutableStateOf(false) }
    var showNotificationSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Sync user horizontal swipes -> ViewModel active location
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settledPage ->
                selectedDay = null
                viewModel.onSwipeToPage(settledPage)
            }
    }

    // Sync picker / dot selection -> HorizontalPager smoothly
    LaunchedEffect(targetPageIndex, pages.size) {
        val safeTarget = targetPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        if (pagerState.currentPage != safeTarget) {
            pagerState.animateScrollToPage(safeTarget)
        }
    }
    val updateManager = remember { AppUpdateManager(context) }
    var updateCheckState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var updateDownloadState by remember { mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadedApkFile by remember { mutableStateOf<java.io.File?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var canInstallUnknownApps by remember { mutableStateOf(updateManager.canInstallUnknownApps()) }
    var easterEggTapCount by remember { mutableIntStateOf(0) }
    var lastEasterEggTapTime by remember { mutableLongStateOf(0L) }

    LifecycleResumeEffect(Unit) {
        canInstallUnknownApps = updateManager.canInstallUnknownApps()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.onLocationPermissionGranted()
        }
        val notifGranted = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            permissions[Manifest.permission.POST_NOTIFICATIONS] == true
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
            } else if (notifGranted && notificationPrefs.isWeatherNotificationsEnabled) {
                com.google.weatherapp.notification.WeatherNotificationScheduler.ensureScheduled(context)
            }
        } else if (notificationPrefs.isWeatherNotificationsEnabled) {
            com.google.weatherapp.notification.WeatherNotificationScheduler.ensureScheduled(context)
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        val result = updateManager.checkForUpdates(forceUpdate = false)
        if (result is UpdateCheckState.UpdateAvailable && !showUpdateDialog) {
            updateCheckState = result
            showUpdateDialog = true
        }
    }

    var activeEffectOverride by remember { mutableStateOf<WeatherEffectType?>(null) }

    val visiblePageLocation = pages.getOrNull(pagerState.currentPage)
    val visiblePageKey = WeatherViewModel.pageKeyFor(visiblePageLocation)
    val visibleUiState = pageStates[visiblePageKey] ?: uiState

    val defaultEffect = remember { getDefaultWeatherEffect() }
    val resolvedEffect = when (val state = visibleUiState) {
        is WeatherUiState.Success -> activeEffectOverride ?: resolveActiveEffect(state.data.hero)
        else -> defaultEffect
    }

    val isDaytime = when (val state = visibleUiState) {
        is WeatherUiState.Success -> {
            if (activeEffectOverride == WeatherEffectType.CLEAR_NIGHT) false
            else if (activeEffectOverride == WeatherEffectType.CLEAR_DAY) true
            else state.data.hero.isDaytime
        }
        else -> defaultEffect != WeatherEffectType.CLEAR_NIGHT
    }

    val sunProgress = (visibleUiState as? WeatherUiState.Success)?.data?.telemetry?.sunProgress ?: 0.5f
    val isGoldenHour = isDaytime && (sunProgress < 0.20f || sunProgress > 0.80f) &&
        (resolvedEffect == WeatherEffectType.CLEAR_DAY || resolvedEffect == WeatherEffectType.CLOUDY)

    val isBrightSky = isDaytime && (
        resolvedEffect == WeatherEffectType.CLEAR_DAY ||
            resolvedEffect == WeatherEffectType.CLOUDY ||
            resolvedEffect == WeatherEffectType.SNOW
        )

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

    var isScrolledPastHero by remember { mutableStateOf(false) }
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (isScrolledPastHero) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "topbar_scrim_alpha"
    )

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

    CompositionLocalProvider(LocalSkyIsBright provides isBrightSky) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(skyTop, skyMid, skyBottom)
                    )
                )
                .drawBehind {
                    // Subtle zenith atmospheric depth & golden-hour/nocturnal horizon bloom
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(SkyZenithDepthWash, Transparent),
                            startY = 0f,
                            endY = size.height * 0.32f
                        )
                    )
                    if (isGoldenHour) {
                        val glowCenter = Offset(size.width * 0.5f, size.height * 0.82f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    GoldenHourBloomAmber,
                                    GoldenHourHorizonPeach,
                                    Transparent
                                ),
                                center = glowCenter,
                                radius = size.width * 0.95f
                            ),
                            radius = size.width * 0.95f,
                            center = glowCenter
                        )
                    } else if (!isDaytime) {
                        val nightCenter = Offset(size.width * 0.5f, size.height * 0.92f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(NocturnalHorizonGlow, Transparent),
                                center = nightCenter,
                                radius = size.width * 0.85f
                            ),
                            radius = size.width * 0.85f,
                            center = nightCenter
                        )
                    }
                }
        ) {
            if (visibleUiState is WeatherUiState.Success) {
                WeatherEffectOverlay(
                    effectType = resolvedEffect,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Adaptive atmospheric contrast scrim ensures crisp readability on both bright day & night skies
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                SkyContrastScrimTop,
                                if (isBrightSky) SkyContrastScrimMidDark else SkyContrastScrimMidLight,
                                if (isBrightSky) SkyContrastScrimBottomDark else SkyContrastScrimBottomLight
                            )
                        )
                    )
            )

            Scaffold(
                topBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GlassHeaderScrim.copy(alpha = 0.82f * topBarScrimAlpha))
                            .drawBehind {
                                if (topBarScrimAlpha > 0.05f) {
                                    drawLine(
                                        color = GlassCardBorderLight.copy(alpha = 0.45f * topBarScrimAlpha),
                                        start = Offset(0f, size.height),
                                        end = Offset(size.width, size.height),
                                        strokeWidth = 1f
                                    )
                                }
                            }
                    ) {
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
                                        style = AlmanacWordmarkTextStyle,
                                        color = SkyTextPrimary
                                    )

                                    val successHero = (visibleUiState as? WeatherUiState.Success)?.data?.hero
                                    AnimatedVisibility(
                                        visible = isScrolledPastHero && successHero != null,
                                        enter = fadeIn(tween(220)),
                                        exit = fadeOut(tween(180))
                                    ) {
                                        if (successHero != null) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(CornerTokens.Pill))
                                                    .background(GlassPillHighlight)
                                                    .border(
                                                        BorderTokens.Hairline,
                                                        GlassRimSpecularTop,
                                                        RoundedCornerShape(CornerTokens.Pill)
                                                    )
                                                    .padding(
                                                        horizontal = SpacingTokens.Small,
                                                        vertical = SpacingTokens.Micro
                                                    )
                                            ) {
                                                Text(
                                                    text = "${successHero.locationName} · ${successHero.tempDegrees}°",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = SkyTextPrimary,
                                                    maxLines = 1
                                                )
                                            }
                                        }
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
                                                modifier = Modifier.padding(
                                                    horizontal = SpacingTokens.Small,
                                                    vertical = SpacingTokens.ExtraSmall
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                            actions = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                                    modifier = Modifier.padding(end = SpacingTokens.Small)
                                ) {
                                    // Quick Frosted Location Switcher Button
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(ComponentTokens.TopBarActionButtonSize)
                                            .luminousGlass(cornerRadius = CornerTokens.Pill)
                                            .clickable { showLocationPicker = true }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocationOn,
                                            contentDescription = "Switch City",
                                            tint = SkyTextPrimary,
                                            modifier = Modifier.size(IconSizeTokens.Small)
                                        )
                                    }

                                    // Frosted Options Menu Button
                                    Box {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(ComponentTokens.TopBarActionButtonSize)
                                                .luminousGlass(cornerRadius = CornerTokens.Pill)
                                                .clickable { showMenu = true }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Options menu",
                                                tint = SkyTextPrimary,
                                                modifier = Modifier.size(IconSizeTokens.Small)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false },
                                            shape = RoundedCornerShape(CornerTokens.Large),
                                            containerColor = GlassMenuContainer,
                                            tonalElevation = ElevationTokens.Flat,
                                            shadowElevation = ElevationTokens.Level3,
                                            border = BorderStroke(BorderTokens.Hairline, GlassMenuBorder)
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "Locations",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                        color = SkyTextPrimary
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.LocationCity,
                                                        contentDescription = null,
                                                        tint = SunCoreGold
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
                                                        color = SkyTextPrimary
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Notifications,
                                                        contentDescription = null,
                                                        tint = PrimaryDark
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
                                                        color = SkyTextPrimary
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.SystemUpdate,
                                                        contentDescription = null,
                                                        tint = TempBarMildColor
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
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent
                            )
                        )
                    }
                },
                containerColor = Color.Transparent,
                modifier = Modifier.fillMaxSize()
            ) { paddingValues ->
                // Full-bleed edge-to-edge swipeable city pages gliding smoothly behind the floating glass header
                Box(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        key = { index -> WeatherViewModel.pageKeyFor(pages.getOrNull(index)) },
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        val pageLocation = pages.getOrNull(pageIndex)
                        val pageKey = WeatherViewModel.pageKeyFor(pageLocation)
                        val pageState = pageStates[pageKey] ?: WeatherUiState.Loading
                        val isCurrentPage = pageIndex == pagerState.currentPage
                        val pageListState = rememberLazyListState()
                        val pageScrolledPastHero by remember(pageListState) {
                            derivedStateOf {
                                pageListState.firstVisibleItemIndex > 0 || pageListState.firstVisibleItemScrollOffset > 190
                            }
                        }
                        LaunchedEffect(isCurrentPage, pageScrolledPastHero) {
                            if (isCurrentPage) {
                                isScrolledPastHero = pageScrolledPastHero
                            }
                        }

                        val pullToRefreshState = rememberPullToRefreshState()
                        PullToRefreshBox(
                            isRefreshing = isRefreshing && isCurrentPage,
                            onRefresh = { viewModel.refresh() },
                            state = pullToRefreshState,
                            indicator = {
                                PullToRefreshDefaults.Indicator(
                                    state = pullToRefreshState,
                                    isRefreshing = isRefreshing && isCurrentPage,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = paddingValues.calculateTopPadding())
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            when (val state = pageState) {
                                is WeatherUiState.Loading -> {
                                    WeatherSkeletonPlaceholder(
                                        modifier = Modifier.padding(top = paddingValues.calculateTopPadding())
                                    )
                                }
                                is WeatherUiState.Error -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(paddingValues)
                                            .padding(SpacingTokens.Base),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .luminousGlass(cornerRadius = CornerTokens.Large, elevated = true)
                                                .padding(SpacingTokens.ExtraLarge),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = if (pageLocation != null) {
                                                    "Unable to load ${pageLocation.name}"
                                                } else {
                                                    "Unable to load weather"
                                                },
                                                style = MaterialTheme.typography.headlineMedium,
                                                color = SkyTextPrimary
                                            )
                                            Spacer(modifier = Modifier.height(SpacingTokens.Small))
                                            Text(
                                                text = state.message,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SkyTextSecondary
                                            )
                                            Spacer(modifier = Modifier.height(SpacingTokens.Large))
                                            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)) {
                                                Button(
                                                    onClick = { viewModel.retryPage(pageLocation) },
                                                    shape = RoundedCornerShape(CornerTokens.Pill)
                                                ) {
                                                    Text("Retry")
                                                }
                                                OutlinedButton(
                                                    onClick = { showLocationPicker = true },
                                                    shape = RoundedCornerShape(CornerTokens.Pill),
                                                    border = BorderStroke(BorderTokens.Hairline, GlassCardBorderLight)
                                                ) {
                                                    Text("Pick a Place", color = SkyTextPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                                is WeatherUiState.Success -> {
                                    val data = state.data
                                    val reorderableState = rememberReorderableLazyListState(pageListState) { from, to ->
                                        val fromIdx = widgetOrder.indexOfFirst { it.name == from.key }
                                        val toIdx = widgetOrder.indexOfFirst { it.name == to.key }
                                        if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                                            widgetOrder = widgetOrder.toMutableList().apply {
                                                add(toIdx, removeAt(fromIdx))
                                            }
                                        }
                                    }

                                    LazyColumn(
                                        state = pageListState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            top = paddingValues.calculateTopPadding() + SpacingTokens.ExtraSmall,
                                            bottom = paddingValues.calculateBottomPadding() + SpacingTokens.Base
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
                                    ) {
                                        items(widgetOrder, key = { it.name }) { widget ->
                                            ReorderableItem(reorderableState, key = widget.name) { isDragging ->
                                                val dragScale by animateFloatAsState(
                                                    targetValue = if (isDragging) 1.02f else 1f,
                                                    animationSpec = tween(160),
                                                    label = "drag_scale"
                                                )
                                                val baseModifier = Modifier
                                                    .longPressDraggableHandle()
                                                    .graphicsLayer {
                                                        scaleX = dragScale
                                                        scaleY = dragScale
                                                    }
                                                val widgetModifier = if (isDragging) {
                                                    baseModifier
                                                        .padding(horizontal = SpacingTokens.ExtraSmall)
                                                        .border(
                                                            width = BorderTokens.Subtle,
                                                            color = GlassActiveBorderGold,
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
                                                        telemetry = data.telemetry,
                                                        alert = data.alert,
                                                        locationTimezone = data.timezone ?: pageLocation?.timezone,
                                                        isCurrentGps = pageIndex == 0,
                                                        pageCount = pages.size,
                                                        activePageIndex = pageIndex,
                                                        onSelectPageIndex = { idx ->
                                                            coroutineScope.launch {
                                                                pagerState.animateScrollToPage(idx)
                                                            }
                                                        },
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
                                                        currentTemp = data.hero.tempDegrees,
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
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = SpacingTokens.Medium, bottom = SpacingTokens.ExtraLarge),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                                                    modifier = Modifier
                                                        .luminousGlass(cornerRadius = CornerTokens.Pill)
                                                        .clickable {
                                                            Toast.makeText(
                                                                context,
                                                                "Almanac v${com.google.weatherapp.BuildConfig.VERSION_NAME} (Code ${com.google.weatherapp.BuildConfig.VERSION_CODE})",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                        .padding(
                                                            horizontal = SpacingTokens.Medium,
                                                            vertical = SpacingTokens.ExtraSmall
                                                        )
                                                ) {
                                                    Text(
                                                        text = "Almanac",
                                                        style = EditorialStoryTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                                                        color = SkyTextSecondary
                                                    )
                                                    Text(
                                                        text = "·",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = SkyTextMuted
                                                    )
                                                    Text(
                                                        text = "v${com.google.weatherapp.BuildConfig.VERSION_NAME}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = SkyTextSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val activeSuccessData = (visibleUiState as? WeatherUiState.Success)?.data
                    selectedDay?.let { currentDay ->
                        if (activeSuccessData != null) {
                            DayDetailModalSheet(
                                day = currentDay,
                                allDays = activeSuccessData.dailyList,
                                onSelectDay = { selectedDay = it },
                                onDismiss = { selectedDay = null }
                            )
                        }
                    }

                    if (showLocationPicker) {
                        LocationPickerSheet(
                            savedLocations = savedLocations,
                            selectedCustomLocation = selectedCustomLocation,
                            searchState = searchState,
                            onSearchQueryChanged = { viewModel.searchCities(it) },
                            onSelectLocation = { loc ->
                                viewModel.selectLocation(loc)
                                showLocationPicker = false
                            },
                            onRemoveSavedLocation = { viewModel.removeSavedLocation(it) },
                            onDismiss = {
                                viewModel.searchCities("")
                                showLocationPicker = false
                            }
                        )
                    }

                    if (showNotificationSheet) {
                        val successData = (visibleUiState as? WeatherUiState.Success)?.data
                        EveningForecastSheet(
                            weatherData = successData,
                            onDismiss = { showNotificationSheet = false }
                        )
                    }

                    if (showUpdateDialog) {
                        UpdateDialog(
                            checkState = updateCheckState,
                            downloadState = updateDownloadState,
                            canInstallUnknownApps = canInstallUnknownApps,
                            onDismiss = {
                                downloadJob?.cancel()
                                downloadJob = null
                                showUpdateDialog = false
                                updateDownloadState = UpdateDownloadState.Idle
                            },
                            onForceUpdateCheck = {
                                downloadJob?.cancel()
                                downloadJob = null
                                updateCheckState = UpdateCheckState.Checking
                                updateDownloadState = UpdateDownloadState.Idle
                                coroutineScope.launch {
                                    updateCheckState = updateManager.checkForUpdates(forceUpdate = true)
                                }
                            },
                            onStartDownload = { url ->
                                downloadJob?.cancel()
                                downloadJob = coroutineScope.launch {
                                    updateDownloadState = UpdateDownloadState.Downloading(0f, 0L, 0L)
                                    val result = updateManager.downloadApk(url) { progress, read, total ->
                                        updateDownloadState = UpdateDownloadState.Downloading(progress, read, total)
                                    }
                                    result.onSuccess { file ->
                                        downloadedApkFile = file
                                        canInstallUnknownApps = updateManager.canInstallUnknownApps()
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
