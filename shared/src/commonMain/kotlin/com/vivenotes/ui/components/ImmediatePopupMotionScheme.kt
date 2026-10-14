package com.vivenotes.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.MotionScheme

/** SwingGraphics popup layers can miss draw-only frames during scale and opacity animations. */
internal class ImmediatePopupMotionScheme(private val delegate: MotionScheme) : MotionScheme by delegate {
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
}
