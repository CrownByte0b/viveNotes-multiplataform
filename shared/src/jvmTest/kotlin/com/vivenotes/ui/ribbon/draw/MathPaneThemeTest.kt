package com.vivenotes.ui.ribbon.draw

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.localization.MathText
import com.vivenotes.localization.Strings
import com.vivenotes.math.MathAction
import com.vivenotes.math.MathAnalysis
import com.vivenotes.math.MathEngine
import com.vivenotes.math.MathOperationResult
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.ink.InkSelectionBounds
import com.vivenotes.ui.components.assertIsIndeterminateWavyBar
import com.vivenotes.ui.canvas.INK_LAYER_TAG
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.InkSettings
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MathPaneThemeTest {
    @Test
    fun handwritingStatusUsesThePanelForegroundAcrossThemeChanges() = runDesktopComposeUiTest(600, 800) {
        var dark by mutableStateOf(false)
        val read = CompletableDeferred<String>()
        val math = object : DrawMath {
            override val engine: MathEngine get() = error("Recognition has not finished")
            override suspend fun readFormula(ink: InkPage, lines: List<Outline.Shape>) = read.await()
        }
        val selection = object : InkSelection {
            override val page = InkPage("selected", emptyList())
            override val bounds = InkSelectionBounds(0f, 0f, 20f, 20f)
            override val targetIds = emptySet<String>()
            override fun forRecognition() = page
            override fun move(dx: Float, dy: Float) = null
            override fun delete() = null
        }
        val request = InkMathRequest(selection)
        setContent {
            ViveNotesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    InkMathPane(request, math) {}
                }
            }
        }
        for (theme in listOf(false, true, false)) {
            runOnIdle { dark = theme }
            onNodeWithText(Strings.reading_handwriting).assertForeground(panelForeground(theme))
            onNodeWithTag("ink-math-loading").assertIsIndeterminateWavyBar()
        }
    }

    @Test
    fun analysisPreviewAndResultMessagesFollowThePanelTheme() = runDesktopComposeUiTest(600, 1100) {
        var dark by mutableStateOf(false)
        val analyzed = CompletableDeferred<MathAnalysis>()
        val math = object : DrawMath {
            override val engine = object : MathEngine {
                override suspend fun analyze(latex: String) = analyzed.await()
                override suspend fun execute(latex: String, actionId: String) =
                    MathOperationResult(title = "Result", latex = latex, message = "No real solution")
            }
            override suspend fun readFormula(ink: InkPage, lines: List<Outline.Shape>) = "x+1"
        }
        setContent {
            ViveNotesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    MathPane("x+1", math) {}
                }
            }
        }
        mainClock.advanceTimeBy(300)
        for (theme in listOf(false, true, false)) {
            runOnIdle { dark = theme }
            onNodeWithTag(MathPaneTags.Busy).assertIsIndeterminateWavyBar()
            onNodeWithTag(MathPaneTags.Preview).assertPaints(panelForeground(theme))
        }
        runOnIdle { analyzed.complete(MathAnalysis(actions = listOf(MathAction("simplify", "Simplify")))) }
        onNodeWithTag(MathPaneTags.Busy).assertDoesNotExist()
        for (theme in listOf(false, true, false)) {
            runOnIdle { dark = theme }
            onNodeWithText(MathText.translate("No real solution")).performScrollTo().assertForeground(panelForeground(theme))
            onNodeWithTag(MathPaneTags.Result).assertPaints(panelForeground(theme))
        }
    }

    @Test
    fun calculatorRecognitionStatusFollowsThemeChanges() = runDesktopComposeUiTest(700, 700) {
        var dark by mutableStateOf(false)
        val read = CompletableDeferred<String>()
        val math = object : DrawMath {
            override val engine: MathEngine get() = error("Recognition has not finished")
            override suspend fun readFormula(ink: InkPage, lines: List<Outline.Shape>) = read.await()
        }
        setContent {
            ViveNotesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    FloatingCalculator(InkSettings(), 0, false, math, {}, {})
                }
            }
        }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(20f, 80f)); press(); moveTo(Offset(200f, 80f), delayMillis = 80); release()
        }
        onNodeWithTag(CalculatorTags.Equals).performClick()
        for (theme in listOf(false, true, false)) {
            runOnIdle { dark = theme }
            onNodeWithText(Strings.reading_handwriting).assertForeground(panelForeground(theme))
            onNodeWithTag(CalculatorTags.Equals).assertIsNotEnabled()
        }
    }

    private fun panelForeground(dark: Boolean) = if (dark) Color(0xFFE6E6E6) else Color(0xFF1B1B1B)

    private fun SemanticsNodeInteraction.assertForeground(expected: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(expected, layouts.single().layoutInput.style.color)
        assertPaints(expected)
    }

    private fun SemanticsNodeInteraction.assertPaints(expected: Color) {
        val pixels = assertIsDisplayed().captureToImage().toPixelMap()
        assertTrue((0 until pixels.height).any { y -> (0 until pixels.width).any { x ->
            val actual = pixels[x, y]
            abs(actual.red - expected.red) < .02f && abs(actual.green - expected.green) < .02f &&
                abs(actual.blue - expected.blue) < .02f
        } }, "The panel must paint visible text using $expected")
    }
}
