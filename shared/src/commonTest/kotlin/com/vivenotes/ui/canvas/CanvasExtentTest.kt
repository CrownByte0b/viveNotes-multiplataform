package com.vivenotes.ui.canvas

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasExtentTest {

    private val density = Density(1f)
    private val window = DpSize(1000.dp, 800.dp)
    private val a6 = DpSize((PaperSize.A6.widthInches * PageStyle.DP_PER_INCH).dp,
        (PaperSize.A6.heightInches * PageStyle.DP_PER_INCH).dp)

    // --- the page ---------------------------------------------------------------------------------

    @Test
    fun anInfinitePageHasNoSheetAndIsNeverBound() {
        val extent = PageExtent.of(PageStyle(), contentRight = 300f, contentBottom = 400f)
        assertNull(extent.sheet)
        assertFalse(extent.bound)
        assertEquals(DpSize(PageExtent.CanvasMinWidth, PageExtent.CanvasMinHeight), extent.page)
    }

    @Test
    fun aSheetTheContentFitsInsideBindsThePage() {
        val extent = PageExtent.of(PageStyle(paper = PaperSize.A6), contentRight = 300f, contentBottom = 400f)
        assertTrue(extent.bound)
        assertEquals(a6, extent.page)
        assertEquals(DpSize(a6.width + PageExtent.TrailingWidth, a6.height + PageExtent.TrailingHeight), extent.room)
    }

    @Test
    fun contentPastTheSheetTurnsItIntoAGuideInsteadOfClippingIt() {
        val past = a6.height.value + 200f
        val extent = PageExtent.of(PageStyle(paper = PaperSize.A6), contentRight = 300f, contentBottom = past)
        assertFalse(extent.bound)
        assertEquals(a6, extent.sheet)
        assertEquals(past, extent.page.height.value)
    }

    @Test
    fun landscapeTurnsTheSheetOnItsSide() {
        val extent = PageExtent.of(PageStyle(paper = PaperSize.A6, orientation = Orientation.Landscape), 0f, 0f)
        assertEquals(DpSize(a6.height, a6.width), extent.sheet)
    }

    @Test
    fun theTitleBandCountsAsContentUnlessTheTitleIsHidden() {
        val tiny = PageStyle(paper = PaperSize.Custom,
            customPaper = com.vivenotes.model.PaperDimensions(1f, 0.5f))
        assertFalse(PageExtent.of(tiny, 0f, 0f).bound)
        assertTrue(PageExtent.of(tiny.copy(hideTitle = true), 0f, 0f).bound)
    }

    // --- the endless canvas ----------------------------------------------------------------------

    @Test
    fun anInfiniteCanvasKeepsAScreenfulAheadOfTheFurthestScroll() {
        val extent = PageExtent.of(PageStyle(), 0f, 0f)
        val atTop = extent.canvasSize(window, reachedX = 1, reachedY = 1, density, zoom = 1f)
        val scrolled = extent.canvasSize(window, reachedX = 1, reachedY = 4, density, zoom = 1f)
        assertEquals(1600f, atTop.height.value)
        assertEquals(4000f, scrolled.height.value)
        assertEquals(atTop.width, scrolled.width)
    }

    @Test
    fun aBoundSheetStopsAtItsSurroundHoweverFarItIsScrolled() {
        val extent = PageExtent.of(PageStyle(paper = PaperSize.A6), 0f, 0f)
        val size = extent.canvasSize(window, reachedX = 9, reachedY = 9, density, zoom = 1f)
        assertEquals(DpSize(maxOf(extent.room.width, window.width), maxOf(extent.room.height, window.height)), size)
    }

    @Test
    fun onlyTheSheetTakesNewContentWhileItBindsThePage() {
        val style = PageStyle(paper = PaperSize.A6, hideTitle = true)
        val bound = PageExtent.of(style, 0f, 0f)
        assertTrue(bound.canPlaceAt(style, 120f, 200f))
        assertFalse(bound.canPlaceAt(style, a6.width.value + 80f, 200f))
        val infinite = PageExtent.of(PageStyle(), 0f, 0f)
        assertTrue(infinite.canPlaceAt(PageStyle(), 50_000f, 90_000f))
        assertFalse(infinite.canPlaceAt(PageStyle(), 300f, PageStyle.TITLE_BAND_DP / 2f))
    }

    // --- Android CanvasConstraintsTest ---------------------------------------------------------------

    @Test
    fun fivePercentZoomRemainsRepresentableOnADenseScreen() {
        val dense = Density(2f)
        val size = with(dense) {
            limitEmptyCanvas(DpSize(1_200.dp, 1_200.dp), DpSize(25_600.dp, 20_000.dp), 0.05f)
        }
        assertBothMeasurementStagesAreRepresentable(dense, size, 0.05f)
    }

    @Test
    fun fivePercentZoomPreservesANaturallyNarrowLongPage() {
        val dense = Density(2f)
        val size = with(dense) {
            limitEmptyCanvas(DpSize(1_200.dp, 40_000.dp), DpSize(25_600.dp, 40_000.dp), 0.05f)
        }
        assertTrue(size.width >= 1_200.dp)
        assertEquals(40_000f, size.height.value, 0f)
        assertBothMeasurementStagesAreRepresentable(dense, size, 0.05f)
    }

    @Test
    fun theEmptyAreaLimitAlsoPreservesALongHorizontalPage() {
        val dense = Density(2f)
        val size = with(dense) {
            limitEmptyCanvas(DpSize(40_000.dp, 1_200.dp), DpSize(40_000.dp, 25_600.dp), 0.05f)
        }
        assertEquals(40_000f, size.width.value, 0f)
        assertTrue(size.height >= 1_200.dp)
        assertBothMeasurementStagesAreRepresentable(dense, size, 0.05f)
    }

    @Test
    fun maximumZoomRemainsRepresentableOnADenseScreen() {
        val dense = Density(2f)
        val size = with(dense) {
            limitEmptyCanvas(DpSize(1_200.dp, 1_200.dp), DpSize(25_600.dp, 20_000.dp), 4f)
        }
        assertBothMeasurementStagesAreRepresentable(dense, size, 4f)
    }

    @Test
    fun wideAndTallDocumentContentIsNeverClampedToTheEmptyAreaBudget() {
        val required = DpSize(8_061.dp, 19_285.dp)
        val size = with(Density(2f)) {
            limitEmptyCanvas(required, DpSize(required.width + 200.dp, required.height + 320.dp), 0.05f)
        }
        assertEquals(required, size)
    }

    /** Constructing these is the assertion: Constraints throws when the pair cannot be packed. */
    private fun assertBothMeasurementStagesAreRepresentable(density: Density, size: DpSize, zoom: Float) {
        val widthPx = with(density) { size.width.roundToPx() }
        val heightPx = with(density) { size.height.roundToPx() }
        Constraints.fixed(widthPx, heightPx)
        Constraints.fixed((widthPx * zoom).roundToInt(), (heightPx * zoom).roundToInt())
    }
}
