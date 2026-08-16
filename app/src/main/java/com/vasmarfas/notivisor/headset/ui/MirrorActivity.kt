package com.vasmarfas.notivisor.headset.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.ScreenControl
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.ui.theme.NotivisorTheme
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import com.vasmarfas.notivisor.headset.core.MirrorState
import com.vasmarfas.notivisor.headset.core.ScreenReceiver
import android.view.MotionEvent

class MirrorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HeadsetBridge.init(this)

        HeadsetBridge.send(Envelope(action = Action.MIRROR_START, ts = System.currentTimeMillis()))
        HeadsetBridge.onMirrorStop = { runOnUiThread { finish() } }

        setContent {
            NotivisorTheme {
                MaterialSurface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MirrorWindow(onClose = { finish() })
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

    override fun onDestroy() {
        HeadsetBridge.onMirrorStop = null

        if (isFinishing) {
            HeadsetBridge.send(
                Envelope(action = Action.MIRROR_STOP, ts = System.currentTimeMillis())
            )
        }
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(
                Intent(context, MirrorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            )
        }
    }
}

@Composable
private fun MirrorWindow(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = HeadsetBridge.settings
    val receiver = remember { ScreenReceiver() }
    val mirrorState by receiver.state.collectAsStateWithLifecycle()
    var surface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) { onDispose { receiver.stop() } }

    val host = settings.tcpHost
    val connected = mirrorState as? MirrorState.Connected

    DisposableEffect(surface, host) {
        val target = surface
        if (target != null && host != null) {
            receiver.start(host, TransportConfig.SCREEN_STREAM_PORT, target)
        }
        onDispose { }
    }

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
            MirrorSurface(
                aspect = connected?.let { it.width.toFloat() / it.height },
                onSurface = { surface = it },
                onPointer = { action, x, y ->
                    receiver.send(ScreenControl.pointer(action, x, y))
                },
            )
        }

        if (host == null) {
            Text(
                stringResource(R.string.mirror_needs_wifi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            when (val state = mirrorState) {
                is MirrorState.Idle -> stringResource(R.string.mirror_idle)
                is MirrorState.Connecting -> stringResource(R.string.mirror_connecting)
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
            OutlinedButton(onClick = { receiver.send(ScreenControl.key(ScreenControl.KEY_BACK)) }) {
                Text(stringResource(R.string.action_key_back))
            }
            OutlinedButton(onClick = { receiver.send(ScreenControl.key(ScreenControl.KEY_HOME)) }) {
                Text(stringResource(R.string.action_key_home))
            }
            OutlinedButton(onClick = { receiver.send(ScreenControl.key(ScreenControl.KEY_RECENTS)) }) {
                Text(stringResource(R.string.action_key_recents))
            }
            OutlinedButton(onClick = onClose) {
                Text(stringResource(R.string.action_close_window))
            }
        }
    }
}

@Composable
private fun MirrorSurface(
    aspect: Float?,
    onSurface: (Surface?) -> Unit,
    onPointer: (Int, Float, Float) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(aspect ?: DEFAULT_ASPECT)
            .background(Color.Black)
            .onSizeChanged { size = it }

            .pointerInput(Unit) {
                awaitEachGesture {
                    val width = { size.width.takeIf { it > 0 } }
                    val height = { size.height.takeIf { it > 0 } }
                    val down = awaitFirstDown()
                    val w = width() ?: return@awaitEachGesture
                    val h = height() ?: return@awaitEachGesture

                    onPointer(MotionEvent.ACTION_DOWN, down.position.x / w, down.position.y / h)
                    down.consume()

                    var lifted = false
                    var last = down.position
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        lifted = !change.pressed
                        onPointer(
                            if (lifted) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                            last.x / w,
                            last.y / h,
                        )

                        change.consume()
                    } while (change.pressed)

                    if (!lifted) onPointer(MotionEvent.ACTION_UP, last.x / w, last.y / h)
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
                    })
                }
            },
        )
    }
}

private const val DEFAULT_ASPECT = 0.46f
