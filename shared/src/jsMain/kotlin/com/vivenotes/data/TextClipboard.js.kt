package com.vivenotes.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@Composable
internal actual fun rememberPlatformTextClipboard(): TextClipboard {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        object : TextClipboard {
            // Browsers expose no synchronous clipboard read, as with the previous Compose adapter.
            override fun getText(): AnnotatedString? = null
            override fun setText(annotatedString: AnnotatedString) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    clipboard.setClipEntry(ClipEntry.withPlainText(annotatedString.text))
                }
            }
        }
    }
}
