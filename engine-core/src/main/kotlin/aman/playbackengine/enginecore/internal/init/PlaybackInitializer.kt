package aman.playbackengine.enginecore.internal.init

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import aman.playbackengine.enginecore.PlaybackManager

/**
 * Internal initializer that uses the ContentProvider trick to grab the ApplicationContext
 * automatically at app startup. This eliminates the need for manual init() calls.
 */
internal class PlaybackInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context?.applicationContext ?: return false
        aman.playbackengine.enginecore.PlaybackLogger.log("Initializer", "Library auto-waking via ContentProvider")
        PlaybackManager.internalInit(context)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
