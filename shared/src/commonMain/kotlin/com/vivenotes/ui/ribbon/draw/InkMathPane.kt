package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What Math was pressed on: the ink and the lines selected with it (`WorkspaceState.selectedFormulaLines`). */
internal data class InkMathRequest(val selection: InkSelection, val lines: List<Outline.Shape> = emptyList())

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun InkMathPane(request: InkMathRequest, math: DrawMath, showInterpretation: Boolean = false,
                         log: DebugLog = remember { DebugLog() }, onPlaceGraph: ((com.vivenotes.math.MathGraph) -> Unit)? = null, onClose: () -> Unit) {
    var source by remember(request, math) { mutableStateOf<String?>(null) }
    var error by remember(request, math) { mutableStateOf<String?>(null) }
    var attempt by remember(request, math) { mutableIntStateOf(0) }
    LaunchedEffect(request, math) { math.holdFormulaModel() }
    LaunchedEffect(request, math, attempt) {
        source = null
        error = null
        try {
            val ink = withContext(Dispatchers.Default) { request.selection.forRecognition() }
                ?: throw IllegalStateException(Strings.the_selected_ink_could_not_be_read)
            val recognized = withoutTrailingEquals(math.readFormula(ink, request.lines))
            currentCoroutineContext().ensureActive()
            check(recognized.isNotBlank()) { Strings.no_formula_was_recognized_try_selecting_the_expression_again }
            source = recognized
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            error = failure.message ?: Strings.unable_to_read_this_formula_578906
        }
    }
    val recognized = source
    if (recognized != null) MathPane(recognized, math, showInterpretation, log, onPlaceGraph, onClose)
    else MathPaneFrame(onClose) {
        val failure = error
        if (failure == null) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth().testTag("ink-math-loading"))
            Text(Strings.reading_handwriting)
        } else {
            Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ink-math-error"))
            TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("ink-math-retry")) { Text(Strings.retry) }
        }
    }
}
