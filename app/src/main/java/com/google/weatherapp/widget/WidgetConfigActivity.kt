package com.google.weatherapp.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.theme.*
import com.google.weatherapp.ui.components.luminousGlass

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val prefs = WidgetPreferences(this)
        val kind = WeatherWidgetUpdater.resolveWidgetKind(this, appWidgetId)
        val initialStyle = prefs.getBackgroundStyle(
            appWidgetId = appWidgetId,
            defaultStyle = kind.defaultBackgroundStyle
        )

        setContent {
            WeatherAppTheme(darkTheme = true) {
                WidgetConfigScreen(
                    kind = kind,
                    initialStyle = initialStyle,
                    onConfirm = { chosenStyle ->
                        prefs.setBackgroundStyle(appWidgetId, chosenStyle)
                        val manager = AppWidgetManager.getInstance(this)
                        WeatherWidgetUpdater.updateSingleWidget(
                            context = this,
                            appWidgetManager = manager,
                            appWidgetId = appWidgetId,
                            kind = kind
                        )
                        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    kind: WidgetKind,
    initialStyle: WidgetBackgroundStyle,
    onConfirm: (WidgetBackgroundStyle) -> Unit
) {
    val context = LocalContext.current
    var selectedStyle by remember { mutableStateOf(initialStyle) }

    val cachedData = remember {
        WeatherLocalDataSource(context).getLatestCachedWeatherIgnoringTtl()
    }

    val previewBitmap = remember(kind, selectedStyle, cachedData) {
        WeatherWidgetRenderer.renderWidgetBitmap(
            context = context,
            kind = kind,
            style = selectedStyle,
            data = cachedData
        )
    }

    Scaffold(
        containerColor = SkyNightClearStart
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(SkyNightClearStart, SkyNightClearMid, SkyNightClearEnd)
                    )
                )
                .padding(innerPadding)
                .padding(horizontal = SpacingTokens.Base, vertical = SpacingTokens.Large)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Large)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)) {
                Text(
                    text = kind.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = SkyTextPrimary
                )
                Text(
                    text = kind.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SkyTextSecondary
                )
            }

            // Live Home Screen Preview Stage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .luminousGlass(cornerRadius = CornerTokens.Large, elevated = true)
                    .padding(SpacingTokens.Large),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = kind.title,
                    contentScale = ContentScale.Fit,
                    modifier = if (kind == WidgetKind.LOCK_SCREEN) {
                        Modifier.widthIn(max = ComponentTokens.LockScreenPreviewMaxWidth)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)) {
                Text(
                    text = "BACKGROUND STYLE",
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyTextMuted
                )

                WidgetBackgroundStyle.entries.forEach { styleOption ->
                    val isSelected = styleOption == selectedStyle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .luminousGlass(
                                cornerRadius = CornerTokens.Medium,
                                elevated = isSelected,
                                accentBorderColor = if (isSelected) PrimaryDark else null,
                                borderWidth = if (isSelected) BorderTokens.Subtle else BorderTokens.Hairline
                            )
                            .clickable { selectedStyle = styleOption }
                            .padding(SpacingTokens.Base)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = SpacingTokens.Medium),
                            verticalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
                        ) {
                            Text(
                                text = styleOption.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = SkyTextPrimary
                            )
                            Text(
                                text = styleOption.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = SkyTextSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(IconSizeTokens.Medium)
                                .clip(CircleShape)
                                .background(if (isSelected) PrimaryDark else Transparent)
                                .border(
                                    width = BorderTokens.Hairline,
                                    color = if (isSelected) PrimaryDark else GlassCardBorderLight,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = SkyNightClearStart,
                                    modifier = Modifier.size(IconSizeTokens.Small)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { onConfirm(selectedStyle) },
                shape = RoundedCornerShape(CornerTokens.Pill),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryDark,
                    contentColor = SkyNightClearStart
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SpacingTokens.Jumbo)
            ) {
                Text(
                    text = if (kind == WidgetKind.LOCK_SCREEN) {
                        "Apply Lock Screen Widget"
                    } else {
                        "Apply Widget to Home Screen"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SkyNightClearStart
                )
            }
        }
    }
}
