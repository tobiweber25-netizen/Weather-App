package com.google.weatherapp.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import com.google.weatherapp.theme.*
import com.google.weatherapp.update.UpdateCheckState
import com.google.weatherapp.update.UpdateDownloadState
import java.util.Locale

@Composable
fun UpdateDialog(
    checkState: UpdateCheckState,
    downloadState: UpdateDownloadState,
    canInstallUnknownApps: Boolean,
    onDismiss: () -> Unit,
    onStartDownload: (String) -> Unit,
    onRequestInstallPermission: () -> Unit,
    onInstallApk: () -> Unit,
    onForceUpdateCheck: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(CornerTokens.ExtraLarge),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = ElevationTokens.Level3,
            border = BorderStroke(BorderTokens.Hairline, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.Base)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingTokens.ExtraLarge),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    downloadState is UpdateDownloadState.Downloading -> {
                        DownloadingContent(downloadState = downloadState)
                    }
                    downloadState is UpdateDownloadState.ReadyToInstall -> {
                        ReadyToInstallContent(
                            canInstallUnknownApps = canInstallUnknownApps,
                            onRequestPermission = onRequestInstallPermission,
                            onInstall = onInstallApk
                        )
                    }
                    downloadState is UpdateDownloadState.Error -> {
                        ErrorContent(
                            title = "Download Failed",
                            message = downloadState.message,
                            onDismiss = onDismiss
                        )
                    }
                    checkState is UpdateCheckState.Checking -> {
                        CheckingContent()
                    }
                    checkState is UpdateCheckState.UpdateAvailable -> {
                        UpdateAvailableContent(
                            update = checkState,
                            canInstallUnknownApps = canInstallUnknownApps,
                            onStartDownload = { onStartDownload(checkState.downloadUrl) },
                            onRequestPermission = onRequestInstallPermission,
                            onDismiss = onDismiss
                        )
                    }
                    checkState is UpdateCheckState.UpToDate -> {
                        UpToDateContent(
                            currentVersion = checkState.currentVersion,
                            onDismiss = onDismiss,
                            onForceUpdateCheck = onForceUpdateCheck
                        )
                    }
                    checkState is UpdateCheckState.Error -> {
                        ErrorContent(
                            title = "Update Check Failed",
                            message = checkState.message,
                            onDismiss = onDismiss
                        )
                    }
                    else -> {
                        CheckingContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base),
        modifier = Modifier.padding(vertical = SpacingTokens.Base)
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconSizeTokens.ExtraLarge)
        )
        Text(
            text = "Checking for updates...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun UpToDateContent(
    currentVersion: String,
    onDismiss: () -> Unit,
    onForceUpdateCheck: (() -> Unit)? = null
) {
    var tapCount by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
    ) {
        Icon(
            imageVector = Icons.Default.Verified,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(IconSizeTokens.ExtraLarge)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    tapCount++
                    if (tapCount in 1..2) {
                        Toast.makeText(
                            context,
                            "${3 - tapCount} taps to force OTA test",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (tapCount >= 3) {
                        tapCount = 0
                        Toast.makeText(
                            context,
                            "OTA Test Triggered!",
                            Toast.LENGTH_SHORT
                        ).show()
                        onForceUpdateCheck?.invoke()
                    }
                }
        )
        Text(
            text = "You’re up to date",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Almanac v$currentVersion is currently the newest version available.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                tapCount++
                if (tapCount in 1..2) {
                    Toast.makeText(
                        context,
                        "${3 - tapCount} taps to force OTA test",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (tapCount >= 3) {
                    tapCount = 0
                    Toast.makeText(
                        context,
                        "OTA Test Triggered!",
                        Toast.LENGTH_SHORT
                    ).show()
                    onForceUpdateCheck?.invoke()
                }
            }
        )
        Spacer(modifier = Modifier.height(SpacingTokens.Small))
        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(CornerTokens.Pill),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Done", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun UpdateAvailableContent(
    update: UpdateCheckState.UpdateAvailable,
    canInstallUnknownApps: Boolean,
    onStartDownload: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val formattedSize = remember(update.assetSize) {
        if (update.assetSize > 0) {
            String.format(Locale.US, "%.1f MB", update.assetSize / (1024.0 * 1024.0))
        } else ""
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
    ) {
        Icon(
            imageVector = Icons.Default.SystemUpdate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconSizeTokens.ExtraLarge)
        )
        Text(
            text = "Update Available",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(CornerTokens.Pill)
        ) {
            Text(
                text = "v${update.currentVersion}  →  v${update.newVersion}" + if (formattedSize.isNotBlank()) "  ($formattedSize)" else "",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = SpacingTokens.Base, vertical = SpacingTokens.Small)
            )
        }

        if (update.releaseNotes.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ComponentTokens.HeroCardMinHeight)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(CornerTokens.Medium)
                    )
                    .padding(SpacingTokens.Medium)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = update.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!canInstallUnknownApps) {
            OutlinedButton(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(CornerTokens.Pill),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Enable App Install Permission", style = MaterialTheme.typography.labelLarge)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(CornerTokens.Pill),
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Later")
            }
            Button(
                onClick = onStartDownload,
                shape = RoundedCornerShape(CornerTokens.Pill),
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Download")
            }
        }
    }
}

@Composable
private fun DownloadingContent(downloadState: UpdateDownloadState.Downloading) {
    val progressPercent = (downloadState.progress * 100).toInt().coerceIn(0, 100)
    val downloadedMb = downloadState.downloadedBytes / (1024.0 * 1024.0)
    val totalMb = downloadState.totalBytes / (1024.0 * 1024.0)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
    ) {
        Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconSizeTokens.ExtraLarge)
        )
        Text(
            text = "Downloading Update",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (downloadState.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { downloadState.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SpacingTokens.Small)
                    .clip(RoundedCornerShape(CornerTokens.Pill)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SpacingTokens.Small)
                    .clip(RoundedCornerShape(CornerTokens.Pill)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Text(
            text = if (downloadState.totalBytes > 0) {
                String.format(Locale.US, "%d%% (%.1f / %.1f MB)", progressPercent, downloadedMb, totalMb)
            } else {
                String.format(Locale.US, "%.1f MB downloaded", downloadedMb)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReadyToInstallContent(
    canInstallUnknownApps: Boolean,
    onRequestPermission: () -> Unit,
    onInstall: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
    ) {
        Icon(
            imageVector = Icons.Default.SystemUpdate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconSizeTokens.ExtraLarge)
        )
        Text(
            text = "Download Complete",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "The update is ready to install. Android will now update Almanac.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!canInstallUnknownApps) {
            Button(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(CornerTokens.Pill),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Grant Permission to Install")
            }
        } else {
            Button(
                onClick = onInstall,
                shape = RoundedCornerShape(CornerTokens.Pill),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Install Now")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(IconSizeTokens.ExtraLarge)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(SpacingTokens.Small))
        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(CornerTokens.Pill),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Dismiss")
        }
    }
}
