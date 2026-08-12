package com.vasmarfas.notivisor.core.transport

import android.content.Context
import com.vasmarfas.notivisor.core.protocol.WireCodec
import com.vasmarfas.notivisor.core.transport.ble.BleClientTransport
import com.vasmarfas.notivisor.core.transport.ble.BleServerTransport

object TransportFactory {

    fun create(
        context: Context,
        config: TransportConfig,
        codecProvider: () -> WireCodec,
        deviceLabel: String,
    ): NotificationTransport = when (config.kind) {
        TransportKind.TCP -> TcpTransport(context, config, codecProvider)
        TransportKind.BLE -> if (config.isBleServer) {
            BleServerTransport(context, codecProvider, deviceLabel)
        } else {
            BleClientTransport(context, codecProvider)
        }
    }
}
