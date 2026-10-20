package com.topjohnwu.magisk.ui.webui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/** Loads app icons for `ksu://icon/<package>` requests from the WebUI. */
object AppIconUtil {

    private const val CACHE_SIZE = 200
    private val iconCache = LruCache<String, Bitmap>(CACHE_SIZE)

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else size
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else size
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun getAppIconDrawable(context: Context, packageName: String): Drawable? {
        val pm = context.packageManager
        val appInfo = AppList.applist.firstOrNull { it.packageName == packageName }
            ?.packageInfo?.applicationInfo
        if (appInfo != null) return appInfo.loadIcon(pm)
        return try {
            pm.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun loadAppIconSync(context: Context, packageName: String, sizePx: Int): Bitmap? {
        iconCache.get(packageName)?.let { return it }
        return try {
            val drawable = getAppIconDrawable(context, packageName) ?: return null
            drawableToBitmap(drawable, sizePx).also { iconCache.put(packageName, it) }
        } catch (e: Exception) {
            null
        }
    }
}
