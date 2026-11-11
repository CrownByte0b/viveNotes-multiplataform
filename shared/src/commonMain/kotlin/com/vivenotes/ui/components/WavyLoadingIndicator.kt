package com.vivenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The M3 Expressive circular wave, with its stroke and wavelength scaled for compact controls. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WavyLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = WavyProgressIndicatorDefaults.CircularContainerSize,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = color.copy(alpha = .2f),
) {
    val scale = size / WavyProgressIndicatorDefaults.CircularContainerSize
    val stroke = WavyProgressIndicatorDefaults.circularIndicatorStroke
    val trackStroke = WavyProgressIndicatorDefaults.circularTrackStroke
    CircularWavyProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        trackColor = trackColor,
        stroke = Stroke(width = stroke.width * scale, cap = stroke.cap),
        trackStroke = Stroke(width = trackStroke.width * scale, cap = trackStroke.cap),
        gapSize = WavyProgressIndicatorDefaults.CircularIndicatorTrackGapSize * scale,
        wavelength = WavyProgressIndicatorDefaults.CircularWavelength * scale,
    )
}

/** Keeps the wave legible over arbitrary page colours and downloaded pictures. */
@Composable
internal fun ContainedWavyLoadingIndicator(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .progressSemantics(),
        contentAlignment = Alignment.Center,
    ) {
        WavyLoadingIndicator(modifier = Modifier.clearAndSetSemantics {}, size = 32.dp, color = ink)
    }
}
