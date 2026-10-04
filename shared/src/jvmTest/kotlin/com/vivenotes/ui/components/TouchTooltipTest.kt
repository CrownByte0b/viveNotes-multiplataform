package com.vivenotes.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.ShellSymbols
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tooltips under a finger behave as on Android: a long press shows one, a tap does not. */
@OptIn(ExperimentalTestApi::class)
class TouchTooltipTest {
    private val label = "Hide notebook navigation"

    private fun ComposeUiTest.button(focus: FocusRequester = FocusRequester(), onClick: () -> Unit = {}) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                Box(Modifier.padding(60.dp)) {
                    TooltipIconButton(label, onClick = onClick,
                        modifier = Modifier.testTag("button").focusRequester(focus)) {
                        Icon(ShellSymbols.Menu, contentDescription = null)
                    }
                }
            }
        }
    }

    @Test
    fun aTapDoesItsWorkWithoutFlashingTheTooltip() = runDesktopComposeUiTest(width = 400, height = 300) {
        var clicks = 0
        button(onClick = { clicks++ })
        onNodeWithTag("button").performTouchInput { click() }
        mainClock.advanceTimeBy(800)
        waitForIdle()
        assertEquals(1, clicks)
        onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun aLongPressShowsTheTooltip() = runDesktopComposeUiTest(width = 400, height = 300) {
        button()
        onNodeWithTag("button").performTouchInput { longClick() }
        onNodeWithText(label).assertExists()
    }

    @Test
    fun keyboardFocusStillShowsTheTooltip() = runDesktopComposeUiTest(width = 400, height = 300) {
        val focus = FocusRequester()
        button(focus)
        runOnIdle { focus.requestFocus() }
        mainClock.advanceTimeBy(400)
        onNodeWithText(label).assertExists()
    }
}
