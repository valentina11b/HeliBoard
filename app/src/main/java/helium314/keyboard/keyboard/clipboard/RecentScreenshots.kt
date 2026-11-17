// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.Manifest
import android.content.ClipDescription
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File

/** Recent screenshots from the media store, for the row in the clipboard view. */
object RecentScreenshots {
    private const val TAG = "RecentScreenshots"
    private const val MAX_COUNT = 12

    class Screenshot(val uri: Uri, val mimeType: String)

    /** Needs Android 10 for RELATIVE_PATH and loadThumbnail. */
    fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Full or partial (user-selected photos) access is enough. */
    fun hasPermission(context: Context) = permissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isEnabled(context: Context) = isSupported() && hasPermission(context)
        && context.prefs().getBoolean(Settings.PREF_CLIPBOARD_SHOW_SCREENSHOTS, Defaults.PREF_CLIPBOARD_SHOW_SCREENSHOTS)

    @RequiresApi(Build.VERSION_CODES.Q)
    fun query(context: Context): List<Screenshot> {
        val result = mutableListOf<Screenshot>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE)
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("%Screenshots%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && result.size < MAX_COUNT) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    result.add(Screenshot(uri, cursor.getString(1) ?: "image/png"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not query screenshots", e)
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun loadThumbnail(context: Context, screenshot: Screenshot, size: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(screenshot.uri, Size(size, size), null)
    } catch (e: Exception) {
        Log.w(TAG, "could not load thumbnail for ${screenshot.uri}", e)
        null
    }

    /** Copies the screenshot to our own files, because other apps can only read content we can grant. */
    fun contentInfo(context: Context, screenshot: Screenshot): InputContentInfoCompat? {
        val dir = File(context.filesDir, "screenshots")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(screenshot.mimeType) ?: "png"
        val file = File(dir, "screenshot_${ContentUris.parseId(screenshot.uri)}.$extension")
        try {
            FileUtils.copyContentUriToNewFile(screenshot.uri, context, file)
        } catch (e: Exception) {
            Log.w(TAG, "could not copy screenshot ${screenshot.uri}", e)
            return null
        }
        val uri = FileProvider.getUriForFile(context, context.getString(R.string.clipboard_provider_authority), file)
        return InputContentInfoCompat(uri, ClipDescription("screenshot", arrayOf(screenshot.mimeType)), null)
    }
}
