package de.syntaxfehler.ligpsport.strava

import android.content.Intent
import android.net.Uri
import de.syntaxfehler.ligpsport.BuildConfig

/**
 * OAuth entry/exit points for the Strava connect flow.
 *
 * Strava's mobile recipe (https://developers.strava.com/docs/authentication/)
 * is an implicit intent to `/oauth/mobile/authorize`: the Strava app
 * handles it when installed, and the browser picks it up otherwise —
 * both land back on [REDIRECT_URI].
 *
 * The redirect is a custom scheme pointing at `localhost` because
 * Strava validates the redirect against the "Authorization Callback
 * Domain" registered for the API application, and a native app has no
 * real domain to offer. Register `localhost` there for this to work.
 */
object StravaAuth {
    const val REDIRECT_URI = "ligpsport://localhost"

    /** `activity:write` is the minimum for uploading. We ask for nothing else. */
    private const val SCOPE = "activity:write"

    fun authorizeIntent(): Intent {
        val uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", BuildConfig.STRAVA_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", SCOPE)
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /**
     * Pull the result out of the redirect. Strava appends `code` on
     * approval and `error=access_denied` when the user declines; it also
     * echoes the granted `scope`, which can be narrower than requested
     * if the user unticks the upload permission on the consent screen —
     * worth catching here rather than at the first failed upload.
     */
    fun parseCallback(uri: Uri?): Callback {
        if (uri == null) return Callback.Error("no callback data")
        uri.getQueryParameter("error")?.let { return Callback.Error(it) }
        val code = uri.getQueryParameter("code")
            ?: return Callback.Error("no authorization code in callback")
        val scope = uri.getQueryParameter("scope").orEmpty()
        if (!scope.split(',').contains(SCOPE)) {
            return Callback.Error("upload permission ($SCOPE) was not granted")
        }
        return Callback.Code(code)
    }

    sealed interface Callback {
        data class Code(val code: String) : Callback
        data class Error(val reason: String) : Callback
    }
}
