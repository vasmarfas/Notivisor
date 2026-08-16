package com.vasmarfas.notivisor.phone.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.core.PhoneBridge

class ForwardingTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val settings = BridgeSettings.get(this)
        val enabled = !settings.enabled
        settings.enabled = enabled

        if (enabled) {
            settings.stoppedByUser = false
            BridgeService.start(this)
        } else {
            PhoneBridge.init(this)
            PhoneBridge.stopLink()
        }
        PhoneBridge.notifyStateChanged(this)
        BridgeLog.i(SCOPE, "forwarding ${if (enabled) "on" else "off"} from the tile")
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val enabled = BridgeSettings.get(this).enabled
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(R.string.label_enabled)
        tile.updateTile()
    }

    private companion object {
        const val SCOPE = "tile"
    }
}
