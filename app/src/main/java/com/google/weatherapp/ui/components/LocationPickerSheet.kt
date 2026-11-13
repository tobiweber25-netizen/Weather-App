package com.google.weatherapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerSheet(
    savedLocations: List<SavedLocation>,
    selectedCustomLocation: SavedLocation?,
    searchResults: List<SavedLocation>,
    onSearchQueryChanged: (String) -> Unit,
    onSelectLocation: (SavedLocation?) -> Unit,
    onRemoveSavedLocation: (SavedLocation) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()
    val sheetBg = if (isDark) DayDetailSheetSurfaceDark else DayDetailSheetSurfaceLight
    val cardBg = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val cardBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight

    var query by remember { mutableStateOf("") }

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
                .heightIn(max = ComponentTokens.LocationSearchMaxHeight)
                .padding(horizontal = SpacingTokens.Base),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Locations",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close location switcher",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Search Input
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearchQueryChanged(it)
                },
                placeholder = {
                    Text(
                        text = "Search any city worldwide...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(IconSizeTokens.Small)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""
                                onSearchQueryChanged("")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(IconSizeTokens.Small)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(CornerTokens.Medium),
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                contentPadding = PaddingValues(bottom = SpacingTokens.ExtraLarge)
            ) {
                if (query.trim().length >= 2 && searchResults.isNotEmpty()) {
                    item {
                        Text(
                            text = "SEARCH RESULTS",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(searchResults) { city ->
                        LocationRowCard(
                            title = city.name,
                            subtitle = city.displaySubtitle,
                            isSelected = false,
                            isGps = false,
                            cardBg = cardBg,
                            cardBorder = cardBorder,
                            onClick = { onSelectLocation(city) },
                            onDelete = null
                        )
                    }
                } else {
                    // Current GPS Option
                    item {
                        LocationRowCard(
                            title = "Current Location",
                            subtitle = "Live GPS coordinates",
                            isSelected = selectedCustomLocation == null,
                            isGps = true,
                            cardBg = cardBg,
                            cardBorder = cardBorder,
                            onClick = { onSelectLocation(null) },
                            onDelete = null
                        )
                    }

                    if (savedLocations.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(SpacingTokens.ExtraSmall))
                            Text(
                                text = "SAVED CITIES",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(savedLocations) { city ->
                            val isSelected = selectedCustomLocation?.name.equals(city.name, ignoreCase = true)
                            LocationRowCard(
                                title = city.name,
                                subtitle = city.displaySubtitle,
                                isSelected = isSelected,
                                isGps = false,
                                cardBg = cardBg,
                                cardBorder = cardBorder,
                                onClick = { onSelectLocation(city) },
                                onDelete = { onRemoveSavedLocation(city) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationRowCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isGps: Boolean,
    cardBg: androidx.compose.ui.graphics.Color,
    cardBorder: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val activeBorder = if (isSelected) MaterialTheme.colorScheme.primary else cardBorder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerTokens.Medium))
            .background(cardBg)
            .border(
                width = if (isSelected) BorderTokens.Subtle else BorderTokens.Hairline,
                color = activeBorder,
                shape = RoundedCornerShape(CornerTokens.Medium)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.Base, vertical = SpacingTokens.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isGps) Icons.Default.MyLocation else Icons.Default.LocationCity,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSizeTokens.Small)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizeTokens.Small)
                )
            }
        }
    }
}
