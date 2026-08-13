package de.syntaxfehler.ligpsport.data

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent history of every route the user has uploaded.
 *
 * The brief was "all data should be stored, not only the track or the
 * points" — so each entry carries the full set of editor inputs (start,
 * via, destination + labels) AND the resolved GPX polyline, so the user
 * can re-open a ride and immediately see what the device received. Tap
 * → restore replays the snapshot into MapScreen via [RouteSessionStore];
 * an edit afterwards re-plans normally.
 *
 * Capped at [MAX_RIDES] with FIFO eviction — the SharedPreferences blob
 * is one JSON string and a few dozen routes is already 100s of KB once
 * GPX bytes are Base64-encoded; unbounded growth would eventually stall
 * the prefs read on cold start.
 */
class PreviousRidesStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class SavedWaypoint(
        val lat: Double,
        val lon: Double,
        val label: String? = null,
    )

    @Serializable
    data class Ride(
        val id: String,
        val savedAt: Long,
        val destinationLabel: String,
        val destinationLat: Double,
        val destinationLon: Double,
        /** "Your location" when null/blank — see startLat/startLon for
         *  the live-fix vs. explicit-override distinction. */
        val startLabel: String,
        /** Null when the upload was anchored to the live GPS fix at
         *  upload time. A subsequent restore lands without a sticky
         *  start override, so the new ride routes from wherever the
         *  user currently is. */
        val startLat: Double? = null,
        val startLon: Double? = null,
        val intermediates: List<SavedWaypoint> = emptyList(),
        val routerId: String,
        val fileName: String,
        /** Base64-encoded GPX polyline so the JSON serialiser stays
         *  text-only. ByteArray-via-JSON-array would balloon the
         *  encoded size ~3×. */
        val gpxBase64: String,
        val distanceM: Double,
    ) {
        val gpxBytes: ByteArray get() = Base64.decode(gpxBase64, Base64.NO_WRAP)

        /**
         * "Start → Dest" or "Start → Dest +N stops" per the user's
         * naming spec. The labels can be quite long when reverse-
         * geocoded — leave that to the rendering side.
         */
        val displayName: String
            get() {
                val base = "$startLabel → $destinationLabel"
                return if (intermediates.isEmpty()) base
                else "$base +${intermediates.size} stop${if (intermediates.size == 1) "" else "s"}"
            }
    }

    fun list(): List<Ride> {
        val raw = prefs.getString(KEY_RIDES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Ride.serializer()),
                raw,
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Append a ride to the front of the list. Drops the oldest entries
     * when we'd otherwise exceed [MAX_RIDES]. Returns the new list size.
     */
    fun add(ride: Ride): Int {
        val existing = list().filterNot { it.id == ride.id }
        val next = (listOf(ride) + existing).take(MAX_RIDES)
        persist(next)
        return next.size
    }

    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().remove(KEY_RIDES).apply()
    }

    private fun persist(rides: List<Ride>) {
        // Explicit serialiser — Kotlin can't infer for List<Ride>
        // through the generic encodeToString extension.
        val payload = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Ride.serializer()),
            rides,
        )
        prefs.edit().putString(KEY_RIDES, payload).apply()
    }

    companion object {
        const val PREFS = "ligpsport.previous_rides"
        const val KEY_RIDES = "rides"
        const val MAX_RIDES = 50

        fun encodeGpx(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
