package com.vasmarfas.notivisor.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object Bitmaps {

    private const val MAX_SIDE_PX = 640

    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight))
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sampleSize(largestSide: Int): Int {
        var sample = 1
        while (largestSide / sample > MAX_SIDE_PX) sample *= 2
        return sample
    }
}
