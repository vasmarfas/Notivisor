package com.vasmarfas.notivisor.phone.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val pkg: String, val label: String)

object AppCatalog {

    private const val ICON_PX = 128
    private val icons = LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true)

    suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launchable, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun icon(context: Context, pkg: String): ImageBitmap? {
        synchronized(icons) { if (icons.containsKey(pkg)) return icons[pkg] }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(pkg).toImageBitmap()
            }.getOrNull()
        }
        synchronized(icons) {
            icons[pkg] = bitmap
            while (icons.size > 200) icons.remove(icons.keys.first())
        }
        return bitmap
    }

    private fun Drawable.toImageBitmap(): ImageBitmap {
        (this as? BitmapDrawable)?.bitmap?.let { return it.asImageBitmap() }
        val bitmap = createBitmap(ICON_PX, ICON_PX)
        setBounds(0, 0, ICON_PX, ICON_PX)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}
