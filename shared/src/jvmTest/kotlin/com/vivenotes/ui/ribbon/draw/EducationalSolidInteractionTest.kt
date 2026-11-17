package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.*
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class EducationalSolidInteractionTest {
    private val kinds = listOf(ShapeKind.HollowCylinder, ShapeKind.Hemisphere, ShapeKind.Frustum)

    @Test
    fun allNineSolidsAreAvailableAndOnlyTheHollowCylinderOffersThickness() = runDesktopComposeUiTest(width = 700, height = 1100) {
        val shape = mutableStateOf(ShapeToolSettings(kind = ShapeKind.HollowCylinder))
        setContent { ViveNotesTheme { ShapeKit(shape.value, PenColors, Color.Black, {}) { shape.value = it } } }
        ShapeKind.onPage(PAGE_SOLID).forEach { onNodeWithTag(ShapeMenuTags.kind(it)).assertIsDisplayed() }
        onNodeWithTag(ShapeMenuTags.kind(ShapeKind.Rectangle)).assertDoesNotExist()
        kinds.forEach { kind ->
            onNodeWithTag(ShapeMenuTags.kind(kind)).performClick().assertIsSelected()
            runOnIdle { assertEquals(kind, shape.value.kind) }
            if (kind.hasWallThickness) onNodeWithText("Wall thickness (%)").assertIsDisplayed()
            else onNodeWithText("Wall thickness (%)").assertDoesNotExist()
        }
    }

    @Test
    fun thicknessUsesFivePercentStepsAndSurvivesKindChangesWithoutChangingTheBorder() = runDesktopComposeUiTest(width = 700, height = 1100) {
        val shape = mutableStateOf(ShapeToolSettings(kind = ShapeKind.HollowCylinder, borderWidth = 3))
        setContent { ViveNotesTheme { ShapeKit(shape.value, PenColors, Color.Black, {}) { shape.value = it } } }
        onNodeWithTag("${ShapeMenuTags.WallThickness}-plus").performClick()
        runOnIdle { assertEquals(30, shape.value.wallThickness); assertEquals(3, shape.value.borderWidth) }
        onNodeWithTag(ShapeMenuTags.kind(ShapeKind.Hemisphere)).performClick()
        onNodeWithTag(ShapeMenuTags.WallThickness).assertDoesNotExist()
        onNodeWithTag(ShapeMenuTags.kind(ShapeKind.HollowCylinder)).performClick()
        runOnIdle { assertEquals(30, shape.value.wallThickness) }
        onNodeWithTag(ShapeMenuTags.WallThickness).performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        onNodeWithTag("${ShapeMenuTags.WallThickness}-plus").assertIsNotEnabled()
        onNodeWithTag(ShapeMenuTags.WallThickness).performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        onNodeWithTag("${ShapeMenuTags.WallThickness}-minus").assertIsNotEnabled()
    }

    @Test
    fun theRibbonPlacesAHollowCylinderAndUndoRestoresThePage() = place(ShapeKind.HollowCylinder)

    @Test
    fun theRibbonPlacesAHemisphereAndUndoRestoresThePage() = place(ShapeKind.Hemisphere)

    @Test
    fun theRibbonPlacesAFrustumAndUndoRestoresThePage() = place(ShapeKind.Frustum)

    private fun place(kind: ShapeKind) = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw,
            shapeSettings = ShapeToolSettings(kind = ShapeKind.HollowCylinder, wallThickness = 40))
        val state = mutableStateOf(base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList())) })
        val saved = mutableListOf<ShapeToolSettings>()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) },
            onShapeSettingsChange = saved::add) } }
        onNodeWithTag(DrawRibbonTags.ShapeKit).performClick()
        onNodeWithTag(ShapeMenuTags.kind(kind)).performClick()
        onNodeWithTag(ShapeMenuTags.kind(kind)).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput {
            updatePointerTo(Offset(100f, 220f)); press(); moveTo(Offset(340f, 400f), delayMillis = 80); release()
        }
        runOnIdle {
            val shape = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().single()
            assertEquals(kind, shape.kind)
            assertEquals(40, saved.last().wallThickness)
            val expected = seedSegments(kind, shape.x, shape.y, shape.x + shape.width, shape.y + shape.height, 40) { "" }
            assertEquals(expected.map { it.fillContour }, shape.segments.map { it.fillContour })
            expected.zip(shape.segments).forEach { (reference, stored) ->
                val points = reference.polyline()
                val actual = stored.polyline()
                assertEquals(points.size, actual.size)
                points.indices.forEach { index -> assertEquals(points[index], actual[index], .02f) }
            }
        }
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        runOnIdle { assertTrue(state.value.selectedPage!!.document.outlines.isEmpty()) }
    }
}
