package com.google.weatherapp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.google.weatherapp.theme.*

/**
 * Silky directional glass shimmer placeholder that mirrors the live dashboard layout
 * to avoid layout jumps when weather data arrives.
 */
@Composable
fun WeatherSkeletonPlaceholder(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "glass_shimmer_transition")
    val shimmerOffset by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_sweep"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            SkeletonShimmerBase,
            SkeletonShimmerHighlight,
            SkeletonShimmerBase
        ),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 340f, 220f)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.Base, vertical = SpacingTokens.Small),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
    ) {
        // 1. Borderless Centered Hero Placeholder (matches live HeroWeatherSection geometry)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SpacingTokens.Medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)
        ) {
            Box(
                modifier = Modifier
                    .width(SpacingTokens.ContentPaddingBottom + SpacingTokens.Huge)
                    .height(SpacingTokens.ExtraLarge)
                    .clip(RoundedCornerShape(CornerTokens.Pill))
                    .background(shimmerBrush)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
            ) {
                Box(
                    modifier = Modifier
                        .size(ComponentTokens.SkeletonIconSize)
                        .clip(CircleShape)
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .width(SpacingTokens.ContentPaddingBottom + SpacingTokens.Large)
                        .height(ComponentTokens.SkeletonHeroTempHeight)
                        .clip(RoundedCornerShape(CornerTokens.Medium))
                        .background(shimmerBrush)
                )
            }

            Box(
                modifier = Modifier
                    .width(SpacingTokens.ContentPaddingBottom + SpacingTokens.Jumbo)
                    .height(ComponentTokens.SkeletonTitleHeight)
                    .clip(RoundedCornerShape(CornerTokens.Small))
                    .background(shimmerBrush)
            )

            Box(
                modifier = Modifier
                    .width(SpacingTokens.ContentPaddingBottom + SpacingTokens.Huge)
                    .height(ComponentTokens.SkeletonTextHeight)
                    .clip(RoundedCornerShape(CornerTokens.Pill))
                    .background(shimmerBrush)
            )

            // Editorial Weather Story Glass Skeleton
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ComponentTokens.SkeletonPillHeight + SpacingTokens.Large)
                    .luminousGlass(cornerRadius = CornerTokens.Large)
                    .padding(SpacingTokens.Base),
                contentAlignment = Alignment.CenterStart
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(ComponentTokens.SkeletonTextHeight)
                            .clip(RoundedCornerShape(CornerTokens.Small))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(ComponentTokens.SkeletonTextHeight)
                            .clip(RoundedCornerShape(CornerTokens.Small))
                            .background(shimmerBrush)
                    )
                }
            }
        }

        // 2. Hourly Forecast Luminous Glass Card Skeleton
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminousGlass(cornerRadius = CornerTokens.Large)
                .padding(SpacingTokens.Base),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .width(SpacingTokens.ContentPaddingBottom)
                        .height(ComponentTokens.SkeletonTextHeight)
                        .clip(RoundedCornerShape(CornerTokens.Pill))
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .width(SpacingTokens.ContentPaddingBottom + SpacingTokens.Jumbo)
                        .height(SpacingTokens.Large)
                        .clip(RoundedCornerShape(CornerTokens.Pill))
                        .background(shimmerBrush)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                repeat(5) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(SpacingTokens.Huge)
                                .height(ComponentTokens.SkeletonTextHeight)
                                .clip(RoundedCornerShape(CornerTokens.Small))
                                .background(shimmerBrush)
                        )
                        Box(
                            modifier = Modifier
                                .size(IconSizeTokens.Large)
                                .clip(CircleShape)
                                .background(shimmerBrush)
                        )
                        Box(
                            modifier = Modifier
                                .width(SpacingTokens.ExtraLarge)
                                .height(ComponentTokens.SkeletonTextHeight)
                                .clip(RoundedCornerShape(CornerTokens.Small))
                                .background(shimmerBrush)
                        )
                    }
                }
            }
        }

        // 3. 10-Day Outlook Glass Skeleton
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminousGlass(cornerRadius = CornerTokens.Large)
                .padding(SpacingTokens.Base),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
        ) {
            repeat(5) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .width(ComponentTokens.DailyItemDayLabelWidth)
                            .height(ComponentTokens.SkeletonTextHeight)
                            .clip(RoundedCornerShape(CornerTokens.Small))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .size(IconSizeTokens.Medium)
                            .clip(CircleShape)
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .width(ComponentTokens.TempBarWidth + SpacingTokens.Huge)
                            .height(ComponentTokens.SkeletonTextHeight)
                            .clip(RoundedCornerShape(CornerTokens.Pill))
                            .background(shimmerBrush)
                    )
                }
            }
        }

        // 4. Modular Bento Telemetry Skeleton (Wide Solar Card + 2-up Row)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.BentoSolarCardHeight)
                .luminousGlass(cornerRadius = CornerTokens.Large)
                .padding(SpacingTokens.Base)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(ComponentTokens.SkeletonTextHeight)
                        .clip(RoundedCornerShape(CornerTokens.Small))
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ComponentTokens.BentoGaugeHeight)
                        .clip(RoundedCornerShape(CornerTokens.Medium))
                        .background(shimmerBrush)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            repeat(2) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(ComponentTokens.BentoCardMinHeight)
                        .luminousGlass(cornerRadius = CornerTokens.Large)
                        .padding(SpacingTokens.Base)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.45f)
                                .height(ComponentTokens.SkeletonTextHeight)
                                .clip(RoundedCornerShape(CornerTokens.Small))
                                .background(shimmerBrush)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(ComponentTokens.SkeletonTitleHeight)
                                .clip(RoundedCornerShape(CornerTokens.Small))
                                .background(shimmerBrush)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(ComponentTokens.SkeletonTextHeight)
                                .clip(RoundedCornerShape(CornerTokens.Small))
                                .background(shimmerBrush)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(SpacingTokens.Huge))
    }
}
