package de.syntaxfehler.ligpsport.data

import android.content.Context

/**
 * Per-device record of the last successful AGPS seed.
 *
 * AssistNow Online `datatype=eph` payloads describe where the GNSS
 * satellites are *right now*; they go stale after a couple of hours.
 * Re-sending them on every route upload costs an HTTP round-trip plus a
 * multi-kilobyte BLE transfer for no benefit, so [isFresh] gates both
 * halves of the operation.
 *
 * State is keyed by MAC rather than kept globally: with the multi-device
 * fan-out one computer can be freshly seeded while another was switched
 * off during the last upload and still needs the data.
 *
 * The record deliberately lives in its own SharedPreferences file, so it
 * survives `DeviceStore.remove()` / `DeviceStore.clear()` the same way
 * nicknames do — unpairing and re-pairing a device shouldn't throw away
 * a seed that is still valid and force a needless re-upload. [clear]
 * exists for the explicit "forget everything" path.
 */
class AgpsSeedStore(
    context: Context,
    /** How long a seed is considered usable. See [DEFAULT_TTL_MS]. */
    val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One recorded seed. [bytes] is what the device actually accepted. */
    data class Seed(val seededAt: Long, val bytes: Int)

    /** Last successful seed for [mac], or null if we never seeded it. */
    fun get(mac: String): Seed? {
        val canonical = mac.uppercase()
        val at = prefs.getLong(keySeededAt(canonical), 0L)
        if (at <= 0L) return null
        return Seed(seededAt = at, bytes = prefs.getInt(keyBytes(canonical), 0))
    }

    /**
     * Record a *successful* push. Callers must not invoke this when the
     * device rejected the payload or the transfer threw — the whole
     * point of the timestamp is that the data is known to be on the
     * device, so a failure has to leave the previous (stale) state in
     * place and let the next upload retry.
     */
    fun record(mac: String, bytes: Int, at: Long = System.currentTimeMillis()) {
        val canonical = mac.uppercase()
        prefs.edit()
            .putLong(keySeededAt(canonical), at)
            .putInt(keyBytes(canonical), bytes)
            .apply()
    }

    /** True when [mac] was seeded recently enough to skip re-seeding. */
    fun isFresh(mac: String, now: Long = System.currentTimeMillis()): Boolean =
        isFresh(get(mac)?.seededAt, now, ttlMs)

    /**
     * When the current seed for [mac] stops being usable, or null when
     * the device was never seeded.
     */
    fun expiresAt(mac: String): Long? = get(mac)?.let { it.seededAt + ttlMs }

    /** Drop the record for a single device. */
    fun forget(mac: String) {
        val canonical = mac.uppercase()
        prefs.edit().remove(keySeededAt(canonical)).remove(keyBytes(canonical)).apply()
    }

    /** Drop every record. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun keySeededAt(mac: String) = "$SEEDED_AT_PREFIX$mac"
    private fun keyBytes(mac: String) = "$BYTES_PREFIX$mac"

    companion object {
        /**
         * u-blox rates AssistNow Online ephemeris data as useful for
         * roughly 2–4 hours. Two hours is the conservative end: the seed
         * is still genuinely helpful when it is used, and a user who
         * uploads two routes back to back — the case that motivated
         * this — pays the fetch exactly once.
         */
        const val DEFAULT_TTL_MS = 2L * 60 * 60 * 1000

        /**
         * Pure freshness predicate, split out so the gating logic is
         * testable without Android. Never seeded → stale. The boundary
         * is exclusive: at exactly [ttlMs] the data has expired.
         */
        fun isFresh(seededAt: Long?, now: Long, ttlMs: Long): Boolean {
            if (seededAt == null || seededAt <= 0L) return false
            val age = now - seededAt
            // A negative age means the clock moved backwards (timezone
            // fiddling, NTP correction). Treat that as stale rather than
            // as "valid forever".
            if (age < 0L) return false
            return age < ttlMs
        }

        private const val PREFS = "ligpsport.agps_seed"
        private const val SEEDED_AT_PREFIX = "seeded_at_"
        private const val BYTES_PREFIX = "bytes_"
    }
}
