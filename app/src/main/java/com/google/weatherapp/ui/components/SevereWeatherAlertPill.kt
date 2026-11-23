package com.google.weatherapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.google.weatherapp.data.model.SevereAlertInfo
import com.google.weatherapp.data.model.SeverityRiskLevel
import com.google.weatherapp.theme.*

@Composable
fun SevereWeatherAlertPill(
    alert: SevereAlertInfo,
    onOpenStormMap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!alert.isVisible) return

    var expanded by remember { mutableStateOf(false) }

    val isSevere = alert.riskLevel == SeverityRiskLevel.SEVERE || alert.riskLevel == SeverityRiskLevel.HIGH
    val containerTopColor = when {
        isSevere -> AlertSevereContainer
        alert.riskLevel == SeverityRiskLevel.MODERATE -> AlertWarningContainer
        else -> AlertLowContainer
    }
    val containerBottomColor = containerTopColor.copy(alpha = (containerTopColor.alpha * 1.25f).coerceAtMost(0.88f))
    val contentColor = when {
        isSevere -> AlertSevereOnContainer
        alert.riskLevel == SeverityRiskLevel.MODERATE -> AlertWarningOnContainer
        else -> AlertLowOnContainer
    }
    val borderColor = when {
        isSevere -> AlertSevereBorder
        alert.riskLevel == SeverityRiskLevel.MODERATE -> AlertWarningBorder
        else -> GlassRimSpecularTop
    }

    val icon = when (alert.riskLevel) {
        SeverityRiskLevel.SEVERE -> Icons.Default.Warning
        SeverityRiskLevel.HIGH -> Icons.Default.ElectricBolt
        SeverityRiskLevel.MODERATE -> Icons.Default.ElectricBolt
        SeverityRiskLevel.LOW -> Icons.Default.Shield
    }

    val pulseTransition = rememberInfiniteTransition(label = "alert_beacon_pulse")
    val beaconAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beacon_alpha"
    )

    val peakSuffix = alert.peakHourLabel?.let {
        if (it == "Now") " · Active Now" else " · Peak at $it"
    } ?: ""

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.Base)
            .luminousGlass(
                cornerRadius = CornerTokens.Large,
                accentBorderColor = borderColor,
                overrideTopColor = containerTopColor,
                overrideBottomColor = containerBottomColor
            )
            .clickable { expanded = !expanded }
            .padding(horizontal = SpacingTokens.Base, vertical = SpacingTokens.Medium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                modifier = Modifier.weight(1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(IconSizeTokens.Large)
                            .clip(CircleShape)
                            .background(SunCoreGold.copy(alpha = 0.18f * beaconAlpha))
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = "Weather Alert Indicator",
                        tint = SunCoreGold,
                        modifier = Modifier.size(IconSizeTokens.Small)
                    )
                }

                Column {
                    Text(
                        text = alert.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        maxLines = 1
                    )
                    val topOfficial = alert.officialAlerts.firstOrNull()
                    val isOfficialHeadline = topOfficial != null &&
                        !alert.sourceLabel.isNullOrBlank() &&
                        alert.sourceLabel != alert.liveSatellite?.sourceLabel
                    val liveSatSummary = alert.liveSatellite
                        ?.takeIf { it.hasActiveStormOrLightning }
                        ?.formatCompactSummary()
                    val headerMeta = when {
                        !liveSatSummary.isNullOrBlank() && !isOfficialHeadline ->
                            "$liveSatSummary · ${alert.liveSatellite?.sourceLabel ?: "Meteosat Live"}"
                        isOfficialHeadline -> buildString {
                            append(alert.sourceLabel)
                            if (topOfficial!!.areaName.isNotBlank()) append(" · ${topOfficial.areaName}")
                            if (topOfficial.expiresLabel.isNotBlank()) append(" · Until ${topOfficial.expiresLabel}")
                        }
                        else -> "24h Storm Probability: ${alert.thunderstormProbability}%$peakSuffix"
                    }
                    Text(
                        text = headerMeta,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.88f),
                        maxLines = 1
                    )
                }
            }

            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse storm details" else "Expand storm details",
                tint = contentColor,
                modifier = Modifier.size(IconSizeTokens.Small)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SpacingTokens.Medium),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                Text(
                    text = alert.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )

                val topOfficial = alert.officialAlerts.firstOrNull()
                val isOfficialHeadline = topOfficial != null &&
                    !alert.sourceLabel.isNullOrBlank() &&
                    alert.sourceLabel != alert.liveSatellite?.sourceLabel
                val liveSatSummary = alert.liveSatellite
                    ?.takeIf { it.hasActiveStormOrLightning }
                    ?.formatCompactSummary()
                if (!liveSatSummary.isNullOrBlank() && isOfficialHeadline) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                        modifier = Modifier
                            .clip(RoundedCornerShape(CornerTokens.Small))
                            .background(GlassPillHighlight)
                            .padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.ExtraSmall)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ElectricBolt,
                            contentDescription = null,
                            tint = MapHudLightningAccent,
                            modifier = Modifier.size(IconSizeTokens.Micro)
                        )
                        Text(
                            text = "$liveSatSummary · ${alert.liveSatellite?.sourceLabel ?: "Meteosat Live"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor
                        )
                    }
                }
                if (isOfficialHeadline) {
                    val topInstruction = topOfficial!!.instruction
                    if (topInstruction.isNotBlank()) {
                        Text(
                            text = "SAFETY GUIDANCE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor.copy(alpha = 0.85f)
                        )
                        Text(
                            text = topInstruction,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.92f)
                        )
                    }
                } else if (topOfficial != null) {
                    // Storm tracker won the headline, but a lower-severity official advisory is also active:
                    // keep it clearly attributed so it is never conflated with the storm headline.
                    val secondarySourceLabel = topOfficial.source.trim()
                    Text(
                        text = if (secondarySourceLabel.isNotBlank()) {
                            "ALSO ACTIVE · ${secondarySourceLabel.uppercase(java.util.Locale.ROOT)}"
                        } else {
                            "ALSO ACTIVE"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor.copy(alpha = 0.85f)
                    )
                    val secondarySummary = buildString {
                        append(topOfficial.title)
                        if (topOfficial.areaName.isNotBlank()) append(" · ${topOfficial.areaName}")
                        if (topOfficial.expiresLabel.isNotBlank()) append(" · Until ${topOfficial.expiresLabel}")
                        if (topOfficial.description.isNotBlank()) append("\n${topOfficial.description}")
                        if (topOfficial.instruction.isNotBlank()) append("\n${topOfficial.instruction}")
                    }
                    Text(
                        text = secondarySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.92f)
                    )
                }

                if (alert.hourlyStormProbabilities.any { it > 0 }) {
                    Text(
                        text = "24-HOUR STORM PROBABILITY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor.copy(alpha = 0.85f)
                    )
                    DotMatrixGraph(
                        data = alert.hourlyStormProbabilities.map { it.toFloat() },
                        color = SunCoreGold,
                        unitSuffix = "%",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.AlertSparklineHeight)
                            .padding(vertical = SpacingTokens.ExtraSmall)
                    )
                }

                if (onOpenStormMap != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                        modifier = Modifier
                            .padding(top = SpacingTokens.ExtraSmall)
                            .clip(RoundedCornerShape(CornerTokens.Pill))
                            .background(GlassPillHighlight)
                            .clickable(onClick = onOpenStormMap)
                            .padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.Small)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = SunCoreGold,
                            modifier = Modifier.size(IconSizeTokens.Micro)
                        )
                        Text(
                            text = "View Storm & Lightning Map",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(IconSizeTokens.Micro)
                        )
                    }
                }
            }
        }
    }
}
