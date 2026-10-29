package com.vivenotes.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString

/** Plain text clipboard operations used by desktop editor commands. */
internal interface TextClipboard {
    fun getText(): AnnotatedString?
    fun setText(annotatedString: AnnotatedString)
    fun hasText(): Boolean = !getText()?.text.isNullOrEmpty()
}

internal val LocalTextClipboard = staticCompositionLocalOf<TextClipboard?> { null }

@Composable
internal fun rememberTextClipboard(): TextClipboard = LocalTextClipboard.current ?: rememberPlatformTextClipboard()

@Composable
internal expect fun rememberPlatformTextClipboard(): TextClipboard
