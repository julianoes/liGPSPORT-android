package de.syntaxfehler.ligpsport.strava

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The OAuth redirect is the one place a user can hand us a
 * half-successful result — approving sign-in but declining the upload
 * scope — so the parsing is worth pinning down.
 */
@RunWith(RobolectricTestRunner::class)
class StravaAuthTest {

    @Test
    fun extracts_code_when_write_scope_granted() {
        val uri = Uri.parse(
            "ligpsport://localhost?state=&code=abc123&scope=read,activity:write",
        )
        val result = StravaAuth.parseCallback(uri)
        assertThat(result).isInstanceOf(StravaAuth.Callback.Code::class.java)
        assertThat((result as StravaAuth.Callback.Code).code).isEqualTo("abc123")
    }

    @Test
    fun rejects_when_user_declines() {
        val result = StravaAuth.parseCallback(Uri.parse("ligpsport://localhost?error=access_denied"))
        assertThat(result).isInstanceOf(StravaAuth.Callback.Error::class.java)
        assertThat((result as StravaAuth.Callback.Error).reason).contains("access_denied")
    }

    /**
     * Strava's consent screen lets the athlete untick the upload
     * permission while still approving sign-in. Catching it here beats
     * discovering it on the first 401 from /uploads.
     */
    @Test
    fun rejects_when_write_scope_withheld() {
        val uri = Uri.parse("ligpsport://localhost?code=abc123&scope=read")
        val result = StravaAuth.parseCallback(uri)
        assertThat(result).isInstanceOf(StravaAuth.Callback.Error::class.java)
        assertThat((result as StravaAuth.Callback.Error).reason).contains("activity:write")
    }

    @Test
    fun rejects_callback_without_code() {
        val result = StravaAuth.parseCallback(Uri.parse("ligpsport://localhost"))
        assertThat(result).isInstanceOf(StravaAuth.Callback.Error::class.java)
    }

    @Test
    fun rejects_null_uri() {
        assertThat(StravaAuth.parseCallback(null)).isInstanceOf(StravaAuth.Callback.Error::class.java)
    }
}
