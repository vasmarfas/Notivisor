package com.vasmarfas.notivisor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.settings.DeviceRole
import com.vasmarfas.notivisor.core.ui.theme.NotivisorTheme
import com.vasmarfas.notivisor.headset.ui.HeadsetScreen
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import com.vasmarfas.notivisor.phone.ui.BridgeScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val settings = BridgeSettings.get(this)
        settings.stoppedByUser = false
        AppRole.start(this)

        setContent {
            val revision by settings.revision.collectAsStateWithLifecycle()
            val role = revision.let { AppRole.current(this) }

            NotivisorTheme(darkTheme = role == DeviceRole.HEADSET || isSystemInDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (role) {
                        DeviceRole.PHONE -> BridgeScreen()
                        DeviceRole.HEADSET -> HeadsetScreen()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        HeadsetBridge.uiVisible = true
    }

    override fun onPause() {
        HeadsetBridge.uiVisible = false
        super.onPause()
    }
}
