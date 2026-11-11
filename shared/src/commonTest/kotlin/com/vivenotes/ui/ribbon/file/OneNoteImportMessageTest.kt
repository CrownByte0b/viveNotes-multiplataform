package com.vivenotes.ui.ribbon.file

import kotlin.test.Test
import kotlin.test.assertEquals

class OneNoteImportMessageTest {
    @Test
    fun anImportWithNoOmissionsSaysWhereItCameFrom() {
        assertEquals("Biology was imported from OneNote.", oneNoteImportMessage("Biology", emptyList()))
    }

    @Test
    fun aSingleOmissionUsesTheSingularAndCapitalizesTheWarning() {
        assertEquals("Biology was imported from OneNote. 1 item was left out:\n• Omitted an embedded file",
            oneNoteImportMessage("Biology", listOf("omitted an embedded file")))
    }

    @Test
    fun repeatedOmissionsAreGroupedInFirstSeenOrderWithTheirCounts() {
        assertEquals("Biology was imported from OneNote. 3 items were left out:\n" +
            "• Omitted an embedded file (2×)\n• Omitted a link",
            oneNoteImportMessage("Biology", listOf("omitted an embedded file", "omitted a link", "omitted an embedded file")))
    }
}
