package de.syntaxfehler.ligpsport.ui.settings

import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.data.AgpsSeedStore
import org.junit.Test

/**
 * The Settings text has to agree with the skip rule in
 * [AgpsSeedStore.isFresh] — "valid for another 3 min" next to an upload
 * that re-seeds anyway would be worse than showing nothing.
 */
class AgpsSeedLabelsTest {

    private val now = 1_700_000_000_000L
    private val ttl = AgpsSeedStore.DEFAULT_TTL_MS
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun a_device_that_was_never_seeded_says_so() {
        assertThat(AgpsSeedLabels.lastSeeded(null, now)).isEqualTo("never")
        assertThat(AgpsSeedLabels.validity(null, now, ttl)).isEqualTo("not seeded yet")
    }

    @Test
    fun ages_are_rendered_in_the_largest_useful_unit() {
        assertThat(AgpsSeedLabels.lastSeeded(now - 10_000, now)).isEqualTo("just now")
        assertThat(AgpsSeedLabels.lastSeeded(now - minute, now)).isEqualTo("1 minute ago")
        assertThat(AgpsSeedLabels.lastSeeded(now - 12 * minute, now)).isEqualTo("12 minutes ago")
        assertThat(AgpsSeedLabels.lastSeeded(now - hour, now)).isEqualTo("1 hour ago")
        assertThat(AgpsSeedLabels.lastSeeded(now - 5 * hour, now)).isEqualTo("5 hours ago")
        assertThat(AgpsSeedLabels.lastSeeded(now - 49 * hour, now)).isEqualTo("2 days ago")
    }

    @Test
    fun validity_counts_down_the_remaining_ttl() {
        assertThat(AgpsSeedLabels.validity(now - 12 * minute, now, ttl))
            .isEqualTo("valid for another 1 h 48 min")
        assertThat(AgpsSeedLabels.validity(now - (ttl - 30 * minute), now, ttl))
            .isEqualTo("valid for another 30 min")
        assertThat(AgpsSeedLabels.validity(now - (ttl - 10_000), now, ttl))
            .isEqualTo("valid for less than a minute")
    }

    @Test
    fun an_expired_seed_is_labelled_expired_at_the_same_boundary_the_gate_uses() {
        // One millisecond before the gate flips: still valid, and with
        // so little left it renders as the sub-minute wording.
        assertThat(AgpsSeedLabels.validity(now - ttl + 1, now, ttl))
            .isEqualTo("valid for less than a minute")
        assertThat(AgpsSeedLabels.validity(now - ttl, now, ttl)).isEqualTo("expired")
        assertThat(AgpsSeedLabels.validity(now - 2 * ttl, now, ttl)).isEqualTo("expired")
    }
}
