package de.syntaxfehler.ligpsport.data

import java.util.concurrent.atomic.AtomicReference

/**
 * In-process holder for the user's current route session — the
 * destination they picked and (optionally) the GPX the active router
 * planned for it. Survives Compose navigation (which destroys
 * Composable `remember` state), so going Map → Upload → Back to map
 * doesn't lose the planned polyline.
 *
 * Deliberately not persisted across process death — a fresh launch
 * starts with no destination.
 *
 * Cleared explicitly by the user via the "X" on the destination card
 * (or when they pick a new destination on the map).
 */
object RouteSessionStore {

    data class Stop(
        val lat: Double,
        val lon: Double,
        val label: String? = null,
    )

    data class Session(
        val destinationName: String,
        val destinationLat: Double,
        val destinationLon: Double,
        val plannedGpx: ByteArray? = null,
        /** Ordered list of intermediate stops in route order. Empty
         *  when the route is point-to-point. Used by "restore from
         *  previous rides" so reopening a multi-stop ride brings all
         *  its vias back onto the map. */
        val intermediates: List<Stop> = emptyList(),
        /** Explicit start location override. Null means "follow the
         *  live GPS fix" (the editor's default behaviour). */
        val startLat: Double? = null,
        val startLon: Double? = null,
        /** Sticky display name for the Start row — preserved so a
         *  restored ride doesn't read "Your location" when the user
         *  had previously entered a specific origin. */
        val startLabel: String? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Session) return false
            return destinationName == other.destinationName &&
                destinationLat == other.destinationLat &&
                destinationLon == other.destinationLon &&
                (plannedGpx?.contentEquals(other.plannedGpx) ?: (other.plannedGpx == null)) &&
                intermediates == other.intermediates &&
                startLat == other.startLat &&
                startLon == other.startLon &&
                startLabel == other.startLabel
        }

        override fun hashCode(): Int {
            var r = destinationName.hashCode()
            r = 31 * r + destinationLat.hashCode()
            r = 31 * r + destinationLon.hashCode()
            r = 31 * r + (plannedGpx?.contentHashCode() ?: 0)
            r = 31 * r + intermediates.hashCode()
            r = 31 * r + (startLat?.hashCode() ?: 0)
            r = 31 * r + (startLon?.hashCode() ?: 0)
            r = 31 * r + (startLabel?.hashCode() ?: 0)
            return r
        }
    }

    private val ref = AtomicReference<Session?>(null)

    fun get(): Session? = ref.get()
    fun set(session: Session) {
        ref.set(session)
    }
    fun clear() {
        ref.set(null)
    }

    /** Update only the GPX field of the current session, keeping the
     *  destination intact. No-op if there is no current session. */
    fun setPlannedGpx(gpx: ByteArray?) {
        while (true) {
            val cur = ref.get() ?: return
            val next = cur.copy(plannedGpx = gpx)
            if (ref.compareAndSet(cur, next)) return
        }
    }
}
