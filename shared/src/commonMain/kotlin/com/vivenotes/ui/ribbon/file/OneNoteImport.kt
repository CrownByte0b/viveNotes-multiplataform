package com.vivenotes.ui.ribbon.file

import com.vivenotes.localization.Strings

/** Android's import summary: repeated omissions appear once per kind, with their count. */
internal fun oneNoteImportMessage(notebookName: String, warnings: List<String>): String {
    val imported = Strings.onenote_imported(notebookName)
    if (warnings.isEmpty()) return imported
    val kinds = warnings.groupingBy { it }.eachCount().entries.joinToString("\n") { (warning, count) ->
        val line = warning.replaceFirstChar(Char::uppercaseChar)
        if (count == 1) "• $line" else "• $line ($count×)"
    }
    val total = if (warnings.size == 1) Strings.onenote_one_item_omitted else Strings.onenote_items_omitted(warnings.size)
    return "$imported $total\n$kinds"
}
