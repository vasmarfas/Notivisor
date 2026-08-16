package com.vasmarfas.notivisor.headset.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Base64
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.File

class IconCache(context: Context) {

    private val directory = File(context.applicationContext.cacheDir, "icons")
    private val memory = HashMap<String, Bitmap>()

    fun has(pkg: String): Boolean =
        synchronized(memory) { pkg in memory } || fileFor(pkg).exists()

    fun store(pkg: String, base64: String): Boolean {
        val bytes = runCatching { Base64.decode(base64, Base64.NO_WRAP) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            BridgeLog.w(SCOPE, "icon for $pkg did not decode")
            return false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            BridgeLog.w(SCOPE, "icon for $pkg is not an image")
            return false
        }
        synchronized(memory) { memory[pkg] = bitmap }
        runCatching {
            directory.mkdirs()
            fileFor(pkg).writeBytes(bytes)
        }.onFailure { BridgeLog.w(SCOPE, "could not cache the icon for $pkg: ${it.message}") }
        BridgeLog.i(SCOPE, "icon stored for $pkg (${bytes.size} B)")
        return true
    }

    fun icon(pkg: String): Icon? {
        synchronized(memory) { memory[pkg] }?.let { return Icon.createWithBitmap(it) }
        val file = fileFor(pkg)
        if (!file.exists()) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return null
        synchronized(memory) { memory[pkg] = bitmap }
        return Icon.createWithBitmap(bitmap)
    }

    private fun fileFor(pkg: String) =
        File(directory, pkg.replace(Regex("[^A-Za-z0-9_.]"), "_") + ".png")

    private companion object {
        const val SCOPE = "icons"
    }
}
