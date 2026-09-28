package com.vivenotes.ui.ribbon.view

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.PaperSize
import com.vivenotes.ui.components.PaneChoice
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PaperDefaultChoiceTest {
    @Test
    fun paperMenuShowsCurrentAndDefaultAndLongPressChangesFuturePaperOnly() =
        runDesktopComposeUiTest(width = 700, height = 500) {
            var current by mutableStateOf(PaperSize.Auto)
            var default by mutableStateOf(PaperSize.Auto)
            setContent {
                ViveNotesTheme {
                    PaneChoice("Size", current, PaperSize.entries, ::paperSizeLabel,
                        onPick = { current = it }, default = default, onSetDefault = { default = it })
                }
            }
            onNodeWithTag(ToolPaneTags.field("Size")).performClick()
            onNodeWithTag(ToolPaneTags.option("Size", "Infinite")).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected, Default"))
            onNodeWithTag(ToolPaneTags.option("Size", "A4")).performTouchInput { longClick() }
            runOnIdle {
                assertEquals(PaperSize.Auto, current)
                assertEquals(PaperSize.A4, default)
            }
            onNodeWithTag(ToolPaneTags.field("Size")).performClick()
            onNodeWithTag(ToolPaneTags.option("Size", "A4")).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Default"))
            onNodeWithTag(ToolPaneTags.option("Size", "A4")).performClick()
            runOnIdle { assertEquals(PaperSize.A4, current) }
        }
}
