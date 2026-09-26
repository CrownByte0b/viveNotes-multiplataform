package com.vivenotes.ui.ribbon.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vivenotes.model.normalizedLinkUrl
import com.vivenotes.richtext.LinkTarget
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.ribbon.RibbonIcon

/**
 * Android's `LinkButton`: a panel under the Link button that links the selected text, edits the
 * link at the caret, or inserts a new one. The text range is the one the ribbon captured when the
 * button was pressed, because typing into the panel takes focus away from the editor.
 */
@Composable
internal fun LinkButton(
    enabled: Boolean,
    /** The range to link, captured at the press, and what the panel starts with for it. */
    onOpen: () -> Pair<TextSelection?, LinkTarget>,
    onSubmit: (label: String, url: String, TextSelection?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<TextSelection?>(null) }
    var editing by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var focusAddress by remember { mutableStateOf(false) }
    val url = normalizedLinkUrl(address)
    fun submit() {
        val valid = url ?: return
        expanded = false
        onSubmit(label.ifBlank { valid }, valid, target)
    }

    Box {
        RibbonIcon(DocumentSymbols.Link, "Link", selected = expanded, enabled = enabled || expanded,
            tag = DocumentRibbonTags.Link) {
            val (selection, existing) = onOpen()
            target = selection
            editing = existing.url != null
            label = existing.text
            address = existing.url.orEmpty()
            focusAddress = existing.text.isNotEmpty()
            expanded = true
        }
        ScaledDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val first = remember { FocusRequester() }
            Column(
                Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(DocumentRibbonTags.LinkPanel),
            ) {
                Text(if (editing) "Edit link" else "Insert link", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Text to display") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(DocumentRibbonTags.LinkText)
                        .then(if (focusAddress) Modifier else Modifier.focusRequester(first)),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Web address") },
                    singleLine = true,
                    isError = address.isNotBlank() && url == null,
                    supportingText = { Text("https://example.com") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .testTag(DocumentRibbonTags.LinkAddress)
                        .then(if (focusAddress) Modifier.focusRequester(first) else Modifier),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { expanded = false }, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag(DocumentRibbonTags.LinkCancel)) { Text("Cancel") }
                    Button(onClick = ::submit, enabled = url != null, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag(DocumentRibbonTags.LinkSubmit)) { Text("Apply") }
                }
            }
            // Keyboard focus starts where there is still something to type.
            LaunchedEffect(Unit) { first.requestFocus() }
        }
    }
}
