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
