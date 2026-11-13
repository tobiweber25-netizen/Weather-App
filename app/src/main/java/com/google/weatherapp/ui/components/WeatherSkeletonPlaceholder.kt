package com.google.weatherapp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.google.weatherapp.theme.*

/**
 * Frosted glass skeleton shimmer placeholder shown during initial cold start loading.
 */
@Composable
fun WeatherSkeletonPlaceholder(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "skeleton_transition")
    val alpha by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )

    val placeholderColor = PureWhite.copy(alpha = alpha)
    val cardColor = GlassCardBackgroundLight.copy(alpha = alpha * 0.65f)
    val borderColor = GlassCardBorderLight.copy(alpha = alpha)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.Base, vertical = SpacingTokens.Small),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
    ) {
        // 1. Hero Weather Card Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.HeroCardMinHeight)
                .clip(RoundedCornerShape(CornerTokens.Large))
                .background(cardColor)
                .border(BorderTokens.Hairline, borderColor, RoundedCornerShape(CornerTokens.Large))
                .padding(SpacingTokens.ExtraLarge)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(SpacingTokens.ContentPaddingBottom)
                        .height(ComponentTokens.SkeletonTextHeight)
                        .clip(RoundedCornerShape(CornerTokens.Pill))
                        .background(placeholderColor)
                )

                Spacer(modifier = Modifier.height(SpacingTokens.Medium))

                Box(
                    modifier = Modifier
                        .size(ComponentTokens.SkeletonIconSize)
                        .clip(RoundedCornerShape(CornerTokens.Pill))
                        .background(placeholderColor)
                )

                Spacer(modifier = Modifier.height(SpacingTokens.Small))

                Box(
                    modifier = Modifier
                        .width(SpacingTokens.ContentPaddingBottom)
                        .height(ComponentTokens.SkeletonTextHeight)
                        .clip(RoundedCornerShape(CornerTokens.Small))
                        .background(placeholderColor)
                )

                Spacer(modifier = Modifier.height(SpacingTokens.ExtraSmall))

                Box(
                    modifier = Modifier
                        .width(SpacingTokens.Jumbo)
                        .height(ComponentTokens.SkeletonTitleHeight)
                        .clip(RoundedCornerShape(CornerTokens.Small))
                        .background(placeholderColor)
                )
            }
        }

        // 2. Alert Pill Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.SkeletonPillHeight)
                .clip(RoundedCornerShape(CornerTokens.Large))
                .background(cardColor)
                .border(BorderTokens.Hairline, borderColor, RoundedCornerShape(CornerTokens.Large))
                .padding(horizontal = SpacingTokens.Base),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(ComponentTokens.SkeletonTextHeight)
                    .clip(RoundedCornerShape(CornerTokens.Small))
                    .background(placeholderColor)
            )
        }

        // 3. Hourly Forecast Strip Skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .width(ComponentTokens.HourlyCardWidth)
                        .height(ComponentTokens.HourlyCardHeight)
                        .clip(RoundedCornerShape(CornerTokens.Medium))
                        .background(cardColor)
                        .border(BorderTokens.Hairline, borderColor, RoundedCornerShape(CornerTokens.Medium))
                        .padding(SpacingTokens.Small)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(ComponentTokens.SkeletonTextHeight)
                                .clip(RoundedCornerShape(CornerTokens.Small))
                                .background(placeholderColor)
                        )
                        Box(
                            modifier = Modifier
                                .size(IconSizeTokens.Medium)
                                .clip(RoundedCornerShape(CornerTokens.Pill))
                                .background(placeholderColor)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(ComponentTokens.SkeletonTextHeight)
                                .clip(RoundedCornerShape(CornerTokens.Small))
                                .background(placeholderColor)
                        )
                    }
                }
            }
        }

        // 4. Bento Telemetry Grid Skeleton (2x2)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            repeat(2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
                ) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(ComponentTokens.BentoCardMinHeight)
                                .clip(RoundedCornerShape(CornerTokens.Large))
                                .background(cardColor)
                                .border(BorderTokens.Hairline, borderColor, RoundedCornerShape(CornerTokens.Large))
                                .padding(SpacingTokens.Medium)
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
                                        .background(placeholderColor)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.7f)
                                        .height(ComponentTokens.SkeletonTitleHeight)
                                        .clip(RoundedCornerShape(CornerTokens.Small))
                                        .background(placeholderColor)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.55f)
                                        .height(ComponentTokens.SkeletonTextHeight)
                                        .clip(RoundedCornerShape(CornerTokens.Small))
                                        .background(placeholderColor)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(SpacingTokens.Huge))
    }
}
