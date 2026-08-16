package com.vasmarfas.notivisor.phone.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.ByteArrayOutputStream

object RichContent {

    private const val SCOPE = "rich"
    private const val AVATAR_PX = 64
    private const val PICTURE_MAX_PX = 320
    private const val PICTURE_QUALITY = 70

    fun avatar(context: Context, icon: Icon?): String? {
        icon ?: return null
        return runCatching {
            val drawable = icon.loadDrawable(context) ?: return null
            val bitmap = createBitmap(AVATAR_PX, AVATAR_PX)
            drawable.setBounds(0, 0, AVATAR_PX, AVATAR_PX)
            drawable.draw(Canvas(bitmap))
            val encoded = encode(bitmap, Bitmap.CompressFormat.PNG, 100)
            bitmap.recycle()
            encoded
        }.onFailure { BridgeLog.w(SCOPE, "avatar did not encode: ${it.message}") }.getOrNull()
    }

    fun picture(bitmap: Bitmap?): String? {
        bitmap ?: return null
        return runCatching {
            val scaled = scaleDown(bitmap, PICTURE_MAX_PX)
            val encoded = encode(scaled, Bitmap.CompressFormat.JPEG, PICTURE_QUALITY)
            if (scaled !== bitmap) scaled.recycle()
            encoded
        }.onFailure { BridgeLog.w(SCOPE, "picture did not encode: ${it.message}") }.getOrNull()
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(width, height)
    }
}
