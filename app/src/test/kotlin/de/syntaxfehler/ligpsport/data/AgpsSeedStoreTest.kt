package de.syntaxfehler.ligpsport.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.ble.DeviceStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uploads used to re-fetch and re-push AssistNow data every single time,
 * costing an HTTP round-trip plus a multi-kilobyte BLE transfer even when
 * the device had been seeded minutes earlier. The gate is only safe if
 * the freshness rule is exact and the record outlives an unpair.
 */
@RunWith(RobolectricTestRunner::class)
class AgpsSeedStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val otherMac = "11:22:33:44:55:66"
    private val ttl = AgpsSeedStore.DEFAULT_TTL_MS

    private fun store() = AgpsSeedStore(context).also { it.clear() }

    @Test
    fun a_device_that_was_never_seeded_is_stale() {
        val store = store()

        assertThat(store.get(mac)).isNull()
        assertThat(store.isFresh(mac)).isFalse()
        assertThat(store.expiresAt(mac)).isNull()
    }

    @Test
    fun freshness_boundary_is_exclusive() {
        val now = 1_700_000_000_000L
        val seededAt = now - ttl

        assertThat(AgpsSeedStore.isFresh(null, now, ttl)).isFalse()
        assertThat(AgpsSeedStore.isFresh(seededAt + 1, now, ttl)).isTrue()
        assertThat(AgpsSeedStore.isFresh(seededAt, now, ttl)).isFalse()
        assertThat(AgpsSeedStore.isFresh(seededAt - 1, now, ttl)).isFalse()
    }

    @Test
    fun a_clock_that_jumped_backwards_counts_as_stale() {
        val now = 1_700_000_000_000L

        assertThat(AgpsSeedStore.isFresh(now + 60_000, now, ttl)).isFalse()
    }

    @Test
    fun a_recorded_seed_expires_after_the_ttl() {
        val store = store()
        val at = 1_700_000_000_000L
        store.record(mac, bytes = 12_345, at = at)

        assertThat(store.get(mac)).isEqualTo(AgpsSeedStore.Seed(seededAt = at, bytes = 12_345))
        assertThat(store.expiresAt(mac)).isEqualTo(at + ttl)
        assertThat(store.isFresh(mac, now = at + ttl - 1)).isTrue()
        assertThat(store.isFresh(mac, now = at + ttl)).isFalse()
    }

    @Test
    fun seeding_one_device_leaves_the_other_stale() {
        val store = store()
        val at = 1_700_000_000_000L
        store.record(mac, bytes = 4_096, at = at)

        assertThat(store.isFresh(mac, now = at + 1_000)).isTrue()
        assertThat(store.isFresh(otherMac, now = at + 1_000)).isFalse()
    }

    @Test
    fun the_mac_is_matched_case_insensitively() {
        val store = store()
        val at = 1_700_000_000_000L
        store.record(mac.lowercase(), bytes = 2_048, at = at)

        assertThat(store.isFresh(mac.uppercase(), now = at + 1_000)).isTrue()
        assertThat(store.get(mac.uppercase())?.bytes).isEqualTo(2_048)
    }

    @Test
    fun the_seed_record_survives_unpairing_and_re_pairing() {
        val store = store()
        val devices = DeviceStore(context).also { it.clear() }
        val at = 1_700_000_000_000L
        devices.add(name = "BSC200-1234", mac = mac)
        store.record(mac, bytes = 8_192, at = at)

        devices.remove(mac)
        devices.add(name = "BSC200-1234", mac = mac)

        assertThat(store.isFresh(mac, now = at + 1_000)).isTrue()
        assertThat(store.get(mac)?.bytes).isEqualTo(8_192)
    }

    @Test
    fun forget_drops_only_the_named_device() {
        val store = store()
        val at = 1_700_000_000_000L
        store.record(mac, bytes = 1_024, at = at)
        store.record(otherMac, bytes = 2_048, at = at)

        store.forget(mac)

        assertThat(store.get(mac)).isNull()
        assertThat(store.get(otherMac)?.bytes).isEqualTo(2_048)
    }
}
