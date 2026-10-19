package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Offset
import com.vivenotes.model.Block
import com.vivenotes.richtext.LinkTarget
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the text box menu's link command opens the link panel on, and so whether it reads Edit link. */
class TextMenuLinkTest {
    private val limitsUrl = "https://example.com/limits"

    /** "Review limits and derivatives", with "limits" (7–13) linked. */
    private val text = RichTextBuffer(listOf(Block.of("Review limits and derivatives")))
        .select(TextSelection(7, 13)).insertLink("limits", limitsUrl)
    private val link = text.linkSpans().single()

    private fun request(selection: TextSelection, link: RichTextBuffer.LinkSpan? = null) =
        TextMenuRequest("box", Offset.Zero, selection, link)

    @Test
    fun aRightClickOnALinkEditsTheWholeLink() {
        assertEquals(TextSelection(7, 13) to LinkTarget("limits", limitsUrl),
            linkEditFor(request(TextSelection(9), link), text))
    }

    @Test
    fun aSelectionInsideALinkEditsTheWholeLink() {
        assertEquals(TextSelection(7, 13) to LinkTarget("limits", limitsUrl),
            linkEditFor(request(TextSelection(8, 11)), text))
        assertEquals(TextSelection(7, 13) to LinkTarget("limits", limitsUrl),
            linkEditFor(request(TextSelection(7, 13), link), text))
    }

    @Test
    fun aSelectionOfPlainTextIsLinkedAsItIs() {
        assertEquals(TextSelection(0, 6) to LinkTarget("Review", null), linkEditFor(request(TextSelection(0, 6)), text))
    }

    /** Part link, part plain: a new link over the selection, as the ribbon's Link button makes. */
    @Test
    fun aSelectionReachingPastALinkInsertsOne() {
        assertEquals(TextSelection(7, 17) to LinkTarget("limits and", null),
            linkEditFor(request(TextSelection(7, 17), link), text))
    }

    @Test
    fun aCaretOffAnyLinkHasNothingToLink() {
        assertNull(linkEditFor(request(TextSelection(2)), text))
        assertNull(linkEditFor(request(TextSelection(9), link), null), "a box that is gone has nothing to link")
    }
}
