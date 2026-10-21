package com.vivenotes.richtext

import com.vivenotes.model.Mark
import com.vivenotes.model.Run

internal fun normalizedRuns(runs: List<Run>, mergeEquations: Boolean = false): List<Run> = buildList {
    var pending: Run? = null
    var text: StringBuilder? = null
    fun flush() {
        pending?.let { add(if (text == null) it else it.copy(text = text.toString())) }
        text = null
    }
    for (run in runs) {
        if (run.text.isEmpty()) continue
        val previous = pending
        if (previous != null && previous.marks == run.marks &&
            (mergeEquations || previous.marks.none { it is Mark.Equation })) {
            val builder = text ?: StringBuilder(previous.text).also { text = it }
            builder.append(run.text)
        } else {
            flush()
            pending = run
        }
    }
    flush()
}
