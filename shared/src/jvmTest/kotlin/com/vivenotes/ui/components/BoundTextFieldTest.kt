@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.vivenotes.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.selection.SelectionHandleInfoKey
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BoundTextFieldTest {
    @Test
    fun changingRichStylesPreservesMouseSelectionAndRefreshesThePresentation() = runDesktopComposeUiTest {
        val value = mutableStateOf(TextFieldValue("first\nsecond\n"))
        var measured: TextLayoutResult? = null
        setContent {
            BoundTextField(value.value, { value.value = it }, Modifier.testTag("rich"), separateBlocks = true,
                onTextLayout = { measured = it })
        }
        val editor = onNodeWithTag("rich")
        editor.performMouseInput { click(center) }
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        val selection = TextRange(0, value.value.text.length)
        onAllNodes(SemanticsMatcher.keyIsDefined(SelectionHandleInfoKey)).assertCountEquals(0)
        listOf(TextAlign.Center, TextAlign.End, TextAlign.Start).forEach { align ->
            runOnIdle {
                val source = value.value.text
                value.value = value.value.copy(annotatedString = AnnotatedString(source,
                    spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = Color.Red), 0, 5)),
                    paragraphStyles = listOf(AnnotatedString.Range(ParagraphStyle(textAlign = align), 0, 6),
                        AnnotatedString.Range(ParagraphStyle(textAlign = align), 6, source.length),
                        AnnotatedString.Range(ParagraphStyle(textAlign = align), source.length, source.length))))
            }
            runOnIdle {
                val shown = assertNotNull(measured).layoutInput.text
                assertEquals("first\u200Bsecond\u200B\u200B", shown.text)
                assertTrue(shown.spanStyles.any { it.item.color == Color.Red && it.start == 0 && it.end == 5 })
                assertEquals(listOf(align, align, align), shown.paragraphStyles.map { it.item.textAlign })
                assertEquals(selection, value.value.selection)
            }
            onAllNodes(SemanticsMatcher.keyIsDefined(SelectionHandleInfoKey)).assertCountEquals(0)
        }
        editor.performTextInput("replacement")
        runOnIdle { assertEquals("replacement", value.value.text) }
        onAllNodes(SemanticsMatcher.keyIsDefined(SelectionHandleInfoKey)).assertCountEquals(0)
    }

    @Test
    fun touchSelectionsStillShowHandlesAfterFormattingChanges() = runDesktopComposeUiTest {
        val value = mutableStateOf(TextFieldValue("first second"))
        var measured: TextLayoutResult? = null
        setContent {
            Box(Modifier.padding(40.dp)) {
                BoundTextField(value.value, { value.value = it }, Modifier.testTag("touch"),
                    onTextLayout = { measured = it })
            }
        }
        val word = runOnIdle { assertNotNull(measured).getBoundingBox(2).center }
        onNodeWithTag("touch").performTouchInput { click(word) }
        onNodeWithTag("touch").assertIsFocused()
        onNodeWithTag("touch").performTouchInput { longClick(word) }
        runOnIdle { assertEquals(TextRange(0, 5), value.value.selection) }
        onAllNodes(SemanticsMatcher.keyIsDefined(SelectionHandleInfoKey)).assertCountEquals(2)
        val selected = runOnIdle { value.value.selection }
        runOnIdle {
            value.value = value.value.copy(annotatedString = AnnotatedString(value.value.text,
                spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = Color.Red), 0, value.value.text.length))))
        }
        onAllNodes(SemanticsMatcher.keyIsDefined(SelectionHandleInfoKey)).assertCountEquals(2)
        runOnIdle { assertEquals(selected, value.value.selection) }
    }

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
