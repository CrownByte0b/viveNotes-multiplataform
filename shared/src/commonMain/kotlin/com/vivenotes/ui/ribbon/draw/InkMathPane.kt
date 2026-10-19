package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun InkMathPane(selection: InkSelection, math: DrawMath, showInterpretation: Boolean = false,
                         log: DebugLog = remember { DebugLog() }, onClose: () -> Unit) {
    var source by remember(selection, math) { mutableStateOf<String?>(null) }
    var error by remember(selection, math) { mutableStateOf<String?>(null) }
    var attempt by remember(selection, math) { mutableIntStateOf(0) }
    LaunchedEffect(selection, math) { math.holdFormulaModel() }
    LaunchedEffect(selection, math, attempt) {
        source = null
        error = null
        try {
            val ink = withContext(Dispatchers.Default) { selection.forRecognition() }
                ?: throw IllegalStateException(Strings.the_selected_ink_could_not_be_read)
            val recognized = withoutTrailingEquals(math.readFormula(ink))
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
    if (recognized != null) MathPane(recognized, math, showInterpretation, log, onClose)
    else MathPaneFrame(onClose) {
        val failure = error
        if (failure == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ink-math-loading"))
            Text(Strings.reading_handwriting)
        } else {
            Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ink-math-error"))
            TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("ink-math-retry")) { Text(Strings.retry) }
        }
    }
}
