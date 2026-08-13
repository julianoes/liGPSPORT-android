package de.syntaxfehler.ligpsport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Bluetooth is off, missing, or the stored MAC isn't a valid address. */
class BleUnavailableException(message: String) : IllegalStateException(message)

/**
 * Owns exactly one live [BleTransport] per device MAC and hands out
 * exclusive, serialised leases on it.
 *
 * Before this existed every [UploadPipeline] helper opened its own
 * `BleTransport`, so the 15-second-per-device nav-status poll in
 * `NavStatusOverlay` and a route upload could hold two GATT sessions
 * against the same computer at once. A route upload runs well past 15 s,
 * so the collision happened on nearly every upload: when the poll's
 * transport called `gatt.disconnect()`, the upload's transport saw
 * `STATE_DISCONNECTED`, closed its frame channel, and the ack wait in
 * [FileTransfer] returned nothing — surfaced to the user as a failed
 * upload. The user's only escape was to unpair every device (which stops
 * the pollers) and re-pair.
 *
 * Two invariants make that impossible now:
 *
 *  1. **One connection per MAC.** Callers borrow the cached transport
 *     instead of opening their own, so the device only ever sees a single
 *     link from us.
 *  2. **One operation at a time per MAC.** A lease is exclusive for the
 *     whole protocol exchange. Sharing the connection is not enough —
 *     [Transport.frames] is a single multiplexed channel, so two
 *     concurrent operations would consume each other's acks.
 *
 * Leases are per-MAC, so fanning out to several computers
 * ([UploadPipeline.uploadGpxAll]) still runs fully in parallel.
 *
 * The connection is kept warm for [IDLE_TIMEOUT_MS] after the last lease
 * and then closed, so a device left behind doesn't hold the radio open
 * forever. A cached link that the stack dropped in the meantime — the
 * usual state after the app has been idle for a long while — is detected
 * via [BleTransport.isConnected] and rebuilt transparently.
 */
@SuppressLint("MissingPermission")
object BleSessionManager {
    private const val TAG = "BleSessionManager"

    /** How long a connection is kept warm after the last lease. */
    private const val IDLE_TIMEOUT_MS = 30_000L

    /**
     * Backoff before each connect attempt. A first-try `status=133` is
     * routine on Android when the controller still has state from an
     * earlier link — retrying after a short pause almost always works,
     * and that is exactly the "device is online but the app has to
     * reconnect first" case after a long idle period.
     */
    private val CONNECT_BACKOFF_MS = longArrayOf(0L, 500L, 1_500L)

    private class Session(val mac: String) {
        /** Held for the duration of one lease — see invariant 2. */
        val lock = Mutex()
        var transport: ManagedTransport? = null
        var reaper: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registryLock = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    /**
     * Test seam: replaces the real `connectGatt` path so the leasing
     * rules can be exercised without Bluetooth hardware. Null in
     * production.
     */
    @VisibleForTesting
    internal var connector: (suspend (Context, String) -> ManagedTransport)? = null

    /**
     * Run [block] against a connected transport for [mac], connecting
     * first if there is no usable cached link.
     *
     * Throws [BleUnavailableException] when the adapter is unusable, or
     * whatever the last connect attempt / [block] threw. Cancellation
     * propagates untouched; the connection survives it and is reaped on
     * the idle timer like any other.
     */
    suspend fun <T> withSession(
        context: Context,
        mac: String,
        block: suspend (Transport) -> T,
    ): T {
        val canonical = mac.uppercase()
        val appContext = context.applicationContext
        val session = registryLock.withLock { sessions.getOrPut(canonical) { Session(canonical) } }
        return session.lock.withLock {
            session.reaper?.cancel()
            session.reaper = null

            val cached = session.transport?.takeIf { it.isConnected() }
            if (cached == null) {
                closeTransport(session)
                session.transport = connectWithRetry(appContext, canonical)
            }
            try {
                run(session, block, reconnectOnFailure = cached != null, context = appContext)
            } finally {
                scheduleReap(session)
            }
        }
    }

    /**
     * Drop any cached connection for [mac]. Call this when the user
     * unpairs a device so we stop holding its radio open.
     */
    suspend fun forget(mac: String) {
        val canonical = mac.uppercase()
        // Keep the registry entry — dropping it would let a caller that
        // is still inside a lease coexist with a fresh Session (and so a
        // second connection) for the same MAC. The entry is just a lock
        // plus a null transport; there are at most MAX_DEVICES of them.
        val session = registryLock.withLock { sessions[canonical] } ?: return
        session.lock.withLock {
            session.reaper?.cancel()
            session.reaper = null
            closeTransport(session)
        }
    }

    /** [forget] every device. Used by the "forget all" path in Settings. */
    suspend fun forgetAll() {
        val macs = registryLock.withLock { sessions.keys.toList() }
        macs.forEach { forget(it) }
    }

    // ---- Internals ----------------------------------------------------

    private suspend fun <T> run(
        session: Session,
        block: suspend (Transport) -> T,
        reconnectOnFailure: Boolean,
        context: Context,
    ): T {
        try {
            return block(session.transport!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!reconnectOnFailure) {
                // The link was brand new, so a failure here is the device
                // rejecting us rather than a stale socket. Don't burn a
                // second connect on it.
                closeTransport(session)
                throw e
            }
            Log.w(TAG, "${session.mac}: op failed on a reused link (${e.message}) — reconnecting")
        }
        closeTransport(session)
        session.transport = connectWithRetry(context, session.mac)
        try {
            return block(session.transport!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            closeTransport(session)
            throw e
        }
    }

    private suspend fun connectWithRetry(context: Context, mac: String): ManagedTransport {
        var last: Exception? = null
        for ((attempt, backoff) in CONNECT_BACKOFF_MS.withIndex()) {
            if (backoff > 0) delay(backoff)
            val transport = connector?.invoke(context, mac) ?: newBleTransport(context, mac)
            try {
                transport.open()
                Log.i(TAG, "$mac: connected on attempt ${attempt + 1}")
                return transport
            } catch (e: CancellationException) {
                closeQuietly(transport)
                throw e
            } catch (e: BleUnavailableException) {
                // Adapter off / missing / bad address — retrying can't help.
                closeQuietly(transport)
                throw e
            } catch (e: Exception) {
                last = e
                closeQuietly(transport)
                Log.w(TAG, "$mac: connect attempt ${attempt + 1}/${CONNECT_BACKOFF_MS.size} failed: ${e.message}")
            }
        }
        throw last ?: BleUnavailableException("$mac: connect failed")
    }

    private fun newBleTransport(context: Context, mac: String): ManagedTransport {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw BleUnavailableException("Bluetooth not available")
        if (!adapter.isEnabled) throw BleUnavailableException("Bluetooth is off — enable it and retry")
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            throw BleUnavailableException("not a valid device address: $mac")
        }
        return BleTransport(context, device)
    }

    private suspend fun closeTransport(session: Session) {
        session.transport?.let { closeQuietly(it) }
        session.transport = null
    }

    /**
     * [BleTransport.close] is a suspending function, so a plain
     * `runCatching { close() }` in a cancelled coroutine would abort at
     * the first suspension point and leak the GATT client interface.
     * [NonCancellable] makes the teardown run to completion.
     */
    private suspend fun closeQuietly(transport: ManagedTransport) {
        withContext(NonCancellable) {
            try {
                transport.close()
            } catch (e: Exception) {
                Log.w(TAG, "close failed: ${e.message}")
            }
        }
    }

    private fun scheduleReap(session: Session) {
        session.reaper?.cancel()
        session.reaper = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            session.lock.withLock {
                // A lease taken while we were sleeping cancels this job
                // and installs a new reaper, so reaching here means the
                // connection really has been idle the whole time.
                if (session.transport != null) {
                    Log.i(TAG, "${session.mac}: idle for ${IDLE_TIMEOUT_MS}ms — closing")
                    closeTransport(session)
                }
            }
        }
    }
}
