package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.vivenotes.data.TextClipboard
import com.vivenotes.data.rememberTextClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.math.*
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.theme.ViveNotesTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

/** Ports the Android RecognitionPanelTest interactions and checks the supplied mathp1/mathp2 layout. */
@OptIn(ExperimentalTestApi::class)
class MathPaneReferenceTest {
    private val redraw = mutableIntStateOf(0)

    private class Math : DrawMath {
        val calls = mutableListOf<Pair<String, String>>()
        var fail = false
        var noDecimal = false
        override val engine = object : MathEngine {
            override suspend fun analyze(latex: String) = MathAnalysis(normalizedLatex = latex,
                summary = "Expression", variables = listOf("x"), actions = listOf(
                    MathAction("simplify", "Simplify"), MathAction("expand", "Expand"),
                    MathAction("differentiate", "Differentiate"), MathAction("integrate", "Integrate"),
                    MathAction("graph", "Graph")))
            override suspend fun execute(latex: String, actionId: String): MathOperationResult {
                calls += latex to actionId
                if (fail) error("Unable to process $latex")
                return if (actionId == "graph") MathOperationResult(title = "Graph", latex = latex,
                    graph = MathGraph("x", "y", listOf(-1.0, 0.0, 1.0), listOf(1.0, 0.0, 1.0)))
                else MathOperationResult(title = "Simplified", latex = latex, decimal = if (noDecimal) null else "1.41421356237")
            }
        }
        override suspend fun readFormula(ink: InkPage) = SOURCE
    }

    @Test
    fun developmentPanelMatchesTheReferencePaletteDimensionsAndWrappedActions() = runDesktopComposeUiTest(width = 640, height = 1500) {
        val math = Math()
        setContent { ViveNotesTheme(darkTheme = true) {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.fillMaxSize().drawWithContent { redraw.intValue; drawContent() }) {
                    MathPane(SOURCE, math, showInterpretation = true) {}
                }
            }
        } }
        mainClock.advanceTimeBy(300)
        onNodeWithText("Recognition").assertIsDisplayed()
        onNodeWithText("LaTeX").assertIsDisplayed()
        onNodeWithText("Preview").assertIsDisplayed()
        onNodeWithText("Expression · Variables: x").assertIsDisplayed()
        val pane = onNodeWithTag(ToolPaneTags.Pane)
        assertEquals(640f, pane.fetchSemanticsNode().boundsInRoot.width)
        assertTrue(onNodeWithTag(MathPaneTags.Source).fetchSemanticsNode().boundsInRoot.height < 60f)
        assertEquals(192f, onNodeWithTag(MathPaneTags.Preview).fetchSemanticsNode().boundsInRoot.height)
        assertEquals(163f, onNodeWithTag(MathPaneTags.Interpretation).fetchSemanticsNode().boundsInRoot.height)
        val simplify = onNodeWithTag(MathPaneTags.action("simplify")).fetchSemanticsNode().boundsInRoot
        val expand = onNodeWithTag(MathPaneTags.action("expand")).fetchSemanticsNode().boundsInRoot
        val differentiate = onNodeWithTag(MathPaneTags.action("differentiate")).fetchSemanticsNode().boundsInRoot
        val integrate = onNodeWithTag(MathPaneTags.action("integrate")).fetchSemanticsNode().boundsInRoot
        assertEquals(simplify.top, expand.top)
        assertEquals(simplify.top, differentiate.top)
        assertTrue(integrate.top > simplify.bottom)
        assertTrue(onNodeWithTag(MathPaneTags.Result).fetchSemanticsNode().boundsInRoot.top > integrate.bottom)
        save(onRoot(), "math-panel-development.png")
        onNodeWithTag(MathPaneTags.Decimal).performScrollTo().assertIsDisplayed()
        save(onRoot(), "math-panel-development-result.png")
        onNodeWithText("LaTeX").performScrollTo()
        assertEquals(Color(0xFF2B2B2B), pane.captureToImage().toPixelMap()[1, 1])
        assertEquals(Color(0xFF1D1B20), onNodeWithTag(MathPaneTags.Preview).captureToImage().toPixelMap()[1, 1])
        fun buttonColor(tag: String, expected: Color) {
            val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
            assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2 - 18])
        }
        buttonColor(MathPaneTags.Copy, Color(0xFF007FFF))
        buttonColor(MathPaneTags.action("simplify"), Color(0xFFFF8000))
    }

    @Test
    fun releaseHidesInterpretationAndKeepsEveryActionAndGraphAvailable() = runDesktopComposeUiTest(width = 640, height = 1500) {
        val math = Math()
        setContent { ViveNotesTheme(darkTheme = true) {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.fillMaxSize().drawWithContent { redraw.intValue; drawContent() }) {
                    MathPane(SOURCE, math) {}
                }
            }
        } }
        mainClock.advanceTimeBy(300)
        onNodeWithText("Understood as").assertDoesNotExist()
        onNodeWithText("Expression · Variables: x").assertDoesNotExist()
        onNodeWithTag(MathPaneTags.Interpretation).assertDoesNotExist()
        save(onRoot(), "math-panel-release.png")
        for (id in listOf("simplify", "expand", "differentiate", "integrate", "graph")) {
            onNodeWithTag(MathPaneTags.action(id)).performScrollTo().assertIsEnabled().performClick()
            waitUntil { math.calls.lastOrNull()?.second == id }
        }
        onNodeWithTag(MathPaneTags.Result).assertExists()
        onNodeWithTag("draw-math-graph").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("simplify", "simplify", "expand", "differentiate", "integrate", "graph"), math.calls.map { it.second })
        save(onRoot(), "math-panel-graph.png")
    }

    @Test
    fun copyPublishesEditedPlainLatexToTheDesktopClipboardAndResetsItsFeedback() = runDesktopComposeUiTest(width = 500, height = 900) {
        lateinit var clipboard: TextClipboard
        setContent { ViveNotesTheme {
            clipboard = rememberTextClipboard()
            MathPane(SOURCE, Math()) {}
        } }
        onNodeWithTag(MathPaneTags.Copy).performClick()
        onNodeWithTag(MathPaneTags.Copied).assertIsDisplayed()
        runOnIdle { assertEquals(SOURCE, clipboard.getText()?.text) }
        onNodeWithTag(MathPaneTags.Source).performTextReplacement("\\frac{1}{2}")
        onNodeWithTag(MathPaneTags.Copied).assertDoesNotExist()
        onNodeWithTag(MathPaneTags.Copy).performClick()
        runOnIdle { assertEquals("\\frac{1}{2}", clipboard.getText()?.text) }
        onNodeWithTag(MathPaneTags.Source).performTextClearance()
        onNodeWithTag(MathPaneTags.Copy).assertIsNotEnabled()
        onNodeWithTag(MathPaneTags.Copied).assertDoesNotExist()
        onNodeWithTag(MathPaneTags.Result).assertDoesNotExist()
    }

    @Test
    fun decimalToggleFollowsTheResultAndKeepsItsChoiceAcrossSourceEdits() = runDesktopComposeUiTest(width = 500, height = 1000) {
        val math = Math()
        setContent { ViveNotesTheme { MathPane(SOURCE, math) {} } }
        mainClock.advanceTimeBy(300)
        onNodeWithText("Copy result").assertDoesNotExist()
        onNodeWithTag(MathPaneTags.Decimal).performScrollTo().assertIsOff().performClick().assertIsOn()
        onNodeWithTag(MathPaneTags.Result).assertContentDescriptionEquals("1.41421356237")
        runOnIdle { math.noDecimal = true }
        onNodeWithTag(MathPaneTags.Source).performScrollTo().performTextReplacement("48")
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.Decimal).assertDoesNotExist()
        onNodeWithTag(MathPaneTags.Result).assertContentDescriptionEquals("48")
        runOnIdle { math.noDecimal = false }
        onNodeWithTag(MathPaneTags.Source).performTextReplacement(SOURCE)
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.Decimal).performScrollTo().assertIsOn().performClick().assertIsOff()
        onNodeWithTag(MathPaneTags.Result).assertContentDescriptionEquals(SOURCE)
    }

    @Test
    fun longFormulasScrollAtTheReadableSizeAndShortWindowsKeepActionsAndCloseReachable() = runDesktopComposeUiTest(width = 600, height = 350) {
        var closed = false
        val long = (1..160).joinToString("+") { "x_{$it}" }
        setContent { ViveNotesTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MathPane(long, Math(), showInterpretation = true, onClose = { closed = true })
            }
        } }
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.Preview).performScrollTo()
        val scroll = onNodeWithTag(MathPaneTags.Preview).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(scroll.maxValue() > 0f, "A long formula scrolls after reaching the 40% type-size floor")
        onNodeWithTag(MathPaneTags.action("integrate")).performScrollTo().assertIsDisplayed().performClick()
        onNodeWithTag(MathPaneTags.Decimal).performScrollTo().assertIsDisplayed().performClick()
        onNodeWithTag(ToolPaneTags.Close).assertIsDisplayed().performClick()
        runOnIdle { assertTrue(closed) }
    }

    @Test
    fun actionFailuresStayRetryableAndDebugEventsNeverContainTheFormula() {
        for (enabled in listOf(false, true)) runDesktopComposeUiTest(width = 500, height = 1000) {
            val math = Math()
            val lines = mutableListOf<String>()
            val log = DebugLog(enabled, lines::add)
            setContent { ViveNotesTheme { MathPane(SOURCE, math, log = log) {} } }
            mainClock.advanceTimeBy(300)
            runOnIdle { math.fail = true }
            onNodeWithTag(MathPaneTags.action("expand")).performClick()
            onNodeWithTag(MathPaneTags.Error).assertTextEquals("Unable to process $SOURCE")
            onNodeWithTag(MathPaneTags.action("expand")).assertIsEnabled()
            runOnIdle { math.fail = false }
            onNodeWithTag(MathPaneTags.action("expand")).performClick()
            onNodeWithTag(MathPaneTags.Error).assertDoesNotExist()
            onNodeWithTag(MathPaneTags.Copy).performClick()
            onNodeWithTag(MathPaneTags.Decimal).performScrollTo().performClick()
            runOnIdle {
                if (enabled) {
                    assertTrue(lines.any { "action failed" in it })
                    assertTrue(lines.any { "action completed" in it })
                    assertTrue(lines.any { "LaTeX copied" in it })
                    assertTrue(lines.any { "decimal display=true" in it })
                } else assertTrue(lines.isEmpty())
                assertTrue(lines.none { SOURCE in it })
            }
        }
    }

    private fun ComposeUiTest.save(node: SemanticsNodeInteraction, name: String) {
        runOnIdle { redraw.intValue++ }
        waitForIdle()
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, name).writeBytes(it.bytes) }
        }
    }

    private companion object { const val SOURCE = "\\sqrt { x ^ { 2 } + 1 }" }
}
