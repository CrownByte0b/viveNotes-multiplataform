package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Offset
import com.vivenotes.model.PageStyle
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasKeyboardPasteTest {
    private val margin = WorkspaceState.PICTURE_INSERT_MARGIN

    @Test
    fun ctrlVPastesUnderTheMouseWhereThePageTakesContent() {
        assertEquals(Offset(320f, 410f),
            keyboardPastePoint(Offset(320f, 410f), Offset(0f, 0f), PageStyle()) { _, _ -> true })
    }

    @Test
    fun withTheMouseOffThePageOrSomewhereNothingGoesItPastesInsideTheVisibleCornerBelowTheTitle() {
        val style = PageStyle()
        val titleFloor = PageStyle.TITLE_BAND_DP
        // Scrolled down: just inside the corner, as a picture is inserted.
        assertEquals(Offset(40f + margin, 900f + margin),
            keyboardPastePoint(null, Offset(40f, 900f), style) { _, _ -> true })
        // At the top of the page the title band is kept clear.
        assertEquals(Offset(margin, titleFloor),
            keyboardPastePoint(null, Offset.Zero, style) { _, _ -> true })
        // Over the title, or beside a sheet that bounds the page.
        assertEquals(Offset(margin, titleFloor),
            keyboardPastePoint(Offset(300f, 10f), Offset.Zero, style) { _, y -> y >= titleFloor })
        // With the title hidden, the corner itself.
        assertEquals(Offset(margin, margin),
            keyboardPastePoint(null, Offset.Zero, style.copy(hideTitle = true)) { _, _ -> true })
    }
}
