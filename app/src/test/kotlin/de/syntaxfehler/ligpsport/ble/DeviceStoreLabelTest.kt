package de.syntaxfehler.ligpsport.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pairing screen used to render an unpaired-but-nearby device as a
 * bare MAC address: `remove()` dropped the advertised name along with
 * the slot, and `BluetoothDevice.name` is routinely null during a scan.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceStoreLabelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun store() = DeviceStore(context).also { it.clear() }

    @Test
    fun advertised_name_survives_unpairing() {
        val store = store()
        store.add(name = "BSC200-1234", mac = mac)
        store.remove(mac)

        assertThat(store.list()).isEmpty()
        // Scan with no name available (the usual case post-unpair).
        assertThat(store.labelFor(mac)).isEqualTo("BSC200-1234")
    }

    @Test
    fun nickname_beats_the_advertised_name() {
        val store = store()
        store.add(name = "BSC200-1234", mac = mac)
        store.setNickname(mac, "Gravel bike")
        store.remove(mac)

        assertThat(store.labelFor(mac, advertised = "BSC200-1234")).isEqualTo("Gravel bike")
    }

    @Test
    fun a_fresh_scan_name_is_recorded_for_next_time() {
        val store = store()
        assertThat(store.labelFor(mac)).isNull()

        assertThat(store.labelFor(mac, advertised = "BSC200-1234")).isEqualTo("BSC200-1234")
        assertThat(store.labelFor(mac)).isEqualTo("BSC200-1234")
    }

    @Test
    fun labels_are_case_insensitive_on_the_mac() {
        val store = store()
        store.add(name = "BSC200-1234", mac = mac.lowercase())

        assertThat(store.labelFor(mac.lowercase())).isEqualTo("BSC200-1234")
        assertThat(store.labelFor(mac.uppercase())).isEqualTo("BSC200-1234")
    }

    @Test
    fun re_pairing_over_save_keeps_the_nickname() {
        val store = store()
        store.add(name = "BSC200-1234", mac = mac)
        store.setNickname(mac, "Gravel bike")

        store.save(name = "BSC200-1234", address = mac)

        assertThat(store.list().single().name).isEqualTo("Gravel bike")
    }
}
