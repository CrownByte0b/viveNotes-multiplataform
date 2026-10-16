package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.model.*
import com.vivenotes.model.ink.*
import com.vivenotes.ui.canvas.*
import com.vivenotes.ui.ribbon.view.ViewRibbonTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.shell.canvasPalette
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkColorRenderingTest {
    @Test
    fun inkShapesAndTablesResolveTrueFalseAndLegacyColorsIdenticallyOnEveryCanvas() = runDesktopComposeUiTest(width = 300, height = 500) {
        val cases = listOf(true, false, null).flatMap { flag ->
            listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffe53935.toInt()).map { it to flag }
        } + (0x66ffffff to null)
        val ink = mutableStateOf(Color(0xff1b1b1b))
        val background = mutableStateOf(Color.White)
        val state = WorkspaceState.demo()
        setContent {
            Column {
                cases.forEachIndexed { index, (argb, follows) ->
                    Row {
                        Canvas(Modifier.size(80.dp, 40.dp).background(background.value).testTag("shape-$index")) {
                            drawDocumentShape(shape(argb, follows), originX = 0f, originY = 0f, canvasInk = ink.value)
                        }
                        Box(Modifier.size(80.dp, 40.dp).background(background.value).testTag("table-$index")) {
                            val table = newTable(1, 1, columnWidth = 80f, rowHeight = 40f, inkOnly = true,
                                borderWidth = 4f, borderArgb = argb, borderFollowsTheme = follows)
                            TableContent(table, listOf(40f), ink.value, state, RichTextColors(Color.Green, Color.Blue, Color.Gray),
                                FocusRequester(), {}, { _, _ -> }, { false })
                        }
                        Box(Modifier.size(80.dp, 40.dp).background(background.value).testTag("ink-$index")) {
                            InkLayer(InkPage("page-$index", listOf(VisibleInkStroke("stroke-$index", "marker", 1, 6f,
                                argb, follows, listOf(InkSample(10f, 20f), InkSample(70f, 20f))))),
                                DpSize(80.dp, 40.dp), ink.value)
                        }
                    }
                }
            }
        }
        for ((canvasInk, canvasBackground) in listOf(Color(0xff1b1b1b) to Color.White,
            Color(0xffe6e6e6) to Color(0xff1f1f1f), Color(0xff123456) to Color(0xfffafafa))) {
            runOnIdle { ink.value = canvasInk; background.value = canvasBackground }
            cases.forEachIndexed { index, (argb, follows) ->
                val automatic = follows == true || follows == null && argb in listOf(-1, 0xff000000.toInt())
                val expected = (if (automatic) canvasInk else Color(argb)).compositeOver(canvasBackground)
                for ((kind, x) in listOf("shape" to 10, "table" to 1, "ink" to 40)) {
                    val actual = onNodeWithTag("$kind-$index").captureToImage().toPixelMap()[x, 20]
                    assertTrue(abs(expected.red - actual.red) <= 1f / 255 && abs(expected.green - actual.green) <= 1f / 255 &&
                        abs(expected.blue - actual.blue) <= 1f / 255, "$kind argb=$argb follows=$follows canvas=$canvasInk expected=$expected actual=$actual")
                }
            }
        }
    }

    @Test
    fun switchBackgroundUsesThePageTextColorForAllAutomaticMarksWithoutEditingTheDocument() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val demo = WorkspaceState.demo()
        val table = newTable(1, 1, x = 200f, y = 200f, columnWidth = 100f, rowHeight = 50f,
            borderWidth = 4f, borderArgb = 0xff000000.toInt(), borderFollowsTheme = null, inkOnly = true)
        val shape = shape(0xff000000.toInt(), true).translated(200f, 300f)
        val pageId = demo.selectedPageId
        val document = PageDoc(style = PageStyle(hideTitle = true, ruleLines = RuleLines.None), outlines = listOf(table, shape))
        val initial = demo.copy(activeTab = RibbonTab.View).updatePage(pageId) {
            it.copy(document = document, ink = InkPage(pageId, listOf(VisibleInkStroke("automatic", "marker", 1, 6f,
                -1, true, listOf(InkSample(200f, 400f), InkSample(300f, 400f))))))
        }
        val state = mutableStateOf(initial)
        setContent { ViveNotesTheme(darkTheme = false) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        for ((index, dark) in listOf(false, true, false).withIndex()) {
            if (index > 0) onNodeWithTag(ViewRibbonTags.SwitchBackground).performClick()
            val expected = canvasPalette(document.style, dark).ink
            val pixels = onNodeWithTag(WorkspaceTestTags.PageCanvas).captureToImage().toPixelMap()
            assertEquals(expected, pixels[201, 225], "table follows text")
            assertEquals(expected, pixels[210, 320], "shape follows text")
            assertEquals(expected, pixels[250, 400], "ink follows text")
            runOnIdle {
                assertEquals(document, state.value.selectedPage!!.document)
                assertTrue(state.value.pendingInkEdits.isEmpty())
                assertTrue(state.value.structuralUndo.isEmpty())
            }
        }
    }

    private fun shape(argb: Int, follows: Boolean?) = Outline.Shape(id = "shape", kind = ShapeKind.Rectangle,
        segments = seedSegments(ShapeKind.Rectangle, 10f, 10f, 60f, 30f) { newId() },
        borderArgb = argb, borderFollowsTheme = follows, borderWidth = 4f).withRecomputedBounds()
}
