package de.syntaxfehler.ligpsport.strava

import android.content.Context
import android.util.Log
import de.syntaxfehler.ligpsport.BuildConfig
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import kotlinx.coroutines.delay
import java.io.File

/**
 * High-level "get this ride onto Strava" orchestration, in the spirit
 * of [UploadPipeline]: fetch the FIT off the BSC200 if it isn't already
 * cached, make sure the access token is live, POST, then poll until
 * Strava has actually turned the upload into an activity.
 */
object StravaUploader {
    private const val TAG = "StravaUploader"

    /** Refresh a bit early — a token that expires mid-upload is a wasted BLE fetch. */
    private const val EXPIRY_MARGIN_SECONDS = 120L

    private const val POLL_INTERVAL_MS = 1_500L
    private const val POLL_TIMEOUT_MS = 90_000L

    sealed interface Result {
        /**
         * [activityId] is null only if Strava accepted the file but we
         * stopped polling. [mutedFromFeed] is false when the upload
         * landed but the follow-up mute call didn't — the ride is on
         * Strava and visible in the feed, which the user should know.
         */
        data class Success(
            val activityId: Long?,
            val fileName: String,
            val mutedFromFeed: Boolean = false,
        ) : Result

        data class Failure(val reason: String) : Result
    }

    /**
     * @param timestamp the activity's device-side id (epoch seconds).
     * @param name optional activity title; Strava derives one from the
     *   start time and location when null.
     * @param muteFromFeed keep the upload out of followers' feeds so it
     *   raises no notifications. On by default. Note this is *not*
     *   privacy — see [StravaClient.muteActivity]; the account's default
     *   activity visibility is the only real control and the API cannot
     *   set it.
     */
    suspend fun upload(
        context: Context,
        timestamp: Long,
        name: String? = null,
        muteFromFeed: Boolean = true,
        clientFactory: () -> StravaClient = ::defaultClient,
    ): Result {
        if (!StravaStore.isConfigured()) return Result.Failure("no Strava API credentials in this build")
        val store = StravaStore(context)
        val stored = store.load() ?: return Result.Failure("not connected to Strava")

        val fit = ensureFit(context, timestamp)
            ?: return Result.Failure("could not get the FIT off the device")

        val client = clientFactory()
        return try {
            val tokens = ensureFreshToken(store, client, stored)
            val ticket = client.uploadFit(
                accessToken = tokens.accessToken,
                file = fit,
                name = name,
                // Deterministic per activity, so Strava's own duplicate
                // check rejects a second upload of the same ride rather
                // than creating a twin.
                externalId = fit.name,
            )
            ticket.error?.let { return Result.Failure(it) }
            val processed = awaitProcessing(client, tokens.accessToken, ticket, fit.name)
            // Mute after the fact: the activity id only exists once
            // Strava has finished processing, and there is no way to ask
            // for this at upload time.
            if (processed is Result.Success && processed.activityId != null && muteFromFeed) {
                val muted = runCatching {
                    client.muteActivity(tokens.accessToken, processed.activityId)
                }.isSuccess
                if (!muted) Log.w(TAG, "uploaded ${processed.activityId} but could not mute it")
                processed.copy(mutedFromFeed = muted)
            } else {
                processed
            }
        } catch (e: StravaClient.StravaException) {
            Result.Failure(e.message ?: "Strava error")
        } catch (e: Exception) {
            Log.w(TAG, "upload failed", e)
            Result.Failure("network error: ${e.message}")
        } finally {
            client.runCatching { close() }
        }
    }

    /** Swap an authorization code for tokens and persist them. */
    suspend fun connect(
        context: Context,
        code: String,
        clientFactory: () -> StravaClient = ::defaultClient,
    ): Result {
        val client = clientFactory()
        return try {
            val tokens = client.exchangeCode(code)
            StravaStore(context).save(tokens)
            Result.Success(activityId = null, fileName = tokens.athleteName ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            Result.Failure(e.message ?: "could not complete Strava sign-in")
        } finally {
            client.runCatching { close() }
        }
    }

    /**
     * Reuse an already-downloaded FIT, otherwise pull it over BLE. Same
     * cache-first rule the share button uses — an activity download runs
     * to tens of seconds, so paying for it twice is worth avoiding.
     */
    private suspend fun ensureFit(context: Context, timestamp: Long): File? {
        val cached = UploadPipeline.activityFitFile(context, timestamp)
        if (cached.isFile && cached.length() > 0L) return cached
        return when (val res = UploadPipeline.downloadActivity(context, timestamp)) {
            is UploadPipeline.Result.Success ->
                res.activitySavedPath?.let(::File)?.takeIf { it.isFile } ?: cached.takeIf { it.isFile }
            is UploadPipeline.Result.Failure -> null
        }
    }

    private suspend fun ensureFreshToken(
        store: StravaStore,
        client: StravaClient,
        stored: StravaStore.Tokens,
    ): StravaStore.Tokens {
        val now = System.currentTimeMillis() / 1000L
        if (stored.expiresAtEpochSeconds > now + EXPIRY_MARGIN_SECONDS) return stored
        // Strava can rotate the refresh token on this call, so persist
        // the whole response rather than just the access token.
        val refreshed = client.refresh(stored.refreshToken)
        val merged = refreshed.copy(athleteName = refreshed.athleteName ?: stored.athleteName)
        store.save(merged)
        return merged
    }

    private suspend fun awaitProcessing(
        client: StravaClient,
        accessToken: String,
        ticket: StravaClient.UploadStatus,
        fileName: String,
    ): Result {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var latest = ticket
        while (System.currentTimeMillis() < deadline) {
            latest.error?.let { return Result.Failure(it) }
            latest.activityId?.let { return Result.Success(it, fileName) }
            delay(POLL_INTERVAL_MS)
            latest = client.pollUpload(accessToken, ticket.id)
        }
        // Strava says mean processing is under 2 s; if we get here the
        // upload is probably fine but slow, so don't call it a failure.
        return Result.Success(activityId = null, fileName = fileName)
    }

    private fun defaultClient(): StravaClient =
        StravaClient(BuildConfig.STRAVA_CLIENT_ID, BuildConfig.STRAVA_CLIENT_SECRET)
}
