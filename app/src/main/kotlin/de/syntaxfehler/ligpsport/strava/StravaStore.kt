package de.syntaxfehler.ligpsport.strava

import android.content.Context
import de.syntaxfehler.ligpsport.BuildConfig

/**
 * Persisted Strava OAuth state. Mirrors [de.syntaxfehler.ligpsport.ble.DeviceStore]
 * — plain `SharedPreferences` in app-private storage, no encryption.
 *
 * That's a deliberate match to what this app already does with the
 * paired MAC, and the practical exposure is the same: app-private
 * prefs are readable only by this uid (or by anyone with root / an
 * unlocked bootloader). The stored refresh token grants
 * `activity:write` on the user's Strava account until revoked at
 * https://www.strava.com/settings/apps, so treat a rooted or shared
 * phone accordingly.
 */
class StravaStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Access tokens live six hours; [expiresAtEpochSeconds] is Strava's
     * own absolute expiry, not a duration we computed, so clock skew on
     * our side can't silently shorten it.
     */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSeconds: Long,
        val athleteName: String?,
    )

    fun save(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochSeconds)
            .putString(KEY_ATHLETE, tokens.athleteName)
            .apply()
    }

    fun load(): Tokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return Tokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSeconds = prefs.getLong(KEY_EXPIRES_AT, 0L),
            athleteName = prefs.getString(KEY_ATHLETE, null),
        )
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_ATHLETE)
            .apply()
    }

    fun isConnected(): Boolean = load() != null

    fun athleteName(): String? = prefs.getString(KEY_ATHLETE, null)

    companion object {
        private const val PREFS = "ligpsport.strava"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_ATHLETE = "athlete_name"

        /**
         * Whether this build has API credentials baked in at all. The
         * whole Strava surface hides itself when false, rather than
         * offering a button that can only fail.
         */
        fun isConfigured(): Boolean =
            BuildConfig.STRAVA_CLIENT_ID.isNotBlank() &&
                BuildConfig.STRAVA_CLIENT_SECRET.isNotBlank()
    }
}
