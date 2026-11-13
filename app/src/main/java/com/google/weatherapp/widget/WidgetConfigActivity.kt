package com.google.weatherapp.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.theme.BorderTokens
import com.google.weatherapp.theme.CornerTokens
import com.google.weatherapp.theme.GlassCardBackgroundDark
import com.google.weatherapp.theme.GlassCardBackgroundLight
import com.google.weatherapp.theme.GlassCardBorderLight
import com.google.weatherapp.theme.IconSizeTokens
import com.google.weatherapp.theme.OnPrimaryLight
import com.google.weatherapp.theme.PrimaryDark
import com.google.weatherapp.theme.SkyNightClearEnd
import com.google.weatherapp.theme.SkyNightClearMid
import com.google.weatherapp.theme.SkyNightClearStart
import com.google.weatherapp.theme.SkyTextMuted
import com.google.weatherapp.theme.SkyTextPrimary
import com.google.weatherapp.theme.SkyTextSecondary
import com.google.weatherapp.theme.SpacingTokens
import com.google.weatherapp.theme.Transparent
import com.google.weatherapp.theme.WeatherAppTheme

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
        val initialStyle = prefs.getBackgroundStyle(appWidgetId)

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
                    fontWeight = FontWeight.Bold,
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
                    .clip(RoundedCornerShape(CornerTokens.Large))
                    .background(GlassCardBackgroundDark)
                    .border(
                        width = BorderTokens.Hairline,
                        color = GlassCardBorderLight,
                        shape = RoundedCornerShape(CornerTokens.Large)
                    )
                    .padding(SpacingTokens.Large),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = kind.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
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
                            .clip(RoundedCornerShape(CornerTokens.Medium))
                            .background(if (isSelected) GlassCardBackgroundLight else GlassCardBackgroundDark)
                            .border(
                                width = if (isSelected) BorderTokens.Subtle else BorderTokens.Hairline,
                                color = if (isSelected) PrimaryDark else GlassCardBorderLight,
                                shape = RoundedCornerShape(CornerTokens.Medium)
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
                    text = "Apply Widget to Home Screen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnPrimaryLight.copy(alpha = 0f).let { SkyNightClearStart }
                )
            }
        }
    }
}
