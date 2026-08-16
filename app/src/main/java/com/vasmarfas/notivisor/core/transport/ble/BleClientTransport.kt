package com.vasmarfas.notivisor.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.WireCodec
import com.vasmarfas.notivisor.core.transport.LinkState
import com.vasmarfas.notivisor.core.transport.NotificationTransport
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("MissingPermission")
class BleClientTransport(
    context: Context,
    private val codecProvider: () -> WireCodec,
) : NotificationTransport {

    override val kind = TransportKind.BLE

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = manager?.adapter

    private val _state = MutableStateFlow<LinkState>(LinkState.Stopped)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendMutex = Mutex()
    private val writeAcks = Channel<Int>(Channel.CONFLATED)
    private val reassembler = BleProtocol.Reassembler(SCOPE)

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var running = false
    private var retryJob: Job? = null
    private var backoffMs = INITIAL_BACKOFF_MS

    @Volatile
    private var mtu = BleProtocol.DEFAULT_MTU

    override val maxFrameBytes: Int get() = BleProtocol.maxMessageSize(mtu)

    override fun start() {
        if (running) return
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            fail("bluetooth is off")
            return
        }
        BlePermissions.missing(appContext, BlePermissions.forClient()).let { missing ->
            if (missing.isNotEmpty()) {
                fail("missing permissions: ${missing.joinToString { it.substringAfterLast('.') }}")
                return
            }
        }
        running = true
        _state.value = LinkState.Starting
        startScan()
    }

    override fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        retryJob?.cancel()
        retryJob = null
        stopScan()
        closeGatt()
        _state.value = LinkState.Stopped
    }

    override suspend fun send(envelope: Envelope): Boolean {
        val connection = gatt ?: return false
        val rx = rxCharacteristic ?: return false

        return sendMutex.withLock {
            val chunks = BleProtocol.chunk(codecProvider().encode(envelope), mtu)
            for (chunk in chunks) {
                while (writeAcks.tryReceive().isSuccess) Unit
                if (!write(connection, rx, chunk)) {
                    BridgeLog.w(SCOPE, "write rejected by the stack")
                    return@withLock false
                }
                val status = withTimeoutOrNull(WRITE_TIMEOUT_MS.milliseconds) { writeAcks.receive() }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    BridgeLog.w(SCOPE, "write failed, status $status")
                    return@withLock false
                }
            }
            true
        }
    }

    private fun write(
        connection: BluetoothGatt,
        rx: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection.writeCharacteristic(
                rx, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                rx.value = value
                connection.writeCharacteristic(rx)
            }
        }
    }.getOrElse {
        BridgeLog.e(SCOPE, "write rejected", it)
        false
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            fail("no BLE scanner available")
            return
        }
        if (scanning) return
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onSuccess {
                scanning = true
                BridgeLog.i(SCOPE, "scanning for ${BleProtocol.SERVICE_UUID}")
                _state.value = LinkState.Waiting("scanning")
            }
            .onFailure { fail("startScan threw: ${it.message}") }
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    private fun closeGatt() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        rxCharacteristic = null
        mtu = BleProtocol.DEFAULT_MTU
        reassembler.reset()
    }

    private fun scheduleRetry(reason: String) {
        if (!running) return
        _state.value = LinkState.Waiting("$reason, retrying in ${backoffMs / 1000} s")
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            if (running) startScan()
        }
    }

    private fun fail(reason: String) {
        BridgeLog.w(SCOPE, reason)
        _state.value = LinkState.Failed(reason)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (gatt != null) return
            stopScan()
            BridgeLog.i(SCOPE, "found ${device.address} rssi=${result.rssi}, connecting")
            _state.value = LinkState.Connecting(device.address)
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            BridgeLog.w(SCOPE, "scan failed, error $errorCode")
            scheduleRetry("scan error $errorCode")
        }
    }

    private fun connect(device: BluetoothDevice) {
        mainHandler.postDelayed({
            if (!running || gatt != null) return@postDelayed
            @Suppress("DEPRECATION")
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) scheduleRetry("connectGatt refused")
        }, CONNECT_SETTLE_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            connection: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    BridgeLog.i(SCOPE, "connected, requesting mtu ${BleProtocol.TARGET_MTU}")
                    if (!connection.requestMtu(BleProtocol.TARGET_MTU)) {
                        connection.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    BridgeLog.w(SCOPE, "disconnected, status $status")
                    closeGatt()
                    scheduleRetry("disconnected (status $status)")
                }
            }
        }

        override fun onMtuChanged(connection: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else BleProtocol.DEFAULT_MTU
            BridgeLog.i(SCOPE, "mtu = $mtu (payload ${BleProtocol.payloadSize(mtu)} B)")
            connection.discoverServices()
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            val service = connection.getService(BleProtocol.SERVICE_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                BridgeLog.w(SCOPE, "bridge service not found, status $status")
                connection.disconnect()
                return
            }
            val tx = service.getCharacteristic(BleProtocol.TX_UUID)
            rxCharacteristic = service.getCharacteristic(BleProtocol.RX_UUID)
            if (tx == null || rxCharacteristic == null) {
                BridgeLog.w(SCOPE, "characteristics missing on peer")
                connection.disconnect()
                return
            }
            connection.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(BleProtocol.CCCD_UUID)
            if (cccd == null) {
                BridgeLog.w(SCOPE, "no CCCD on tx characteristic")
                connection.disconnect()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    connection.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(
            connection: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != BleProtocol.CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                BridgeLog.w(SCOPE, "CCCD write failed, status $status")
                connection.disconnect()
                return
            }
            backoffMs = INITIAL_BACKOFF_MS
            val peer = connection.device?.address ?: "peer"
            BridgeLog.i(SCOPE, "subscribed, link up with $peer")
            _state.value = LinkState.Connected(peer, System.currentTimeMillis())
        }

        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == BleProtocol.TX_UUID) accept(value)
        }

        @Deprecated("Kept for API < 33, which does not deliver the value as an argument")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == BleProtocol.TX_UUID) characteristic.value?.let { accept(it) }
        }

        override fun onCharacteristicWrite(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == BleProtocol.RX_UUID) writeAcks.trySend(status)
        }
    }

    private fun accept(frame: ByteArray) {
        val line = reassembler.accept(frame) ?: return
        scope.launch {
            try {
                _incoming.emit(codecProvider().decode(line))
            } catch (e: WireCodec.ProtocolMismatch) {
                BridgeLog.w(SCOPE, "rejected frame: ${e.message}")
            } catch (e: Exception) {
                BridgeLog.w(SCOPE, "undecodable frame: ${e.message}")
            }
        }
    }

    private companion object {
        const val SCOPE = "ble-client"
        const val WRITE_TIMEOUT_MS = 4_000L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val CONNECT_SETTLE_MS = 400L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
