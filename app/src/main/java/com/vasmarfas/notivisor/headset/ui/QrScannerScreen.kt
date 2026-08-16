package com.vasmarfas.notivisor.headset.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf<String?>(null) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    var hasHeadsetPermission by remember {
        mutableStateOf(HeadsetCamera.hasPassthroughPermission(context))
    }
    var permissionAttempted by remember { mutableStateOf(false) }
    val headsetPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasHeadsetPermission = granted
        permissionAttempted = true
    }

    var allCameraIds by remember { mutableStateOf(HeadsetCamera.cameraIds(context)) }
    var passthroughIds by remember { mutableStateOf(HeadsetCamera.passthroughIds(context)) }

    LaunchedEffect(hasHeadsetPermission) {
        while (hasHeadsetPermission && allCameraIds.isEmpty()) {
            delay(700)
            allCameraIds = HeadsetCamera.cameraIds(context)
            passthroughIds = HeadsetCamera.passthroughIds(context)
        }
    }

    val candidateIds = passthroughIds.ifEmpty { allCameraIds }

    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(candidateIds) {
        if (selectedCameraId !in candidateIds) selectedCameraId = candidateIds.firstOrNull()
    }
    LaunchedEffect(selectedCameraId) { error = null }

    val hintRes = when {
        !hasHeadsetPermission -> R.string.scan_hint_virtual
        allCameraIds.isEmpty() -> R.string.scan_waiting_cameras
        else -> R.string.scan_hint
    }
    val hintIsWarning = !hasHeadsetPermission || allCameraIds.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(hintRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = if (hintIsWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .clip(RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            key(selectedCameraId) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            bindCamera(ctx, view, lifecycleOwner, executor, selectedCameraId, onResult) {
                                error = it
                            }
                        }
                    },
                )
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (!hasHeadsetPermission) {
            val permanentlyDenied = permissionAttempted &&
                    activity?.shouldShowRequestPermissionRationale(HeadsetCamera.HEADSET_CAMERA) == false
            OutlinedButton(
                onClick = {
                    if (permanentlyDenied) {
                        openAppSettings(context)
                    } else {
                        headsetPermissionLauncher.launch(HeadsetCamera.HEADSET_CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (permanentlyDenied) R.string.action_open_settings else R.string.action_grant_cameras
                    )
                )
            }
        }

        if (candidateIds.size > 1) {
            val index = candidateIds.indexOf(selectedCameraId).coerceAtLeast(0)
            OutlinedButton(
                onClick = { selectedCameraId = candidateIds[(index + 1) % candidateIds.size] },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.scan_switch_camera, index + 1, candidateIds.size))
            }
        }

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back))
        }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun bindCamera(
    context: Context,
    view: PreviewView,
    lifecycleOwner: LifecycleOwner,
    executor: ExecutorService,
    cameraId: String?,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        runCatching {
            val provider = future.get()
            val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(executor, QrAnalyzer(onResult)) }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                HeadsetCamera.selector(context, cameraId),
                preview,
                analysis
            )
        }.onFailure {
            BridgeLog.w("camera", "could not open a camera: ${it.message}")
            onError(context.getString(R.string.scan_camera_error))
        }
    }, ContextCompat.getMainExecutor(context))
}

private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    @Volatile
    private var done = false

    override fun analyze(image: ImageProxy) {
        if (done) {
            image.close()
            return
        }
        try {
            val plane = image.planes[0]
            val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                bytes,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val text = decode(source) ?: decode(source.invert())
            if (text != null) {
                done = true
                onResult(text)
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun decode(source: LuminanceSource): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
