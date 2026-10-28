package com.vivenotes.workspace

import com.vivenotes.model.Outline
import com.vivenotes.model.newTable
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasPasteLayoutTest {
    @Test
    fun stackedObjectsLeaveAMarginAfterTheirMeasuredHeights() {
        val text = Outline.Text.empty()
        val table = newTable(2, 2)
        val image = Outline.Image("picture", attachmentId = "attachment", height = 160f)
        val pasted = stackPastedOutlines(listOf(text, table, image),
            mapOf(text.id to 80f, table.id to 480f, image.id to 160f))
        assertEquals(listOf(0f, 104f, 608f), pasted.map { it.y })
        assertEquals(listOf(text.id, table.id, image.id), pasted.map { it.id })
        assertEquals(text, pasted.first())
        assertEquals(table.copy(y = 104f), pasted[1])
        assertEquals(image.copy(y = 608f), pasted[2])
    }
}
