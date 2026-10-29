package com.vivenotes.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.vivenotes.ui.canvas.BlockSeparators

/** Keeps the portable document model separate from Compose's mutable editing state. */
internal class TextFieldBinding(initial: TextFieldValue, private val synchronizeSelection: Boolean = true) {
    val state = TextFieldState(initial.text, initial.selection)
    private var external = initial
    private var reported = currentValue()

    fun currentValue(): TextFieldValue = TextFieldValue(state.text.toString(), state.selection, state.composition)

    fun synchronize(value: TextFieldValue) {
        val textChanged = value.text != external.text
        val selectionChanged = synchronizeSelection && value.selection != external.selection
        external = value
        if (!textChanged && !selectionChanged) return
        val nextSelection = if (synchronizeSelection) value.selection else state.selection
        if ((textChanged && value.text != state.text.toString()) || (selectionChanged && state.selection != nextSelection)) {
            state.edit {
                if (textChanged && value.text != asCharSequence().toString()) replace(0, length, value.text)
                selection = TextRange(nextSelection.start.coerceIn(0, length), nextSelection.end.coerceIn(0, length))
            }
            reported = currentValue()
        }
    }

    fun takeChange(value: TextFieldValue): TextFieldValue? {
        val changed = value != reported
        val textChanged = value.text != reported.text
        reported = value
        return value.takeIf { changed && (synchronizeSelection || textChanged) }
    }
}

internal data class BoundTextFieldState(val state: TextFieldState, val inputTransformation: InputTransformation)

@Composable
internal fun rememberBoundTextFieldState(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    synchronizeSelection: Boolean = true,
): BoundTextFieldState {
    val binding = remember { TextFieldBinding(value, synchronizeSelection) }
    val currentChange = rememberUpdatedState(onValueChange)
    val field = remember(binding) {
        BoundTextFieldState(binding.state, InputTransformation {
            val composing = binding.state.composition?.let {
                TextRange(it.start.coerceIn(0, length), it.end.coerceIn(0, length))
            }
            val proposed = TextFieldValue(asCharSequence().toString(), selection, composing)
            binding.takeChange(proposed)?.let { currentChange.value(it) }
        })
    }
    SideEffect { binding.synchronize(value) }
    LaunchedEffect(binding) {
        snapshotFlow { binding.currentValue() }.collect { next ->
            binding.takeChange(next)?.let { currentChange.value(it) }
        }
    }
    return field
}

@Composable
internal fun BoundTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    cursorBrush: Brush = SolidColor(Color.Black),
) {
    val field = rememberBoundTextFieldState(TextFieldValue(value), { onValueChange(it.text) },
        synchronizeSelection = false)
    BasicTextField(state = field.state, inputTransformation = field.inputTransformation,
        modifier = modifier, enabled = enabled, readOnly = readOnly,
        textStyle = textStyle, keyboardOptions = keyboardOptions,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minLines, maxLines),
        cursorBrush = cursorBrush)
}

@Composable
internal fun BoundTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    separateBlocks: Boolean = false,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(Color.Black),
    decorationBox: @Composable (inner: @Composable () -> Unit) -> Unit = { it() },
) {
    val field = rememberBoundTextFieldState(value, onValueChange)
    val styled = value.annotatedString
    val presentation = remember(styled, separateBlocks) {
        OutputTransformation {
            val shown = if (separateBlocks) BlockSeparators.filter(styled).text else styled
            if (separateBlocks) {
                val needsPadding = length == 0 || charAt(length - 1) == '\n'
                for (index in 0 until length) if (charAt(index) == '\n') replace(index, index + 1, "\u200B")
                if (needsPadding) append('\u200B')
            }
            shown.spanStyles.forEach { addStyle(it.item, it.start.coerceAtMost(length), it.end.coerceAtMost(length)) }
            shown.paragraphStyles.forEach { addStyle(it.item, it.start.coerceAtMost(length), it.end.coerceAtMost(length)) }
        }
    }
    BasicTextField(state = field.state, inputTransformation = field.inputTransformation,
        modifier = modifier, enabled = enabled, readOnly = readOnly,
        textStyle = textStyle, keyboardOptions = keyboardOptions,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minLines, maxLines),
        outputTransformation = presentation, onTextLayout = { getResult -> getResult()?.let(onTextLayout) },
        interactionSource = interactionSource, cursorBrush = cursorBrush, decorator = { decorationBox(it) })
}
