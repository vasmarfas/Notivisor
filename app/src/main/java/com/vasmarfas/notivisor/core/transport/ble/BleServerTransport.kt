package com.vasmarfas.notivisor.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.WireCodec
import com.vasmarfas.notivisor.core.transport.LinkState
import com.vasmarfas.notivisor.core.transport.NotificationTransport
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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

@SuppressLint("MissingPermission")
class BleServerTransport(
    context: Context,
    private val codecProvider: () -> WireCodec,
    private val deviceLabel: String,
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
    private val sendMutex = Mutex()
    private val notificationSent = Channel<Int>(Channel.CONFLATED)
    private val reassembler = BleProtocol.Reassembler(SCOPE)

    private var server: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var peer: BluetoothDevice? = null

    @Volatile
    private var subscribed = false

    @Volatile
    private var mtu = BleProtocol.DEFAULT_MTU

    private var advertising = false

    override fun start() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            fail("bluetooth is off")
            return
        }
        BlePermissions.missing(appContext, BlePermissions.forServer()).let { missing ->
            if (missing.isNotEmpty()) {
                fail("missing permissions: ${missing.joinToString { it.substringAfterLast('.') }}")
                return
            }
        }
        if (bt.bluetoothLeAdvertiser == null) {
            fail("this device cannot advertise BLE (no peripheral role)")
            return
        }

        _state.value = LinkState.Starting
        val gattServer = manager?.openGattServer(appContext, serverCallback)
        if (gattServer == null) {
            fail("openGattServer returned null")
            return
        }
        server = gattServer

        val service = BluetoothGattService(
            BleProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val tx = BluetoothGattCharacteristic(
            BleProtocol.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        val rx = BluetoothGattCharacteristic(
            BleProtocol.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        txCharacteristic = tx

        if (!gattServer.addService(service)) {
            fail("addService rejected")
        }
    }

    override fun stop() {
        stopAdvertising()
        peer?.let { runCatching { server?.cancelConnection(it) } }
        runCatching { server?.close() }
        server = null
        txCharacteristic = null
        peer = null
        subscribed = false
        reassembler.reset()
        _state.value = LinkState.Stopped
    }

    override suspend fun send(envelope: Envelope): Boolean {
        val device = peer ?: return false
        val tx = txCharacteristic ?: return false
        val gattServer = server ?: return false
        if (!subscribed) return false

        return sendMutex.withLock {
            val chunks = BleProtocol.chunk(codecProvider().encode(envelope), mtu)
            for (chunk in chunks) {
                while (notificationSent.tryReceive().isSuccess) Unit
                val queued = notify(gattServer, device, tx, chunk)
                if (!queued) {
                    BridgeLog.w(SCOPE, "notify rejected by the stack")
                    return@withLock false
                }
                val status = withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { notificationSent.receive() }
                if (status == null) {
                    BridgeLog.w(SCOPE, "no onNotificationSent within ${NOTIFY_TIMEOUT_MS} ms")
                    return@withLock false
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    BridgeLog.w(SCOPE, "notification failed, status $status")
                    return@withLock false
                }
            }
            true
        }
    }

    private fun notify(
        gattServer: BluetoothGattServer,
        device: BluetoothDevice,
        tx: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer.notifyCharacteristicChanged(
                device,
                tx,
                false,
                value
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            tx.value = value
            @Suppress("DEPRECATION")
            gattServer.notifyCharacteristicChanged(device, tx, false)
        }
    }.getOrElse {
        BridgeLog.e(SCOPE, "notify rejected", it)
        false
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    private fun fail(reason: String) {
        BridgeLog.w(SCOPE, reason)
        _state.value = LinkState.Failed(reason)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            BridgeLog.i(SCOPE, "advertising as '$deviceLabel'")
            if (peer == null) _state.value = LinkState.Waiting("advertising as $deviceLabel")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true
                return
            }
            advertising = false
            fail("advertising failed, error $errorCode")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("service not added, status $status")
                return
            }
            BridgeLog.i(SCOPE, "service ${service.uuid} added")
            startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    peer = device
                    subscribed = false
                    mtu = BleProtocol.DEFAULT_MTU
                    reassembler.reset()
                    BridgeLog.i(SCOPE, "central ${device.address} connected, waiting for CCCD")
                    _state.value = LinkState.Connecting(device.address)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (peer?.address == device.address) {
                        peer = null
                        subscribed = false
                        reassembler.reset()
                    }
                    BridgeLog.w(SCOPE, "central ${device.address} disconnected, status $status")
                    _state.value = LinkState.Waiting("advertising as $deviceLabel")
                    advertising = false
                    startAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            mtu = newMtu
            BridgeLog.i(SCOPE, "mtu = $newMtu (payload ${BleProtocol.payloadSize(newMtu)} B)")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == BleProtocol.CCCD_UUID) {
                subscribed = value.isNotEmpty() && value[0].toInt() != 0
                BridgeLog.i(SCOPE, "central ${device.address} subscribed=$subscribed")
                _state.value = if (subscribed) {
                    LinkState.Connected(device.address, System.currentTimeMillis())
                } else {
                    LinkState.Connecting(device.address)
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid != BleProtocol.RX_UUID) return
            val line = reassembler.accept(value) ?: return
            emit(line)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationSent.trySend(status)
        }
    }

    private fun emit(line: String) {
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
        const val SCOPE = "ble-server"
        const val NOTIFY_TIMEOUT_MS = 4_000L
        const val ADVERTISE_FAILED_ALREADY_STARTED =
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED
    }
}
