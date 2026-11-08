package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize

internal const val LASER_LAYER_TAG = "workspace-laser-layer"

/**
 * The laser pointer in hand (Draw → Laser pointer): a dot under the pen and a short trace that fades
 * behind it, over the page and the ruler alike. It is light rather than an object, so nothing it does
 * reaches the page — no stroke, no history step, no row.
 *
 * Composed above the ruler, and only while [active]; a finger that may not draw never reaches it,
 * since the canvas pans with that finger first.
 */
@Composable
internal expect fun LaserLayer(canvasSize: DpSize, zoom: Float, active: Boolean)
