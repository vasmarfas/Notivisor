package com.vasmarfas.notivisor.headset.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import com.vasmarfas.notivisor.core.util.BridgeLog

object HeadsetCamera {

    private const val SCOPE = "camera"
    private const val TAG_SOURCE = "com.meta.extra_metadata.camera_source"
    private const val TAG_POSITION = "com.meta.extra_metadata.position"
    private const val PASSTHROUGH = 0.toByte()

    val permissions = listOf(Manifest.permission.CAMERA, HEADSET_CAMERA)

    const val HEADSET_CAMERA = "horizonos.permission.HEADSET_CAMERA"

    fun isDeclared(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun hasBasicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    fun hasPassthroughPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, HEADSET_CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    fun hasAllPermissions(context: Context): Boolean =
        hasBasicPermission(context) && hasPassthroughPermission(context)

    fun passthroughIds(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val manager =
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        return cameraIds(context).filter { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(vendorTag(TAG_SOURCE)) == PASSTHROUGH
            }.onFailure {
                BridgeLog.w(SCOPE, "vendor tag unreadable for camera $id: ${it.javaClass.simpleName} ${it.message}")
            }.getOrDefault(false)
        }
    }

    fun hasPassthrough(context: Context): Boolean = passthroughIds(context).isNotEmpty()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun vendorTag(name: String) = CameraCharacteristics.Key(name, Byte::class.java)

    @SuppressLint("UnsafeOptInUsageError")
    fun selector(context: Context, cameraId: String? = null): CameraSelector {
        val target = cameraId ?: passthroughIds(context).firstOrNull()
        if (target == null) {
            BridgeLog.i(SCOPE, "no passthrough camera advertised, using the default rear camera")
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        BridgeLog.i(SCOPE, "using camera $target")
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == target }.ifEmpty { infos }
            }
            .build()
    }

    fun describe(context: Context): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "no camera service"
        val ids = cameraIds(context)
        if (ids.isEmpty()) return "no cameras visible to this app"
        val readTags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return ids.joinToString("; ") { id ->
            runCatching {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val source = if (readTags) characteristics.get(vendorTag(TAG_SOURCE)) else null
                val position = if (readTags) characteristics.get(vendorTag(TAG_POSITION)) else null
                "id=$id facing=$facing source=$source position=$position"
            }.getOrElse { "id=$id unreadable (${it.javaClass.simpleName})" }
        } + " | passthrough=${passthroughIds(context)} | " +
                "camPerm=${hasBasicPermission(context)} headsetCamPerm=${hasPassthroughPermission(context)}"
    }

    fun cameraIds(context: Context): List<String> {
        val manager =
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        return runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
    }
}
