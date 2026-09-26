package com.vivenotes.ui.ribbon.settings

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

class InterfaceSettingsTest {
    @Test
    fun displayUiAndFontControlsStayIndependent() {
        val base = Density(2f, 1.25f)
        val uiOnly = InterfaceSettings(displayScale = 1f, uiScale = 1.4f).density(base)
        assertEquals(2.8f, uiOnly.density, 0.0001f)
        assertEquals(2.5f, uiOnly.density * uiOnly.fontScale, 0.0001f)
        assertEquals(2f, InterfaceSettings(displayScale = 1f, uiScale = 1.4f).documentDensity(base).density, 0.0001f)

        val fontOnly = InterfaceSettings(displayScale = 1f, fontScale = 1.6f).density(base)
        assertEquals(2f, fontOnly.density, 0.0001f)
        assertEquals(4f, fontOnly.density * fontOnly.fontScale, 0.0001f)

        val displayOnly = InterfaceSettings(displayScale = 2f).density(base)
        assertEquals(4f, displayOnly.density, 0.0001f)
        assertEquals(5f, displayOnly.density * displayOnly.fontScale, 0.0001f)
    }

    @Test
    fun invalidAndOutOfRangeValuesAreSafe() {
        assertEquals(0.75f, InterfaceSettings().displayScale)
        assertEquals(InterfaceSettings(), InterfaceSettings(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).normalized())
        assertEquals(InterfaceSettings(0.5f, 1.5f, 1.8f), InterfaceSettings(0f, 99f, 99f).normalized())
        assertEquals(InterfaceSettings(0.5f, 0.5f, 0.5f), InterfaceSettings(0.2f, 0.2f, 0.2f).normalized())
    }
}
