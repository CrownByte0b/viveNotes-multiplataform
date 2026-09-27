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

    private val stamp = ZonedDateTime.of(2026, 9, 22, 14, 5, 0, 0, newYork).toInstant().toEpochMilli()

    /** Android's page list `relativeDate`, step by step. */
    @Test
    fun theModifiedLabelReadsLikeTheAndroidPageList() {
        fun label(ago: Long) = formatUpdated(stamp, now = stamp + ago, zone = newYork, locale = Locale.US)

        assertEquals("Just now", label(59_999))
        assertEquals("1 min ago", label(60_000))
        assertEquals("59 min ago", label(3_599_999))
        assertEquals("2:05 PM", label(3_600_000))
        assertEquals("2:05 PM", label(86_399_999))
        assertEquals("Sep 22, 2026", label(86_400_000))
    }

    @Test
    fun anUnrecordedModificationIsBlank() {
        assertEquals("", formatUpdated(0L, now = stamp, zone = newYork, locale = Locale.US))
    }
}

