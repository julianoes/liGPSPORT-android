package de.syntaxfehler.ligpsport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real BLE transport, port of ligpsport/ble.py's BleakTransport. Talks
 * to an iGPSPORT device's four parallel Nordic-UART services, writes
 * MTU-sized chunks on the RX characteristics, and reassembles incoming
 * notifications on the TX characteristics into whole 20-byte-header
 * frames using [expectedTotalSize] as a length oracle.
 *
 * Caller is responsible for holding `BLUETOOTH_CONNECT` runtime
 * permission (Android 12+). On older versions the legacy `BLUETOOTH` /
 * `BLUETOOTH_ADMIN` permissions are sufficient and declared in the
 * manifest.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ManagedTransport {

    private val mutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var negotiatedMtu: Int = DEFAULT_MTU

    // Written from the GATT callback thread, read by [isConnected] from
    // arbitrary coroutine threads — [BleSessionManager] uses it to tell a
    // still-usable cached link from one the stack dropped while the app
    // was idle.
    @Volatile
    private var connected: Boolean = false

    // RX (we write here, device-side receive) and TX (notify, we
    // listen here) characteristics for each of the 4 channels.
    private val rxChars = mutableMapOf<Channel, BluetoothGattCharacteristic>()
    private val txChars = mutableMapOf<Channel, BluetoothGattCharacteristic>()

    // A shared receive buffer — the Python reference does the same. The
    // device only emits one logical frame at a time across all channels
    // for the upload flow, so this is safe.
    private val rxBuffer = mutableListOf<Byte>()
    private var rxExpected: Int? = null
    private var rxChannel: Channel = Channel.CONTROL
    private val received = KChannel<ReceivedFrame>(KChannel.UNLIMITED)

    // One callback is in flight at a time, courtesy of [mutex].
    private var pendingConnect: CancellableContinuation<Unit>? = null
    private var pendingMtu: CancellableContinuation<Int>? = null
    private var pendingDiscover: CancellableContinuation<Unit>? = null
    private var pendingWrite: CancellableContinuation<Unit>? = null
    private var pendingDescriptor: CancellableContinuation<Unit>? = null
    private var pendingDisconnect: CancellableContinuation<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // A non-success status alongside STATE_CONNECTED means
                    // the link came up broken (some stacks report 133 this
                    // way instead of via STATE_DISCONNECTED). Treat it as a
                    // connect failure so the caller retries on a fresh
                    // BluetoothGatt rather than driving a dead socket.
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connected = true
                        pendingConnect?.resume(Unit)
                    } else {
                        Log.w(TAG, "connected with error status=$status")
                        pendingConnect?.resumeWithException(
                            IllegalStateException("BLE connect failed (status=$status)"),
                        )
                    }
                    pendingConnect = null
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "disconnected; status=$status")
                    connected = false
                    pendingConnect?.resumeWithException(
                        IllegalStateException("BLE disconnected (status=$status)"),
                    )
                    pendingConnect = null
                    pendingDisconnect?.resume(Unit)
                    pendingDisconnect = null
                    received.close()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            pendingMtu?.resume(negotiatedMtu)
            pendingMtu = null
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                wireUpCharacteristics(g)
                pendingDiscover?.resume(Unit)
            } else {
                pendingDiscover?.resumeWithException(
                    IllegalStateException("service discovery failed: status=$status"),
                )
            }
            pendingDiscover = null
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingWrite?.resume(Unit)
            } else {
                pendingWrite?.resumeWithException(
                    IllegalStateException("write failed: status=$status"),
                )
            }
            pendingWrite = null
        }

        @Deprecated("Pre-API-33 signature; the typed override below replaces it.")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleNotification(c, c.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(c, value)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingDescriptor?.resume(Unit)
            } else {
                pendingDescriptor?.resumeWithException(
                    IllegalStateException("descriptor write failed: status=$status"),
                )
            }
            pendingDescriptor = null
        }
    }

    override suspend fun open() {
        mutex.withLock {
            // Connect.
            gatt = device.connectGatt(context, false, callback)
                ?: throw IllegalStateException("connectGatt returned null")
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { c -> pendingConnect = c }
            } ?: throw IllegalStateException("BLE connect timed out after ${CONNECT_TIMEOUT_MS}ms")

            // Request a large MTU. The BSC200 typically grants up to
            // 247; failure is silently degraded to the 23-byte default.
            gatt!!.requestMtu(REQUESTED_MTU)
            withTimeoutOrNull(MTU_TIMEOUT_MS) {
                suspendCancellableCoroutine<Int> { c -> pendingMtu = c }
            }

            // Discover services.
            gatt!!.discoverServices()
            withTimeoutOrNull(DISCOVER_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { c -> pendingDiscover = c }
            } ?: throw IllegalStateException("service discovery timed out")

            // Subscribe to TX notifications on all 4 channels. Some
            // models (e.g. BSC200) don't expose all four; only enable
            // what's present.
            for ((channel, tx) in txChars) {
                gatt!!.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD_UUID) ?: continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt!!.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt!!.writeDescriptor(cccd)
                    }
                }
                withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { c -> pendingDescriptor = c }
                } ?: Log.w(TAG, "CCCD write timed out for channel=$channel")
            }
        }
    }

    override suspend fun send(frame: ByteArray, channel: Channel) {
        val rxChar = rxChars[channel]
            ?: throw IllegalStateException("no RX characteristic for $channel")
        val chunkSize = (negotiatedMtu - ATT_HEADER).coerceAtLeast(DEFAULT_MTU - ATT_HEADER)
        mutex.withLock {
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(offset + chunkSize, frame.size)
                val chunk = frame.copyOfRange(offset, end)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt!!.writeCharacteristic(
                        rxChar,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        rxChar.value = chunk
                        rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        gatt!!.writeCharacteristic(rxChar)
                    }
                }
                withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { c -> pendingWrite = c }
                } ?: throw IllegalStateException("characteristic write timed out")
                offset = end
            }
        }
    }

    override fun frames(): Flow<ReceivedFrame> = received.receiveAsFlow()

    override suspend fun close() {
        mutex.withLock {
            val g = gatt
            if (g != null && connected) {
                // Await the disconnect callback before releasing the
                // client interface. `close()` immediately after
                // `disconnect()` aborts the teardown mid-flight and the
                // ACL link lingers, which makes the *next* connect to the
                // same MAC come back as status=133.
                try {
                    g.disconnect()
                    withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                        suspendCancellableCoroutine<Unit> { c -> pendingDisconnect = c }
                    } ?: Log.w(TAG, "disconnect callback timed out")
                } catch (_: Exception) {
                } finally {
                    pendingDisconnect = null
                }
            }
            try { g?.close() } catch (_: Exception) {}
            gatt = null
            connected = false
            received.close()
        }
    }

    /**
     * True while the GATT link is up. [BleSessionManager] polls this to
     * decide whether a cached transport can be reused or has to be
     * rebuilt — a transport is single-use, since [received] is closed for
     * good once the device drops.
     */
    override fun isConnected(): Boolean = connected && gatt != null

    fun mtu(): Int = negotiatedMtu

    // ---- Internals ----

    private fun wireUpCharacteristics(g: BluetoothGatt) {
        rxChars.clear()
        txChars.clear()
        val pairs = listOf(
            Channel.CONTROL to (GattUuids.PRIMARY_SERVICE to (GattUuids.PRIMARY_RX to GattUuids.PRIMARY_TX)),
            Channel.DATA to (GattUuids.DATA_SERVICE to (GattUuids.DATA_RX to GattUuids.DATA_TX)),
            Channel.THIRD to (GattUuids.THIRD_SERVICE to (GattUuids.THIRD_RX to GattUuids.THIRD_TX)),
            Channel.FOURTH to (GattUuids.FOURTH_SERVICE to (GattUuids.FOURTH_RX to GattUuids.FOURTH_TX)),
        )
        for ((channel, uuids) in pairs) {
            val (svcUuid, rxTx) = uuids
            val (rxUuid, txUuid) = rxTx
            val svc = g.getService(svcUuid) ?: continue
            svc.getCharacteristic(rxUuid)?.let { rxChars[channel] = it }
            svc.getCharacteristic(txUuid)?.let { txChars[channel] = it }
        }
        Log.i(TAG, "wired channels: rx=${rxChars.keys}, tx=${txChars.keys}")
    }

    private fun handleNotification(c: BluetoothGattCharacteristic, data: ByteArray) {
        val channel = txChars.entries.firstOrNull { it.value.uuid == c.uuid }?.key ?: rxChannel
        rxChannel = channel
        rxBuffer.addAll(data.toList())
        while (true) {
            if (rxExpected == null) {
                if (rxBuffer.size < HEADER_SIZE) return
                val header = rxBuffer.subList(0, HEADER_SIZE).toByteArray()
                val sized = try {
                    expectedTotalSize(header)
                } catch (e: FrameError) {
                    Log.w(TAG, "dropping malformed header: ${e.message}")
                    rxBuffer.clear()
                    return
                }
                if (sized != null) {
                    rxExpected = sized
                } else {
                    // file_tag=0x55 transmit-complete download (CYCLING_DATA
                    // FILE_GET reply). The header's payload_size is bogus —
                    // peek into the embedded file_download protobuf to learn
                    // the real stream length. This may need more bytes than
                    // we currently have buffered, in which case we wait
                    // for the next notification.
                    val total = transmitCompleteTotalSize(rxBuffer.toByteArray()) ?: return
                    rxExpected = total
                }
            }
            val needed = rxExpected!!
            if (rxBuffer.size < needed) return
            val frameBytes = rxBuffer.subList(0, needed).toByteArray()
            repeat(needed) { rxBuffer.removeAt(0) }
            rxExpected = null
            received.trySend(ReceivedFrame(channel, frameBytes))
        }
    }

    companion object {
        private const val TAG = "BleTransport"
        // BLE 4.2 LL default; we negotiate higher on connect.
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 247
        private const val ATT_HEADER = 3
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MTU_TIMEOUT_MS = 5_000L
        private const val DISCOVER_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val DISCONNECT_TIMEOUT_MS = 2_000L
        // 0x2902 — standard Client Characteristic Configuration descriptor
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
