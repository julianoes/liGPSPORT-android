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

    /**
     * Snapshot of every pairing, slot-ordered. Empty when nothing is
     * paired. The [Paired.name] field is the user-set nickname when
     * one exists for the MAC (set via [setNickname]) — otherwise the
     * BLE-advertised name captured at [add] time. Nicknames survive
     * [remove] so a user can drop a device and re-pair without
     * relabelling it every time.
     */
    fun list(): List<Paired> = buildList {
        for (i in 0 until MAX_DEVICES) {
            val mac = prefs.getString(keyAddress(i), null) ?: break
            val advertised = prefs.getString(keyName(i), null)
            val nickname = prefs.getString(keyNickname(mac), null)
            add(Paired(mac = mac, name = nickname ?: advertised))
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
        rememberName(canonical, name)
        return true
    }

    /**
     * Remember the label a MAC was last known by, independent of whether
     * it is currently paired. `BluetoothDevice.name` is frequently null
     * during a scan (the OS name cache is only populated after a
     * connection or a scan record that carries the complete local name),
     * so after a user unpairs a device the pairing screen would show it
     * again as a bare MAC. Persisting the label lets the scan list stay
     * readable. Nicknames win over advertised names; see [labelFor].
     */
    fun rememberName(mac: String, name: String?) {
        if (name.isNullOrBlank()) return
        prefs.edit().putString(keyLastName(mac), name.trim()).apply()
    }

    /**
     * Best-known human label for [mac]: user nickname, else the last
     * advertised name we saw, else null. [advertised] is the name from
     * the current scan, used when we have nothing stored (and recorded
     * for next time).
     */
    fun labelFor(mac: String, advertised: String? = null): String? {
        val canonical = mac.uppercase()
        if (!advertised.isNullOrBlank()) rememberName(canonical, advertised)
        return prefs.getString(keyNickname(canonical), null)
            ?: advertised?.takeIf { it.isNotBlank() }
            ?: prefs.getString(keyLastName(canonical), null)
    }

    /**
     * Remove the given MAC. No-op if it wasn't paired. The user-set
     * nickname (if any) is preserved — re-pairing the same MAC later
     * brings the custom label back without the user having to retype
     * it.
     */
    fun remove(mac: String) {
        val canonical = mac.uppercase()
        writeAll(list().filterNot { it.mac.equals(canonical, ignoreCase = true) })
    }

    /**
     * Get / set / clear the user-defined nickname for [mac]. Stored
     * separately from the slot list so it persists across [remove] +
     * re-[add] cycles. A blank or null name clears the nickname (the
     * UI then falls back to the BLE-advertised name).
     */
    fun nickname(mac: String): String? = prefs.getString(keyNickname(mac), null)

    fun setNickname(mac: String, name: String?) {
        val canonical = mac.uppercase()
        val editor = prefs.edit()
        if (name.isNullOrBlank()) {
            editor.remove(keyNickname(canonical))
        } else {
            editor.putString(keyNickname(canonical), name.trim())
        }
        editor.apply()
    }

    /**
     * Forget every paired device. Nicknames are wiped too — this is
     * the "factory reset" path, not the per-device "remove" gesture.
     */
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
        // Drop the slots via writeAll rather than `prefs.clear()` so the
        // per-MAC labels (nickname / last-known name) survive — a re-pair
        // shouldn't cost the user their custom naming.
        writeAll(emptyList())
        add(name = name, mac = address)
    }

    private fun writeAll(devices: List<Paired>) {
        // Preserve the per-MAC label keys across slot rewrites — they
        // live outside the slot keys but a naive `editor.clear()` would
        // wipe them alongside. Snapshot, clear, restore.
        val labelSnapshot = prefs.all.entries
            .filter { it.key.startsWith(NICKNAME_PREFIX) || it.key.startsWith(LAST_NAME_PREFIX) }
            .associate { it.key to (it.value as? String) }
        val editor = prefs.edit()
        editor.clear()
        devices.take(MAX_DEVICES).forEachIndexed { i, d ->
            editor.putString(keyAddress(i), d.mac.uppercase())
            editor.putString(keyName(i), d.name)
        }
        labelSnapshot.forEach { (k, v) -> if (v != null) editor.putString(k, v) }
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
    private fun keyNickname(mac: String) = "$NICKNAME_PREFIX${mac.uppercase()}"
    private fun keyLastName(mac: String) = "$LAST_NAME_PREFIX${mac.uppercase()}"

    companion object {
        const val MAX_DEVICES = 10
        private const val PREFS = "ligpsport.paired_device"
        private const val LEGACY_KEY_ADDRESS = "address"
        private const val LEGACY_KEY_NAME = "name"
        private const val NICKNAME_PREFIX = "nick_"
        private const val LAST_NAME_PREFIX = "lastname_"
    }
}
