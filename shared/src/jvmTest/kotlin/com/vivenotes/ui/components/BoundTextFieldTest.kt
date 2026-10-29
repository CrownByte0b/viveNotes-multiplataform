package com.vivenotes.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BoundTextFieldTest {
    @Test
    fun presentationKeepsRichStylesWithoutChangingDocumentTextOrSelections() = runDesktopComposeUiTest {
        val source = AnnotatedString("first\n", listOf(AnnotatedString.Range(SpanStyle(color = Color.Red), 0, 5)),
            listOf(AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.End), 0, 5)))
        val value = mutableStateOf(TextFieldValue(source, TextRange(2, 4)))
        var measured: TextLayoutResult? = null
        setContent { ViveNotesTheme {
            BoundTextField(value.value, { value.value = it }, separateBlocks = true,
                onTextLayout = { measured = it }, modifier = Modifier.testTag("rich"))
        } }
        runOnIdle {
            val shown = assertNotNull(measured).layoutInput.text
            assertEquals("first\u200B\u200B", shown.text)
            assertTrue(shown.spanStyles.any { it.start == 0 && it.end == 5 && it.item.color == Color.Red })
            assertTrue(shown.paragraphStyles.any { it.item.textAlign == TextAlign.End })
            assertEquals(source, value.value.annotatedString)
            assertEquals(TextRange(2, 4), value.value.selection)
        }
        onNodeWithTag("rich").performTextInputSelection(TextRange(1, 3))
        runOnIdle { assertEquals(TextRange(1, 3), value.value.selection) }
        runOnIdle { value.value = value.value.copy(selection = TextRange(4)) }
        runOnIdle {
            assertEquals(TextRange(4), onNodeWithTag("rich").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        }
    }

    @Test
    fun literalZeroWidthSpacesAreNeverMistakenForEmptyParagraphPadding() = runDesktopComposeUiTest {
        val source = TextFieldValue("text\u200B")
        var measured: TextLayoutResult? = null
        setContent { BoundTextField(source, {}, separateBlocks = true, onTextLayout = { measured = it }) }
        runOnIdle { assertEquals(source.text, assertNotNull(measured).layoutInput.text.text) }
    }

    @Test
    fun inputReachesItsOwnerBeforeAnImmediateSaveCanRun() = runDesktopComposeUiTest {
        val value = mutableStateOf(TextFieldValue("before"))
        setContent { BoundTextField(value.value, { value.value = it }, Modifier.testTag("save")) }
        onNodeWithTag("save").performTextReplacement("save this edit")
        assertEquals("save this edit", value.value.text)
        assertEquals(TextRange(14), value.value.selection)
    }

    @Test
    fun plainTextKeepsTypedCaretsAndAcceptsAnExternalReset() = runDesktopComposeUiTest {
        val text = mutableStateOf("first")
        setContent { BoundTextField(text.value, { text.value = it }, Modifier.testTag("plain")) }
        onNodeWithTag("plain").performTextInputSelection(TextRange(5))
        onNodeWithTag("plain").performTextInput("!")
        runOnIdle { assertEquals("first!", text.value) }
        onNodeWithTag("plain").performTextInput("?")
        runOnIdle { assertEquals("first!?", text.value) }
        runOnIdle { text.value = "reset" }
        onNodeWithTag("plain").performTextInputSelection(TextRange(5))
        onNodeWithTag("plain").performTextInput("!")
        runOnIdle { assertEquals("reset!", text.value) }
    }

    @Test
    fun secureTextStaysMaskedAndCanBeClearedByItsOwner() = runDesktopComposeUiTest {
        val text = mutableStateOf("")
        var measured: TextLayoutResult? = null
        setContent { ViveNotesTheme {
            BoundPasswordField(text.value, { text.value = it }, Modifier.testTag("password"), onTextLayout = { measured = it })
        } }
        onNodeWithTag("password").performTextReplacement("new secret")
        runOnIdle {
            assertEquals("new secret", text.value)
            val semantics = onNodeWithTag("password").fetchSemanticsNode().config
            assertTrue(SemanticsProperties.Password in semantics)
            assertEquals("\u2022".repeat(text.value.length), assertNotNull(measured).layoutInput.text.text)
        }
        runOnIdle { text.value = "" }
        runOnIdle {
            assertEquals("", onNodeWithTag("password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
    }
}
