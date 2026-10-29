package com.vivenotes.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextFieldBindingTest {
    @Test
    fun externalEditsAndSelectionsDoNotEchoBackAsUserInput() {
        val binding = TextFieldBinding(TextFieldValue("first", TextRange(2)))
        assertNull(binding.takeChange(binding.currentValue()))
        binding.synchronize(TextFieldValue("other text", TextRange(3, 7)))
        assertEquals("other text", binding.state.text.toString())
        assertEquals(TextRange(3, 7), binding.state.selection)
        assertNull(binding.takeChange(binding.currentValue()))
    }

    @Test
    fun formattingRecompositionDoesNotRevertPendingTyping() {
        val binding = TextFieldBinding(TextFieldValue("first", TextRange(5)))
        binding.state.edit { append("!"); selection = TextRange(6) }
        binding.synchronize(TextFieldValue(AnnotatedString("first", SpanStyle(color = Color.Red)), TextRange(5)))
        assertEquals("first!", binding.state.text.toString())
        assertEquals(TextFieldValue("first!", TextRange(6)), binding.takeChange(binding.currentValue()))
        assertNull(binding.takeChange(binding.currentValue()))
    }

    @Test
    fun rapidInsertAndDeleteBothReachAPlainTextOwnerBeforeRecomposition() {
        val binding = TextFieldBinding(TextFieldValue(""), synchronizeSelection = false)
        assertEquals("a", binding.takeChange(TextFieldValue("a", TextRange(1)))?.text)
        assertEquals("", binding.takeChange(TextFieldValue(""))?.text)
        assertNull(binding.takeChange(TextFieldValue("")))
        binding.synchronize(TextFieldValue("words"))
        assertNull(binding.takeChange(TextFieldValue("words", TextRange(2))))
    }

    @Test
    fun plainTextBindingsKeepTheCaretWhenTheModelAcknowledgesTyping() {
        val binding = TextFieldBinding(TextFieldValue("first"), synchronizeSelection = false)
        binding.state.edit { selection = TextRange(5); append("!"); selection = TextRange(6) }
        binding.takeChange(binding.currentValue())
        binding.synchronize(TextFieldValue("first!"))
        assertEquals(TextRange(6), binding.state.selection)
        assertNull(binding.takeChange(binding.currentValue()))
        binding.synchronize(TextFieldValue("reset"))
        assertEquals("reset", binding.state.text.toString())
        assertEquals(TextRange(5), binding.state.selection)
        assertNull(binding.takeChange(binding.currentValue()))
    }
}
