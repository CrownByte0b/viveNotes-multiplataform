package com.vivenotes.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue

@Composable
internal fun BoundPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val field = rememberBoundTextFieldState(TextFieldValue(value), { onValueChange(it.text) },
        synchronizeSelection = false)
    OutlinedSecureTextField(state = field.state, inputTransformation = field.inputTransformation,
        modifier = modifier, enabled = enabled,
        label = if (label == null) null else { { label() } },
        supportingText = supportingText, isError = isError, keyboardOptions = keyboardOptions,
        textObfuscationMode = TextObfuscationMode.Hidden,
        onTextLayout = { getResult -> getResult()?.let(onTextLayout) })
}
