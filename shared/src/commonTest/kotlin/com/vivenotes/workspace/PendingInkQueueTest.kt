package com.vivenotes.workspace

import com.vivenotes.data.InkEdit
import com.vivenotes.data.PendingInkEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PendingInkQueueTest {
    private fun edit(index: Int) = PendingInkEdit("command-$index", "page", InkEdit.EraseStrokes(setOf("stroke-$index")))

    @Test
    fun aLargeBacklogRetainsExactPrefixOrderAndOldSnapshots() {
        var queue = emptyList<PendingInkEdit>()
        repeat(10_000) { queue = queue.queueInk(edit(it)) }
        val retained = queue
        repeat(10_000) { index ->
            assertEquals(edit(index), queue.first())
            queue = queue.acknowledgeFirstInk("command-$index")
        }
        assertTrue(queue.isEmpty())
        assertEquals(10_000, retained.size)
        assertEquals(List(10_000, ::edit), retained)
    }

    @Test
    fun appendingDuringAWriteAndBranchingDoesNotLoseAnyCommand() {
        val initial = listOf(edit(0), edit(1)).queueInk(listOf(edit(2), edit(3)))
        val pendingWrite = initial.first()
        val appended = initial.queueInk(edit(4))
        val acknowledged = appended.acknowledgeFirstInk(pendingWrite.id)
        assertEquals((1..4).map(::edit), acknowledged)
        assertEquals((0..3).map(::edit), initial)
        assertEquals(edit(2), acknowledged[1])
        assertEquals((1..4).map(::edit).hashCode(), acknowledged.hashCode())
        assertFailsWith<IllegalStateException> { acknowledged.acknowledgeFirstInk("command-4") }
        assertEquals((1..4).map(::edit), acknowledged)
    }
}
