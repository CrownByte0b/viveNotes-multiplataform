package com.vivenotes.ui.ribbon.document

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vivenotes.model.normalizedLinkUrl
import com.vivenotes.richtext.LinkTarget
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.ribbon.RibbonIcon

/** The selection and link captured before the ribbon button takes focus from the editor. */
internal class LinkEditorRequest(
    val selection: TextSelection?,
    val existing: LinkTarget,
    val onSubmit: (String, String, TextSelection?) -> Unit,
)

@Composable
internal fun LinkButton(enabled: Boolean, onClick: () -> Unit) {
    RibbonIcon(DocumentSymbols.Link, "Link", enabled = enabled,
        tag = DocumentRibbonTags.Link, onClick = onClick)
}

/** The editor belongs to the workspace window so pointer and keyboard focus stay together. */
@Composable
internal fun LinkEditorDialog(request: LinkEditorRequest, onDismiss: () -> Unit) {
    var label by remember(request) { mutableStateOf(request.existing.text) }
    var address by remember(request) { mutableStateOf(request.existing.url.orEmpty()) }
    val first = remember(request) { FocusRequester() }
    val url = normalizedLinkUrl(address)
    fun submit() {
        val valid = url ?: return
        onDismiss()
        request.onSubmit(label.ifBlank { valid }, valid, request.selection)
    }

    DesktopDialogFrame(
        title = if (request.existing.url != null) "Edit link" else "Insert link",
        icon = DocumentSymbols.Link,
        iconTint = MaterialTheme.colorScheme.primary,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(DocumentRibbonTags.LinkPanel),
        backdropTag = "document-link-backdrop",
        maxWidth = 400.dp,
        content = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Text to display") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(DocumentRibbonTags.LinkText)
                        .then(if (request.existing.text.isEmpty()) Modifier.focusRequester(first) else Modifier),
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
                        .then(if (request.existing.text.isNotEmpty()) Modifier.focusRequester(first) else Modifier),
                )
            }
        },
        actions = {
            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(DocumentRibbonTags.LinkCancel)) { Text("Cancel") }
            Button(onClick = ::submit, enabled = url != null, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(DocumentRibbonTags.LinkSubmit)) { Text("Apply") }
        },
    )
    LaunchedEffect(request) { first.requestFocus() }
}
