package com.vivenotes.ui.ribbon.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DocumentPickerDefaultsTest {
    @Test
    fun fontMenuMarksTheCurrentAndDefaultOptionsAndLongPressChangesOnlyTheDefault() =
        runDesktopComposeUiTest(width = 700, height = 500) {
            var current by mutableStateOf("sans-serif")
            var default by mutableStateOf("sans-serif")
            setContent {
                ViveNotesTheme {
                    RibbonPicker("Font family", current, default, FontFamilies, true, null,
                        DocumentRibbonTags.FontFamily,
                        onPick = { value, _ -> current = value }, onSetDefault = { default = it })
                }
            }
            onNodeWithTag(DocumentRibbonTags.FontFamily).performClick()
            onNodeWithTag("document-font-family-sans-serif").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected, Default"))
            onNodeWithTag("document-font-family-lora").performTouchInput { longClick() }
            runOnIdle {
                assertEquals("sans-serif", current)
                assertEquals("lora", default)
            }
            onNodeWithTag(DocumentRibbonTags.FontFamily).performClick()
            onNodeWithTag("document-font-family-lora").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Default"))
            onNodeWithTag("document-font-family-lora").performClick()
            runOnIdle { assertEquals("lora", current) }
        }

    @Test
    fun sizeMenuLongPressSetsTheDefaultWithoutApplyingTheSize() =
        runDesktopComposeUiTest(width = 700, height = 500) {
            var current by mutableStateOf("15")
            var default by mutableStateOf("15")
            setContent {
                ViveNotesTheme {
                    RibbonPicker("Font size", current, default, FontSizes.map { it.toString() to it.toString() },
                        true, null, DocumentRibbonTags.FontSize,
                        onPick = { value, _ -> current = value }, onSetDefault = { default = it })
                }
            }
            onNodeWithTag(DocumentRibbonTags.FontSize).performClick()
            onNodeWithTag("document-font-size-24").performTouchInput { longClick() }
            runOnIdle {
                assertEquals("15", current)
                assertEquals("24", default)
            }
        }
}
