package de.syntaxfehler.ligpsport.ble

import android.content.Context

/**
 * Persistent record of the iGPSPORT devices the user paired with. The
 * BSC200 advertises bondable but the protocol works fine without
 * bonding; remembering the MAC and reconnecting by address gives us
 * the same UX without the bond-handshake quirks reported on Pixel 7 /
 * Android 14.
 *
 * Multi-device: up to [MAX_DEVICES] paired computers are kept,
 * indexed in insertion order. Slot 0 is the "primary" device — every
 * single-device legacy caller resolves to it via [address] / [name].
 * Newer call sites that explicitly want to fan out (uploads, the
 * nav-status overlay, per-device sub-screens) walk [list] instead.
 *
 * Storage shape (SharedPreferences keys):
 *   - `address_0` / `name_0`, `address_1` / `name_1`, …
 *   - Legacy `address` / `name` (single-device layout) is migrated
 *     into slot 0 on the first multi-aware read so existing installs
 *     don't have to re-pair.
 */
class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyKeysIfPresent()
    }

    /** One stored pairing. [mac] is canonical (`XX:XX:XX:XX:XX:XX`). */
    data class Paired(val mac: String, val name: String?)

    /** Snapshot of every pairing, slot-ordered. Empty when nothing is paired. */
    fun list(): List<Paired> = buildList {
        for (i in 0 until MAX_DEVICES) {
            val mac = prefs.getString(keyAddress(i), null) ?: break
            add(Paired(mac = mac, name = prefs.getString(keyName(i), null)))
        }
    }

    /**
     * Append a new device or update its name if it's already paired.
     * Returns true when the slot was added, false when the cap of
     * [MAX_DEVICES] was already reached (and the MAC isn't already
     * paired).
     */
    fun add(name: String?, mac: String): Boolean {
        val canonical = mac.uppercase()
        val current = list().toMutableList()
        val existingIdx = current.indexOfFirst { it.mac.equals(canonical, ignoreCase = true) }
        if (existingIdx >= 0) {
            current[existingIdx] = Paired(canonical, name ?: current[existingIdx].name)
        } else {
            if (current.size >= MAX_DEVICES) return false
            current += Paired(canonical, name)
        }
        writeAll(current)
        return true
    }

    /** Remove the given MAC. No-op if it wasn't paired. */
    fun remove(mac: String) {
        val canonical = mac.uppercase()
        writeAll(list().filterNot { it.mac.equals(canonical, ignoreCase = true) })
    }

    /** Forget every paired device. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Compatibility shim: the first paired device's MAC. New call sites
     * that target a specific device should use [list] and pick the
     * right slot themselves.
     */
    fun address(): String? = list().firstOrNull()?.mac

    /** Compatibility shim mirroring [address]. */
    fun name(): String? = list().firstOrNull()?.name

    /**
     * Save with the legacy single-device semantics: replace every
     * existing pairing with the given one. Kept so the existing
     * [PairingScreen] flow that overwrites a single MAC keeps working
     * when the user explicitly chose to re-pair (vs. add a second
     * device, which goes through [add]).
     */
    fun save(name: String?, address: String) {
        prefs.edit().clear().apply()
        add(name = name, mac = address)
    }

    private fun writeAll(devices: List<Paired>) {
        val editor = prefs.edit()
        editor.clear()
        devices.take(MAX_DEVICES).forEachIndexed { i, d ->
            editor.putString(keyAddress(i), d.mac.uppercase())
            editor.putString(keyName(i), d.name)
        }
        editor.apply()
    }

    private fun migrateLegacyKeysIfPresent() {
        val legacyMac = prefs.getString(LEGACY_KEY_ADDRESS, null) ?: return
        // Only migrate when slot 0 is empty — never clobber a
        // multi-device install that for some reason still has the
        // legacy keys around.
        if (prefs.getString(keyAddress(0), null) != null) {
            prefs.edit().remove(LEGACY_KEY_ADDRESS).remove(LEGACY_KEY_NAME).apply()
            return
        }
        prefs.edit()
            .putString(keyAddress(0), legacyMac.uppercase())
            .putString(keyName(0), prefs.getString(LEGACY_KEY_NAME, null))
            .remove(LEGACY_KEY_ADDRESS)
            .remove(LEGACY_KEY_NAME)
            .apply()
    }

    private fun keyAddress(index: Int) = "address_$index"
    private fun keyName(index: Int) = "name_$index"

    companion object {
        const val MAX_DEVICES = 3
        private const val PREFS = "ligpsport.paired_device"
        private const val LEGACY_KEY_ADDRESS = "address"
        private const val LEGACY_KEY_NAME = "name"
    }
}
