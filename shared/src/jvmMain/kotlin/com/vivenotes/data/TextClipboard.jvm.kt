package com.vivenotes.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.awtClipboard
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun rememberPlatformTextClipboard(): TextClipboard {
    val clipboard = LocalClipboard.current
    return remember(clipboard) { DesktopTextClipboard(clipboard.awtClipboard) }
}

internal class DesktopTextClipboard(private val clipboard: Clipboard?) : TextClipboard {
    override fun getText(): AnnotatedString? = try {
        (clipboard?.getData(DataFlavor.stringFlavor) as? String)?.let(::AnnotatedString)
    } catch (_: UnsupportedFlavorException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: IOException) {
        null
    }

    override fun setText(annotatedString: AnnotatedString) {
        clipboard?.setContents(StringSelection(annotatedString.text), null)
    }
}
