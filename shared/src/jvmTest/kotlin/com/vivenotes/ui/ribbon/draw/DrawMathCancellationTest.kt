package com.vivenotes.ui.ribbon.draw

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.math.*
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.canvas.INK_LAYER_TAG
import com.vivenotes.ui.components.assertIsIndeterminateWavyBar
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.InkSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DrawMathCancellationTest {
    @Test
    fun aRunningAnalysisShowsTheWavyBusyIndicatorUntilItsActionsArrive() = runDesktopComposeUiTest(width = 600, height = 800) {
        val gate = CompletableDeferred<Unit>()
        var started = false
        val math = object : DrawMath {
            override val engine = object : MathEngine {
                override suspend fun analyze(latex: String): MathAnalysis {
                    started = true
                    gate.await()
                    return MathAnalysis(actions = listOf(MathAction("expand", "Expand")))
                }
                override suspend fun execute(latex: String, actionId: String) = MathOperationResult(latex = latex)
            }
            override suspend fun readFormula(ink: InkPage) = ""
        }
        setContent { ViveNotesTheme { MathPane("x+1", math) {} } }
        mainClock.advanceTimeBy(300)
        waitUntil(timeoutMillis = 5000) { started }
        onNodeWithTag(MathPaneTags.Busy).assertIsIndeterminateWavyBar()
        runOnIdle { gate.complete(Unit) }
        onNodeWithTag(MathPaneTags.action("expand")).assertExists()
        onNodeWithTag(MathPaneTags.Busy).assertDoesNotExist()
    }

    @Test
    fun aCancelledNativeRecognitionCannotPublishItsAnswerOrEnableANewerRequest() = runDesktopComposeUiTest(width = 700, height = 700) {
        val gates = List(2) { CompletableDeferred<Unit>() }
        var reads = 0
        val finished = mutableListOf<Int>()
        val math = object : DrawMath {
            override val engine = object : MathEngine {
                override suspend fun analyze(latex: String) = MathAnalysis(actions = listOf(MathAction("simplify", "Simplify")))
                override suspend fun execute(latex: String, actionId: String) = MathOperationResult(latex = latex)
            }
            override suspend fun readFormula(ink: InkPage): String {
                val index = reads++
                withContext(NonCancellable) { gates[index].await(); finished += index }
                return "${index + 1}"
            }
        }
        setContent { ViveNotesTheme { FloatingCalculator(InkSettings(), 0, false, math, {}, {}) } }
        fun draw() = onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(20f, 80f)); press(); moveTo(Offset(200f, 80f), delayMillis = 80); release()
        }
        draw()
        onNodeWithTag(CalculatorTags.Equals).performClick()
        waitUntil(timeoutMillis = 5000) { reads == 1 }
        onNodeWithTag(CalculatorTags.Clear).performClick()
        draw()
        onNodeWithTag(CalculatorTags.Equals).performClick()
        waitUntil(timeoutMillis = 5000) { reads == 2 }
        runOnIdle { gates[0].complete(Unit) }
        waitUntil(timeoutMillis = 5000) { 0 in finished }
        onNodeWithTag(CalculatorTags.Equals).assertIsNotEnabled()
        onNodeWithTag(CalculatorTags.Result).assertDoesNotExist()
        runOnIdle { gates[1].complete(Unit) }
        waitUntil(timeoutMillis = 5000) { 1 in finished }
        onNodeWithTag(CalculatorTags.Result).assertExists()
        onNodeWithTag(CalculatorTags.Equals).assertIsEnabled()
    }

    @Test
    fun editingTheMathSourceDiscardsAnOlderAnalysisAndItsActions() = runDesktopComposeUiTest(width = 600, height = 800) {
        val old = CompletableDeferred<Unit>()
        var oldFinished = false
        var newAnalyzed = false
        val math = object : DrawMath {
            override val engine = object : MathEngine {
                override suspend fun analyze(latex: String): MathAnalysis {
                    if (latex == "old") withContext(NonCancellable) { old.await(); oldFinished = true }
                    else newAnalyzed = true
                    return MathAnalysis(actions = listOf(MathAction(latex, latex)))
                }
                override suspend fun execute(latex: String, actionId: String) = MathOperationResult(latex = latex)
            }
            override suspend fun readFormula(ink: InkPage) = ""
        }
        setContent { ViveNotesTheme { MathPane("old", math) {} } }
        mainClock.advanceTimeBy(250)
        onNodeWithTag("draw-math-source").performTextReplacement("new")
        mainClock.advanceTimeBy(250)
        waitUntil(timeoutMillis = 5000) { newAnalyzed }
        onNodeWithTag("draw-math-action-new").assertExists()
        runOnIdle { old.complete(Unit) }
        waitUntil(timeoutMillis = 5000) { oldFinished }
        onNodeWithTag("draw-math-action-new").assertExists()
        onNodeWithTag("draw-math-action-old").assertDoesNotExist()
    }

    @Test
    fun editingTheSourceDiscardsAnOlderNoncancellableActionResult() = runDesktopComposeUiTest(width = 600, height = 900) {
        val old = CompletableDeferred<Unit>()
        var started = false
        var finished = false
        val math = object : DrawMath {
            override val engine = object : MathEngine {
                override suspend fun analyze(latex: String) = MathAnalysis(actions = listOf(MathAction("expand", "Expand")))
                override suspend fun execute(latex: String, actionId: String): MathOperationResult {
                    if (latex == "old") withContext(NonCancellable) { started = true; old.await(); finished = true }
                    return MathOperationResult(latex = latex)
                }
            }
            override suspend fun readFormula(ink: InkPage) = ""
        }
        setContent { ViveNotesTheme { MathPane("old", math) {} } }
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.action("expand")).performClick()
        waitUntil { started }
        onNodeWithTag(MathPaneTags.action("expand")).assertIsNotEnabled()
        onNodeWithTag(MathPaneTags.Source).performTextReplacement("new")
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.action("expand")).assertIsEnabled().performClick()
        onNodeWithTag(MathPaneTags.Result).assertContentDescriptionEquals("new")
        runOnIdle { old.complete(Unit) }
        waitUntil { finished }
        onNodeWithTag(MathPaneTags.Result).assertContentDescriptionEquals("new")
        onNodeWithTag(MathPaneTags.action("expand")).assertIsEnabled()
    }
}
