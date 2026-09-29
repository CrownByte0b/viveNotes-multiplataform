package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android's `ViewSettingsTest`: zoom arithmetic every button hits in ordinary use. */
class ViewSettingsTest {

    @Test
    fun steppingMovesToTheNeighbouringPreset() {
        assertEquals(1.25f, ViewSettings.zoomStepUp(1f))
        assertEquals(0.75f, ViewSettings.zoomStepDown(1f))
    }

    @Test
    fun zoomingOutReachesTheExtendedCanvasOverview() {
        assertEquals(0.05f, ViewSettings.MIN_ZOOM)
        assertEquals(0.25f, ViewSettings.zoomStepDown(0.5f))
        assertEquals(0.1f, ViewSettings.zoomStepDown(0.25f))
        assertEquals(0.05f, ViewSettings.zoomStepDown(0.1f))
        assertEquals(0.1f, ViewSettings.zoomStepUp(0.05f))
    }

    @Test
    fun steppingFromAValueBetweenPresetsLandsOnTheNextOneEitherWay() {
        // Page Width and Ctrl+wheel produce exactly this: whatever fits, not a round number.
        assertEquals(1.25f, ViewSettings.zoomStepUp(1.13f))
        assertEquals(1f, ViewSettings.zoomStepDown(1.13f))
    }

    @Test
    fun steppingStopsAtTheEndsRatherThanWrappingOrStandingStill() {
        assertEquals(ViewSettings.MAX_ZOOM, ViewSettings.zoomStepUp(ViewSettings.MAX_ZOOM))
        assertEquals(ViewSettings.MIN_ZOOM, ViewSettings.zoomStepDown(ViewSettings.MIN_ZOOM))
    }

    @Test
    fun pageWidthDividesTheWindowByThePage() {
        assertEquals(0.5f, ViewSettings.fitZoom(viewportWidthDp = 600f, contentWidthDp = 1200f))
        assertEquals(1.5f, ViewSettings.fitZoom(viewportWidthDp = 1200f, contentWidthDp = 800f))
    }

    @Test
    fun pageWidthIsClampedToWhatTheZoomControlCanExpress() {
        assertEquals(ViewSettings.MAX_ZOOM, ViewSettings.fitZoom(4000f, 100f))
        assertEquals(ViewSettings.MIN_ZOOM, ViewSettings.fitZoom(100f, 4000f))
    }

    @Test
    fun pageWidthDoesNothingUntilTheCanvasHasBeenMeasured() {
        assertNull(ViewSettings.fitZoom(0f, 800f))
        assertNull(ViewSettings.fitZoom(800f, 0f))
    }

    @Test
    fun everyStepIsOneTheComboBoxOffers() {
        ViewSettings.ZOOM_STEPS.forEach { step ->
            assertTrue(ViewSettings.zoomStepUp(step) in ViewSettings.ZOOM_STEPS)
            assertTrue(ViewSettings.zoomStepDown(step) in ViewSettings.ZOOM_STEPS)
        }
    }

    @Test
    fun aFreshDeviceStartsAtFullSizeWithVerticalTabsFollowingTheTheme() {
        assertEquals(ViewSettings(1f, TabsLayout.Vertical, canvasDark = null), ViewSettings())
    }

    @Test
    fun anExplicitThemeSupersedesAnOlderCanvasOverrideButAChoiceInThatThemeStillWorks() {
        val oldDarkCanvas = ViewSettings(canvasDark = true)
        assertEquals(false, oldDarkCanvas.canvasDarkForTheme(themePreference = false, themeDark = false))
        assertEquals(true, oldDarkCanvas.canvasDarkForTheme(themePreference = null, themeDark = false))
        val chosenInLight = oldDarkCanvas.copy(canvasThemeDark = false)
        assertEquals(true, chosenInLight.canvasDarkForTheme(themePreference = false, themeDark = false))
        assertEquals(true, chosenInLight.copy(canvasDark = false)
            .canvasDarkForTheme(themePreference = true, themeDark = true))
    }

    @Test
    fun normalizingKeepsZoomInsideTheRange() {
        assertEquals(ViewSettings.MAX_ZOOM, ViewSettings(zoom = 40f).normalized().zoom)
        assertEquals(ViewSettings.MIN_ZOOM, ViewSettings(zoom = 0f).normalized().zoom)
        assertEquals(1f, ViewSettings(zoom = Float.NaN).normalized().zoom)
    }
}
