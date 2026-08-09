package de.syntaxfehler.ligpsport.ui.strava

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import de.syntaxfehler.ligpsport.strava.StravaAuth
import de.syntaxfehler.ligpsport.strava.StravaUploader
import kotlinx.coroutines.launch

/**
 * Lands the `ligpsport://localhost` OAuth redirect, trades the code for
 * tokens, and gets out of the way.
 *
 * No UI of its own: it's translucent and finishes as soon as the
 * exchange resolves, so the user comes back to wherever they were in
 * the app with a toast telling them how it went.
 *
 * The theme must NOT be `Theme.NoDisplay`, which looks like the
 * obvious fit but requires `finish()` before `onResume()` returns —
 * the token exchange is a network round-trip, so the framework
 * force-finishes the activity mid-flight and the tokens are lost.
 * `Theme.Translucent.NoTitleBar` is equally invisible and allows
 * finishing asynchronously.
 */
class StravaAuthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle()
    }

    private fun handle() {
        when (val callback = StravaAuth.parseCallback(intent?.data)) {
            is StravaAuth.Callback.Error -> {
                toastAndFinish("Strava sign-in failed: ${callback.reason}")
            }
            is StravaAuth.Callback.Code -> {
                lifecycleScope.launch {
                    val res = StravaUploader.connect(this@StravaAuthActivity, callback.code)
                    val msg = when (res) {
                        is StravaUploader.Result.Success ->
                            if (res.fileName.isNotBlank()) "Connected to Strava as ${res.fileName}"
                            else "Connected to Strava"
                        is StravaUploader.Result.Failure -> "Strava sign-in failed: ${res.reason}"
                    }
                    toastAndFinish(msg)
                }
            }
        }
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
