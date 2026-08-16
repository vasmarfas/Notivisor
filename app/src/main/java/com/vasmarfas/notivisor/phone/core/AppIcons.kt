package com.vasmarfas.notivisor.phone.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import androidx.core.graphics.createBitmap
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.ByteArrayOutputStream

object AppIcons {

    private const val SCOPE = "icons"
    private const val SIZE_PX = 48

    fun encode(context: Context, pkg: String): String? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val bitmap = createBitmap(SIZE_PX, SIZE_PX)
        drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
        drawable.draw(Canvas(bitmap))
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }.onFailure { BridgeLog.w(SCOPE, "no icon for $pkg: ${it.message}") }.getOrNull()
}
