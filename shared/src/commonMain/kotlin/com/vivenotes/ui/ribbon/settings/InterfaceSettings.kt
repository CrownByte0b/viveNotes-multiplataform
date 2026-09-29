package com.vivenotes.ui.ribbon.settings

import androidx.compose.ui.unit.Density

/** Independent controls for the app's logical DPI, component dimensions, and text. */
data class InterfaceSettings(
    val displayScale: Float = 0.75f,
    val uiScale: Float = 1f,
    val fontScale: Float = 1f,
    /** Null follows the desktop's current color scheme until the user chooses a theme. */
    val darkTheme: Boolean? = null,
) {
    companion object {
        val DisplayScaleRange = 0.5f..2.5f
        val UiScaleRange = 0.5f..1.5f
        val FontScaleRange = 0.5f..1.8f
    }

    fun normalized(): InterfaceSettings = copy(
        displayScale = displayScale.takeIf { it.isFinite() }?.coerceIn(DisplayScaleRange) ?: 0.75f,
        uiScale = uiScale.takeIf { it.isFinite() }?.coerceIn(UiScaleRange) ?: 1f,
        fontScale = fontScale.takeIf { it.isFinite() }?.coerceIn(FontScaleRange) ?: 1f,
    )

    /** Display scale affects everything; UI size affects dp, font size affects sp. */
    fun density(base: Density): Density {
        val value = normalized()
        return Density(
            density = base.density * value.displayScale * value.uiScale,
            fontScale = base.fontScale * value.fontScale / value.uiScale,
        )
    }

    /** Document measurements stay fixed when only the surrounding controls change size. */
    fun documentDensity(base: Density): Density = copy(uiScale = 1f).density(base)
}
