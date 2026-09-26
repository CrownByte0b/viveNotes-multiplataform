package com.vivenotes.workspace

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class CreatedLabelTest {

    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun readsLikeTheAndroidPageHeader() {
        val created = ZonedDateTime.of(2026, 9, 25, 19, 30, 0, 0, newYork).toInstant().toEpochMilli()

        assertEquals("Friday, September 25, 2026    7:30 PM", formatCreated(created, newYork, Locale.US))
    }

    @Test
    fun followsTheUsersTimeZone() {
        val created = ZonedDateTime.of(2026, 9, 25, 23, 30, 0, 0, newYork).toInstant().toEpochMilli()

        assertEquals("Saturday, September 26, 2026    4:30 AM", formatCreated(created, ZoneId.of("Europe/London"), Locale.US))
    }

    @Test
    fun anUnrecordedCreationIsBlank() {
        assertEquals("", formatCreated(0L, newYork, Locale.US))
    }
}
