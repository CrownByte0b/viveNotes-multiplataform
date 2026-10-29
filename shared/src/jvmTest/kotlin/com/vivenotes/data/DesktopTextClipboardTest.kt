package com.vivenotes.data

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTextClipboardTest {
    @Test
    fun theNativeClipboardKeepsUnicodeTextAndOnlyPublishesPlainText() {
        val native = Clipboard("editor text fixture")
        val clipboard = DesktopTextClipboard(native)
        assertFalse(clipboard.hasText())
        val text = "Café 日本 😀\nSecond line"
        clipboard.setText(AnnotatedString(text, SpanStyle(color = Color.Red)))
        assertEquals(AnnotatedString(text), clipboard.getText())
        assertTrue(clipboard.hasText())
        assertEquals(text, native.getData(DataFlavor.stringFlavor))
        assertFalse(native.isDataFlavorAvailable(DataFlavor.allHtmlFlavor))
    }

    @Test
    fun unavailableAndNonTextClipboardsHaveNoText() {
        val absent = DesktopTextClipboard(null)
        absent.setText(AnnotatedString("ignored"))
        assertNull(absent.getText())
        assertFalse(absent.hasText())
        val native = Clipboard("non-text fixture")
        native.setContents(object : Transferable {
            override fun getTransferDataFlavors() = emptyArray<DataFlavor>()
            override fun isDataFlavorSupported(flavor: DataFlavor?) = false
            override fun getTransferData(flavor: DataFlavor?): Any = throw UnsupportedFlavorException(flavor)
        }, null)
        assertNull(DesktopTextClipboard(native).getText())
    }

    @Test
    fun aBusyOrVanishedOwnerDoesNotBreakAnEditorCommand() {
        for (failure in listOf(IllegalStateException("busy"), IOException("owner closed"))) {
            val native = object : Clipboard("failed fixture") {
                override fun getData(flavor: DataFlavor?): Any = throw failure
            }
            assertNull(DesktopTextClipboard(native).getText())
            assertFalse(DesktopTextClipboard(native).hasText())
        }
    }
}
