package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.ui.components.ToolPane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun InkMathPane(selection: InkSelection, math: DrawMath, onClose: () -> Unit) {
    var source by remember(selection, math) { mutableStateOf<String?>(null) }
    var error by remember(selection, math) { mutableStateOf<String?>(null) }
    var attempt by remember(selection, math) { mutableIntStateOf(0) }
    LaunchedEffect(selection, math) { math.holdFormulaModel() }
    LaunchedEffect(selection, math, attempt) {
        source = null
        error = null
        try {
            val ink = withContext(Dispatchers.Default) { selection.forRecognition() }
                ?: throw IllegalStateException("The selected ink could not be read.")
            val recognized = withoutTrailingEquals(math.readFormula(ink))
            currentCoroutineContext().ensureActive()
            check(recognized.isNotBlank()) { "No formula was recognized. Try selecting the expression again." }
            source = recognized
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            error = failure.message ?: "Unable to read this formula."
        }
    }
    val recognized = source
    if (recognized != null) MathPane(recognized, math, onClose)
    else ToolPane("Math", onClose) {
        val failure = error
        if (failure == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ink-math-loading"))
            Text("Reading handwriting…")
        } else {
            Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ink-math-error"))
            TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("ink-math-retry")) { Text("Retry") }
        }
    }
}
