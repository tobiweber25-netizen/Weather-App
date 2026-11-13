package com.google.weatherapp.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.weatherapp.data.model.WeatherDashboardData
import com.google.weatherapp.notification.NotificationPreferences
import com.google.weatherapp.notification.TestNotificationType
import com.google.weatherapp.notification.WeatherNotificationScheduler
import com.google.weatherapp.notification.WeatherSummaryFormatter
import com.google.weatherapp.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveningForecastSheet(
    weatherData: WeatherDashboardData?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()
    val sheetBg = if (isDark) DayDetailSheetSurfaceDark else DayDetailSheetSurfaceLight
    val cardBg = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val cardBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight

    val notificationPrefs = remember { NotificationPreferences(context) }
    var isEnabled by remember { mutableStateOf(notificationPrefs.isWeatherNotificationsEnabled) }
    var systemNotificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    // Category toggle states
    var morningEnabled by remember { mutableStateOf(notificationPrefs.isMorningBriefingEnabled) }
    var eveningEnabled by remember { mutableStateOf(notificationPrefs.isEveningOutlookEnabled) }
    var severeEnabled by remember { mutableStateOf(notificationPrefs.isSevereAlertsEnabled) }
    var heatEnabled by remember { mutableStateOf(notificationPrefs.isExtremeHeatEnabled) }
    var freezeEnabled by remember { mutableStateOf(notificationPrefs.isFreezeHazardEnabled) }
    var windEnabled by remember { mutableStateOf(notificationPrefs.isGaleWindEnabled) }
    var fogEnabled by remember { mutableStateOf(notificationPrefs.isDenseFogEnabled) }
    var rainEnabled by remember { mutableStateOf(notificationPrefs.isRainNowcastEnabled) }
    var rainCessationEnabled by remember { mutableStateOf(notificationPrefs.isRainCessationEnabled) }
    var aqiEnabled by remember { mutableStateOf(notificationPrefs.isPoorAqiEnabled) }
    var uvEnabled by remember { mutableStateOf(notificationPrefs.isExtremeUvEnabled) }
    var tempDropEnabled by remember { mutableStateOf(notificationPrefs.isSharpTempDropEnabled) }

    LifecycleResumeEffect(Unit) {
        systemNotificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    var pendingTestType by remember { mutableStateOf<TestNotificationType?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        systemNotificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (isGranted || systemNotificationsAllowed) {
            val type = pendingTestType ?: TestNotificationType.EVENING
            WeatherNotificationScheduler.triggerTestNotification(context, type) { success ->
                Toast.makeText(
                    context,
                    if (success) "Notification sent to shade!" else "Failed to post notification.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(context, "Notification permission is required.", Toast.LENGTH_SHORT).show()
        }
        pendingTestType = null
    }

    fun dispatchTest(type: TestNotificationType) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pendingTestType = type
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(context, "Notifications are disabled in system settings.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                pendingTestType = type
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        WeatherNotificationScheduler.triggerTestNotification(context, type) { success ->
            Toast.makeText(
                context,
                if (success) "Notification sent to shade!" else "Failed to post notification.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val morningSummary = remember(weatherData) {
        val dailyList = weatherData?.dailyList
        if (!dailyList.isNullOrEmpty()) {
            val zoneId = try {
                if (!weatherData.timezone.isNullOrBlank()) java.time.ZoneId.of(weatherData.timezone) else java.time.ZoneId.systemDefault()
            } catch (_: Exception) {
                java.time.ZoneId.systemDefault()
            }
            val todayDate = java.time.LocalDate.now(zoneId)
            val todayStr = "${todayDate.monthValue}/${todayDate.dayOfMonth}"
            val today = dailyList.firstOrNull { it.dateText == todayStr } ?: dailyList.first()
            val currentHour = java.time.ZonedDateTime.now(zoneId).hour
            val upcomingHours = if (today.hourlyList.isNotEmpty()) {
                today.hourlyList
            } else {
                weatherData.hourlyList.take((24 - currentHour).coerceAtLeast(1))
            }
            WeatherSummaryFormatter.generateMorningSummary(
                today = today,
                upcomingHours = upcomingHours,
                locationName = weatherData.hero.locationName,
                minHourOfDay = currentHour
            )
        } else null
    }

    val eveningSummary = remember(weatherData) {
        val dailyList = weatherData?.dailyList
        if (!dailyList.isNullOrEmpty()) {
            val zoneId = try {
                if (!weatherData.timezone.isNullOrBlank()) java.time.ZoneId.of(weatherData.timezone) else java.time.ZoneId.systemDefault()
            } catch (_: Exception) {
                java.time.ZoneId.systemDefault()
            }
            val todayDate = java.time.LocalDate.now(zoneId)
            val todayStr = "${todayDate.monthValue}/${todayDate.dayOfMonth}"
            val tomorrowDate = todayDate.plusDays(1)
            val tomorrowStr = "${tomorrowDate.monthValue}/${tomorrowDate.dayOfMonth}"

            val todayIdx = dailyList.indexOfFirst { it.dateText == todayStr }.takeIf { it >= 0 } ?: 0
            val tomorrowIdx = dailyList.indexOfFirst { it.dateText == tomorrowStr }.takeIf { it >= 0 }
                ?: if (todayIdx + 1 < dailyList.size) (todayIdx + 1) else todayIdx

            WeatherSummaryFormatter.generateTomorrowSummary(
                today = dailyList[todayIdx],
                tomorrow = dailyList[tomorrowIdx],
                locationName = weatherData.hero.locationName
            )
        } else null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBg,
        shape = RoundedCornerShape(topStart = CornerTokens.ExtraLarge, topEnd = CornerTokens.ExtraLarge),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpacingTokens.Base)
                .padding(bottom = SpacingTokens.Huge),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(IconSizeTokens.Medium)
                    )
                    Text(
                        text = "Weather Notifications",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Text(
                text = "Smart, non-intrusive updates delivered at natural moments: morning briefings, evening outlooks, and multi-hazard alerts with quiet hours at night (22:00 to 07:00).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Master Switch Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CornerTokens.Large))
                    .background(cardBg)
                    .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
                    .padding(SpacingTokens.Base),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)
                ) {
                    Text(
                        text = "All Weather Notifications",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Master toggle for forecasts, hazard warnings, and event alerts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        isEnabled = checked
                        notificationPrefs.isWeatherNotificationsEnabled = checked
                        if (checked) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            WeatherNotificationScheduler.ensureScheduled(context)
                        } else {
                            WeatherNotificationScheduler.cancelAll(context)
                        }
                    }
                )
            }

            // System Permission Banner if notifications are enabled in app but blocked in Android OS
            if (isEnabled && !systemNotificationsAllowed) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(CornerTokens.Large))
                        .border(BorderTokens.Hairline, MaterialTheme.colorScheme.error, RoundedCornerShape(CornerTokens.Large)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier.padding(SpacingTokens.Base),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(IconSizeTokens.Small)
                            )
                            Text(
                                text = "System Notifications Blocked",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = "Android system permissions are currently blocking notifications for this app. You will not receive any forecasts until allowed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FilledTonalButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            shape = RoundedCornerShape(CornerTokens.Medium)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            // Category Toggles Section
            Text(
                text = "NOTIFICATION CATEGORIES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CornerTokens.Large))
                    .background(cardBg)
                    .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
                    .padding(SpacingTokens.Base),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)
            ) {
                NotificationCategoryItem(
                    icon = Icons.Default.WbSunny,
                    title = "Morning Briefing",
                    description = "Daily 07:30 AM summary with temperature and rain timing",
                    checked = morningEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        morningEnabled = it
                        notificationPrefs.isMorningBriefingEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.Bedtime,
                    title = "Evening Outlook",
                    description = "Daily 08:00 PM preview for tomorrow's weather",
                    checked = eveningEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        eveningEnabled = it
                        notificationPrefs.isEveningOutlookEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.Thunderstorm,
                    title = "Severe Weather & Storms",
                    description = "Immediate storm alerts (quiet hours exempt)",
                    checked = severeEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        severeEnabled = it
                        notificationPrefs.isSevereAlertsEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.Thermostat,
                    title = "Extreme Heat & Tropical Nights",
                    description = "High ≥ 35°C, feels like ≥ 40°C, or night low ≥ 20°C",
                    checked = heatEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        heatEnabled = it
                        notificationPrefs.isExtremeHeatEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.AcUnit,
                    title = "Frost, Freeze & Black Ice Hazard",
                    description = "Sub-zero lows and freezing on wet roadways",
                    checked = freezeEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        freezeEnabled = it
                        notificationPrefs.isFreezeHazardEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.Air,
                    title = "Gale Gust & High Wind Alert",
                    description = "Sustained wind ≥ 50 km/h or gusts ≥ 65 km/h",
                    checked = windEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        windEnabled = it
                        notificationPrefs.isGaleWindEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.Visibility,
                    title = "Dense Fog & Low Visibility",
                    description = "Visibility ≤ 1.0 km with relative humidity ≥ 90%",
                    checked = fogEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        fogEnabled = it
                        notificationPrefs.isDenseFogEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.WaterDrop,
                    title = "Rain Start Nowcast",
                    description = "Imminent rain starting within 60 minutes",
                    checked = rainEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        rainEnabled = it
                        notificationPrefs.isRainNowcastEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.WaterDrop,
                    title = "Rain Clearing Soon",
                    description = "Alert when ongoing rain is stopping in ~X minutes",
                    checked = rainCessationEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        rainCessationEnabled = it
                        notificationPrefs.isRainCessationEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.Eco,
                    title = "Poor Air Quality (AQI / PM2.5)",
                    description = "AQI > 100 (Sensitive) / > 150 (Unhealthy) or PM2.5 > 35 µg/m³",
                    checked = aqiEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        aqiEnabled = it
                        notificationPrefs.isPoorAqiEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.Default.WbSunny,
                    title = "Extreme UV Alert & Protection",
                    description = "UV index ≥ 8 with solar noon protection guidance",
                    checked = uvEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        uvEnabled = it
                        notificationPrefs.isExtremeUvEnabled = it
                    }
                )

                HorizontalDivider(color = cardBorder, thickness = BorderTokens.Hairline)

                NotificationCategoryItem(
                    icon = Icons.AutoMirrored.Filled.TrendingDown,
                    title = "Sharp Temperature Drop",
                    description = "Alert when tomorrow drops ≥ 7°C compared to today",
                    checked = tempDropEnabled,
                    enabled = isEnabled,
                    onCheckedChange = {
                        tempDropEnabled = it
                        notificationPrefs.isSharpTempDropEnabled = it
                    }
                )
            }

            // Routine Forecasts Previews
            Text(
                text = "NOTIFICATION PREVIEWS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // 1. Morning Preview
            if (morningSummary != null) {
                NotificationPreviewCard(
                    icon = Icons.Default.WbSunny,
                    badgeText = "MORNING BRIEFING (07:30 AM)",
                    title = morningSummary.title,
                    body = morningSummary.conciseText,
                    cardBg = cardBg,
                    cardBorder = cardBorder
                )
            }

            // 2. Evening Preview
            if (eveningSummary != null) {
                NotificationPreviewCard(
                    icon = Icons.Default.Bedtime,
                    badgeText = "EVENING OUTLOOK (08:00 PM)",
                    title = eveningSummary.title,
                    body = eveningSummary.conciseText,
                    cardBg = cardBg,
                    cardBorder = cardBorder
                )
            }

            // 3. Rain Nowcast Preview
            val realNowcast = weatherData?.nowcast
            val nowcastBody = if (realNowcast != null && realNowcast.isPrecipitationImminent) {
                WeatherSummaryFormatter.generateRainNowcastSummary(
                    nowcast = realNowcast,
                    locationName = weatherData.hero.locationName
                ).conciseText
            } else {
                "No precipitation expected in the next 2 hours."
            }

            NotificationPreviewCard(
                icon = Icons.Default.WaterDrop,
                badgeText = "RAIN ALERT (EVENT-DRIVEN)",
                title = "Rain alert • ${weatherData?.hero?.locationName ?: "Current Location"}",
                body = nowcastBody,
                cardBg = cardBg,
                cardBorder = cardBorder
            )

            // Test Actions Header
            Text(
                text = "TEST ALL 12 NOTIFICATIONS ON DEVICE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Row 1: Daily Briefings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                TestNotificationButton(
                    icon = Icons.Default.WbSunny,
                    label = "Morning",
                    onClick = { dispatchTest(TestNotificationType.MORNING) },
                    modifier = Modifier.weight(1f)
                )
                TestNotificationButton(
                    icon = Icons.Default.Bedtime,
                    label = "Evening",
                    onClick = { dispatchTest(TestNotificationType.EVENING) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 2: Rain Start & Cessation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                TestNotificationButton(
                    icon = Icons.Default.WaterDrop,
                    label = "Rain Start",
                    onClick = { dispatchTest(TestNotificationType.RAIN) },
                    modifier = Modifier.weight(1f)
                )
                TestNotificationButton(
                    icon = Icons.Default.WaterDrop,
                    label = "Rain Stop",
                    onClick = { dispatchTest(TestNotificationType.RAIN_CESSATION) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 3: Storm & Heat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                TestNotificationButton(
                    icon = Icons.Default.Thunderstorm,
                    label = "Storm Alert",
                    onClick = { dispatchTest(TestNotificationType.SEVERE) },
                    modifier = Modifier.weight(1f)
                )
                TestNotificationButton(
                    icon = Icons.Default.Thermostat,
                    label = "Extreme Heat",
                    onClick = { dispatchTest(TestNotificationType.HEAT) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 4: Freeze & Wind
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                TestNotificationButton(
                    icon = Icons.Default.AcUnit,
                    label = "Freeze / Ice",
                    onClick = { dispatchTest(TestNotificationType.FREEZE) },
                    modifier = Modifier.weight(1f)
                )
                TestNotificationButton(
                    icon = Icons.Default.Air,
                    label = "Gale Wind",
                    onClick = { dispatchTest(TestNotificationType.WIND) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 5: Fog & Air Quality
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                TestNotificationButton(
                    icon = Icons.Default.Visibility,
                    label = "Dense Fog",
                    onClick = { dispatchTest(TestNotificationType.FOG) },
                    modifier = Modifier.weight(1f)
                )
                TestNotificationButton(
                    icon = Icons.Default.Eco,
                    label = "Poor AQI",
                    onClick = { dispatchTest(TestNotificationType.AQI) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 6: Extreme UV & Temperature Drop
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                TestNotificationButton(
                    icon = Icons.Default.WbSunny,
                    label = "Extreme UV",
                    onClick = { dispatchTest(TestNotificationType.UV) },
                    modifier = Modifier.weight(1f)
                )
                TestNotificationButton(
                    icon = Icons.AutoMirrored.Filled.TrendingDown,
                    label = "Temp Drop",
                    onClick = { dispatchTest(TestNotificationType.TEMP_DROP) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NotificationCategoryItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.Small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSizeTokens.Small)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(SpacingTokens.Small))
        Switch(
            checked = checked && enabled,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun TestNotificationButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(CornerTokens.Medium)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSizeTokens.Small)
        )
        Spacer(modifier = Modifier.width(SpacingTokens.ExtraSmall))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NotificationPreviewCard(
    icon: ImageVector,
    badgeText: String,
    title: String,
    body: String,
    cardBg: androidx.compose.ui.graphics.Color,
    cardBorder: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerTokens.Large))
            .background(cardBg)
            .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
            .padding(SpacingTokens.Base),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSizeTokens.Small)
            )
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
