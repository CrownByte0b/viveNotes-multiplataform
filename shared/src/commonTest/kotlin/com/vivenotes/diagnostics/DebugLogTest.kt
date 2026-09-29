package com.vivenotes.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class DebugLogTest {
    @Test
    fun disabledLogDoesNotBuildOrWriteMessages() {
        val lines = mutableListOf<String>()
        var built = false
        val log = DebugLog(output = lines::add)

        log.event("session") { built = true; "opened" }
        log.failure("storage", "read", IllegalStateException("disk unavailable"))

        assertEquals(false, built)
        assertEquals(emptyList(), lines)
    }

    @Test
    fun enabledLogWritesConciseEventsAndFailures() {
        val lines = mutableListOf<String>()
        val log = DebugLog(enabled = true, output = lines::add)

        log.event("session") { "opening workspace" }
        log.failure("storage", "page read", IllegalStateException("disk\nunavailable"))

        assertEquals(listOf(
            "ViveNotes DEBUG [session] opening workspace",
            "ViveNotes DEBUG [storage] page read failed: IllegalStateException: disk unavailable",
        ), lines)
    }
}
