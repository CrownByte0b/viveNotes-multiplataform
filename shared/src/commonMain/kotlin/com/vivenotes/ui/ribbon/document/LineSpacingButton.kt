package com.vivenotes.ui.ribbon.document

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ViewSymbols
import com.vivenotes.ui.ribbon.RibbonIcon

/** The spacings the Line spacing menu offers, as word processors do: multiples of a single line. */
internal val LineSpacings get() = listOf(
    1f to Strings.line_spacing_1_0,
    1.15f to Strings.line_spacing_1_15,
    1.5f to Strings.line_spacing_1_5,
    2f to Strings.line_spacing_2_0,
    2.5f to Strings.line_spacing_2_5,
    3f to Strings.line_spacing_3_0,
)

/**
 * Line spacing: a menu of [LineSpacings] for the paragraphs in the selection, ticking [current],
 * the spacing of the paragraph the selection starts in. Like Styles, it keeps the editor range the
 * press began with, because opening the menu takes the keyboard from the editor.
 */
@Composable
internal fun LineSpacingButton(
    current: Float?,
    enabled: Boolean,
    selection: TextSelection?,
    onPick: (Float, TextSelection?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectionAtOpen by remember { mutableStateOf<TextSelection?>(null) }
    Box(Modifier.captureSelectionOnPress(selection) { selectionAtOpen = it }) {
        RibbonIcon(DocumentSymbols.FormatLineSpacing, Strings.line_spacing, selected = expanded,
            enabled = enabled, tag = DocumentRibbonTags.LineSpacing, dropdown = true) {
            if (selectionAtOpen == null) selectionAtOpen = selection
            expanded = true
        }
        ScaledDropdownMenu(expanded = expanded, onDismissRequest = {
            expanded = false; selectionAtOpen = null
        }) {
            LineSpacings.forEach { (spacing, label) ->
                val chosen = spacing == current
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        if (chosen) Icon(ViewSymbols.Check, contentDescription = null,
                            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        else Spacer(Modifier.size(18.dp))
                    },
                    onClick = {
                        expanded = false
                        onPick(spacing, selectionAtOpen)
                        selectionAtOpen = null
                    },
                    modifier = Modifier.testTag(DocumentRibbonTags.lineSpacing(spacing))
                        .semantics { selected = chosen },
                )
            }
        }
    }
}
