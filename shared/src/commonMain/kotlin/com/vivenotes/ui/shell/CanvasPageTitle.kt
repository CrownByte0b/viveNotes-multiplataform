package com.vivenotes.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.workspace.PageSummary

@Composable
internal fun CanvasPageTitle(
    page: PageSummary,
    palette: CanvasPalette,
    titleState: MutableState<TextFieldValue>,
    titleFocus: FocusRequester,
    onTitleChange: (String) -> Unit,
    onClearCanvasFocus: () -> Unit,
) {
    var titleValue by titleState
    if (!page.document.style.hideTitle) {
        Column(Modifier.offset(16.dp, 8.dp).width(720.dp)) {
            BasicTextField(
                value = titleValue,
                onValueChange = { titleValue = it; onTitleChange(it.text) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = palette.ink),
                cursorBrush = SolidColor(palette.ink),
                decorationBox = { inner ->
                    Box {
                        if (page.title.isEmpty()) Text(Strings.untitled_page,
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.secondaryInk)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus)
                    // Typing a title leaves the text box: its formatting commands must
                    // not go on acting on a box the caret has left.
                    .onFocusChanged { if (it.isFocused) onClearCanvasFocus() }
                    .testTag(WorkspaceTestTags.TitleEditor),
            )
            Spacer(Modifier.height(2.dp))
            Box(Modifier.width(420.dp).height(1.dp).background(palette.rule))
            Spacer(Modifier.height(6.dp))
            Text(page.createdLabel, style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryInk)
        }
    }
}
