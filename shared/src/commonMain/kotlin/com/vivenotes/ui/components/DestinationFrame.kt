package com.vivenotes.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.ui.icons.AccountSymbols
import com.vivenotes.ui.theme.LocalDesktopColors

/**
 * A destination that covers the whole window, as Android's screens do: a header row with Back and
 * the title over a scrolling, centred body — the desktop's form of a `Scaffold` with a `TopAppBar`.
 *
 * Escape is system Back: [onEscape], which dismisses a dialog in front before it leaves the screen.
 * It is read after the focused field has had the key, and a dialog that holds the focus closes on
 * Escape itself. The frame takes the focus when it opens and keeps it, with [overlay] — the dialogs —
 * inside the same focus group, so the keys do not go on reaching the workspace underneath.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DestinationFrame(
    title: String,
    onBack: () -> Unit,
    screenTag: String,
    backTag: String,
    modifier: Modifier = Modifier,
    onEscape: () -> Unit = onBack,
    overlay: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Keys reach only the focused element and what contains it. Content that replaces the field
    // which had the focus, or a closed dialog, takes the focus with it; then nothing here has it and
    // Escape goes nowhere. The frame takes it back a frame later, as the workspace does for its
    // canvas — which also gives it the focus when it opens.
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) {
        if (hasFocus) return@LaunchedEffect
        withFrameNanos { }
        if (!hasFocus) focus.requestFocus()
    }
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(screenTag)
            .focusRequester(focus)
            .onFocusChanged { hasFocus = it.hasFocus }
            .onKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onEscape()
                    true
                } else {
                    false
                }
            }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .focusable(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = LocalDesktopColors.current.headerBar, shadowElevation = 1.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag(backTag)) {
                            Icon(
                                imageVector = AccountSymbols.ArrowBack,
                                contentDescription = Strings.navigate_back,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    content()
                }
            }
            overlay()
        }
    }
}
