package com.vasmarfas.notivisor.phone.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.control.MirrorState
import com.vasmarfas.notivisor.core.control.ScreenReceiver
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.ui.theme.NotivisorTheme
import com.vasmarfas.notivisor.phone.core.CastPrompt
import com.vasmarfas.notivisor.phone.core.PhoneBridge

class CastActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CastPrompt.dismiss(this)
        PhoneBridge.sendCastState(true)
        PhoneBridge.onCastStop = { runOnUiThread { finish() } }

        setContent {
            NotivisorTheme {
                MaterialSurface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CastWindow(onClose = { finish() })
                }
            }
        }
    }

    override fun onDestroy() {
        PhoneBridge.onCastStop = null
        if (isFinishing) PhoneBridge.sendCastState(false)
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(
                Intent(context, CastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun CastWindow(onClose: () -> Unit) {
    val context = LocalContext.current
    val receiver = remember { ScreenReceiver() }
    val castState by receiver.state.collectAsStateWithLifecycle()
    var surface by remember { mutableStateOf<Surface?>(null) }
    var sound by remember { mutableStateOf(true) }

    DisposableEffect(Unit) { onDispose { receiver.stop() } }

    DisposableEffect(surface) {
        surface?.let { receiver.listen(TransportConfig.CAST_STREAM_PORT, it) }
        onDispose { }
    }

    DisposableEffect(sound) {
        receiver.sound = sound
        onDispose { }
    }

    val connected = castState as? MirrorState.Connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(connected?.let { it.width.toFloat() / it.height } ?: HEADSET_ASPECT)
                    .background(Color.Black),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surface = holder.surface
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surface = null
                            }
                        })
                    }
                },
            )
        }

        Text(
            when (val state = castState) {
                is MirrorState.Idle -> stringResource(R.string.cast_idle)
                is MirrorState.Connecting -> stringResource(R.string.cast_waiting)
                is MirrorState.Connected ->
                    stringResource(R.string.mirror_connected, state.width, state.height, state.frames)

                is MirrorState.Failed -> stringResource(R.string.mirror_failed, state.reason)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { sound = !sound }) {
                Text(
                    stringResource(
                        if (sound) R.string.action_mute else R.string.action_unmute
                    )
                )
            }
            OutlinedButton(
                onClick = {
                    receiver.stop()
                    CastPrompt.dismiss(context)
                    onClose()
                }
            ) { Text(stringResource(R.string.action_close_window)) }
        }
    }
}

private const val HEADSET_ASPECT = 0.94f
