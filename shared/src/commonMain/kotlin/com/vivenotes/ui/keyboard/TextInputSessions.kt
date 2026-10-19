package com.vivenotes.ui.keyboard

import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Whether a text field has the keyboard. A focused, editable field keeps a text input session open
 * for as long as it takes typing — a text box, the page title, the search field, a dialog's field —
 * and every one composed under [InterceptPlatformTextInput] with this passes through here on its way
 * to the platform. Read on the UI thread, where sessions open and close.
 */
internal class TextInputSessions : PlatformTextInputInterceptor {
    private var open = 0

    /** True while a field takes typed text: a letter pressed now is a letter, not a shortcut. */
    val typing: Boolean get() = open > 0

    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        open++
        try {
            nextHandler.startInputMethod(request)
        } finally {
            open--
        }
    }
}
