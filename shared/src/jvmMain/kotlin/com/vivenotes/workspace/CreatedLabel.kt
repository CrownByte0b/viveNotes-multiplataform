package com.vivenotes.workspace

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A page's created stamp as the Android page header writes it — "Friday, September 25, 2026    7:30
 * PM" — in the user's locale and time zone. Zero, a page whose creation was never recorded, is blank.
 */
fun formatCreated(
    timestamp: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (timestamp == 0L) return ""
    val created = Instant.ofEpochMilli(timestamp).atZone(zone)
    val day = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", locale).format(created)
    val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(created)
    return "$day    $time"
}

/**
 * A page's modified stamp as the Android page list writes it: "Just now" inside a minute, "5 min
 * ago" inside the hour, the time of day inside a day, and "Sep 22, 2026" beyond. Zero is blank.
 */
fun formatUpdated(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (timestamp == 0L) return ""
    val elapsed = now - timestamp
    val updated = Instant.ofEpochMilli(timestamp).atZone(zone)
    return when {
        elapsed < 60_000 -> "Just now"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
        elapsed < 86_400_000 -> DateTimeFormatter.ofPattern("h:mm a", locale).format(updated)
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(updated)
    }
}
