package de.syntaxfehler.ligpsport.ui.settings

import de.syntaxfehler.ligpsport.data.AgpsSeedStore

/**
 * Human-readable strings for the per-device AGPS seed state.
 *
 * Kept as pure functions of (timestamp, now, ttl) so the wording is unit
 * testable and — more importantly — so the validity text is derived from
 * the very same [AgpsSeedStore.isFresh] rule that decides whether an
 * upload re-seeds. A UI that disagreed with the actual behaviour would be
 * worse than no UI at all.
 */
object AgpsSeedLabels {

    /** "never", "just now", "12 minutes ago", "3 hours ago", "2 days ago". */
    fun lastSeeded(seededAt: Long?, now: Long): String {
        if (seededAt == null || seededAt <= 0L) return "never"
        val age = now - seededAt
        if (age < 0L) return "just now"
        return when {
            age < MINUTE -> "just now"
            age < HOUR -> "${age / MINUTE} ${plural(age / MINUTE, "minute")} ago"
            age < DAY -> "${age / HOUR} ${plural(age / HOUR, "hour")} ago"
            else -> "${age / DAY} ${plural(age / DAY, "day")} ago"
        }
    }

    /**
     * "not seeded yet", "expired", or "valid for another 1 h 48 min".
     * Mirrors [AgpsSeedStore.isFresh] exactly, boundary included.
     */
    fun validity(seededAt: Long?, now: Long, ttlMs: Long): String {
        if (seededAt == null || seededAt <= 0L) return "not seeded yet"
        if (!AgpsSeedStore.isFresh(seededAt, now, ttlMs)) return "expired"
        val left = seededAt + ttlMs - now
        val hours = left / HOUR
        val minutes = (left % HOUR) / MINUTE
        return when {
            hours > 0 -> "valid for another $hours h $minutes min"
            minutes > 0 -> "valid for another $minutes min"
            // Sub-minute leftovers would render as "0 min", which reads
            // like a bug rather than like "about to expire".
            else -> "valid for less than a minute"
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) unit else "${unit}s"

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
