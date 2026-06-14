package aman.playbackengine.enginecore.internal.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import aman.playbackengine.enginecore.PlayableMedia
import aman.playbackengine.enginecore.PlaybackLogger

/**
 * Native SQLite helper for persistent playback state.
 * Structure: Zero-JSON, fully relational schema.
 * Optimized for Three-Tiered Persistence (Position, Metadata, Full Queue).
 */
internal class PlaybackDatabaseHelper(context: Context) : 
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "playback_persistence.db"
        private const val DATABASE_VERSION = 2
        private const val TAG = "PlaybackDB"

        // Meta Table (Tier 1 & 2)
        private const val TABLE_META = "playback_meta"
        private const val COL_META_KEY = "m_key"
        private const val COL_META_VALUE = "m_value"

        // Media Table (Tier 3)
        private const val TABLE_MEDIA = "media_items"
        private const val COL_UID = "uid"
        private const val COL_ID = "id"
        private const val COL_STREAM_TYPE = "stream_type"
        private const val COL_URI = "uri"
        private const val COL_TITLE = "title"
        private const val COL_SUBTITLE = "subtitle"
        private const val COL_ARTWORK_URI = "artwork_uri"
        private const val COL_LOCAL_PATH = "local_path"
        private const val COL_DURATION_MS = "duration_ms"
        private const val COL_IS_VIDEO = "is_video"
        private const val COL_TRACK_GAIN = "track_gain"
        private const val COL_ALBUM_GAIN = "album_gain"
        private const val COL_PEAK = "peak"
        private const val COL_MASTER_ORDER = "master_order"
        private const val COL_SHUFFLED_ORDER = "shuffled_order"

        // Extras Table (Tier 3)
        private const val TABLE_EXTRAS = "media_extras"
        private const val COL_EXTRA_MEDIA_UID = "media_uid"
        private const val COL_EXTRA_KEY = "key"
        private const val COL_EXTRA_VALUE = "value"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE_META ($COL_META_KEY TEXT PRIMARY KEY, $COL_META_VALUE TEXT)")
        
        db.execSQL("""
            CREATE TABLE $TABLE_MEDIA (
                $COL_UID TEXT PRIMARY KEY,
                $COL_ID TEXT,
                $COL_STREAM_TYPE TEXT,
                $COL_URI TEXT,
                $COL_TITLE TEXT,
                $COL_SUBTITLE TEXT,
                $COL_ARTWORK_URI TEXT,
                $COL_LOCAL_PATH TEXT,
                $COL_DURATION_MS INTEGER,
                $COL_IS_VIDEO INTEGER,
                $COL_TRACK_GAIN REAL,
                $COL_ALBUM_GAIN REAL,
                $COL_PEAK REAL,
                $COL_MASTER_ORDER INTEGER,
                $COL_SHUFFLED_ORDER INTEGER
            )
        """.trimIndent())
        
        db.execSQL("""
            CREATE TABLE $TABLE_EXTRAS (
                $COL_EXTRA_MEDIA_UID TEXT,
                $COL_EXTRA_KEY TEXT,
                $COL_EXTRA_VALUE TEXT,
                PRIMARY KEY ($COL_EXTRA_MEDIA_UID, $COL_EXTRA_KEY),
                FOREIGN KEY ($COL_EXTRA_MEDIA_UID) REFERENCES $TABLE_MEDIA($COL_UID) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_stream_type ON $TABLE_MEDIA ($COL_STREAM_TYPE)")
        db.execSQL("CREATE INDEX idx_extras_uid ON $TABLE_EXTRAS ($COL_EXTRA_MEDIA_UID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_META")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MEDIA")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EXTRAS")
        onCreate(db)
    }

    // --- Tier 1: Position Only (O(1)) ---

    fun updatePosition(type: String, positionMs: Long) {
        val db = writableDatabase
        saveMeta(db, "${type}_position_ms", positionMs.toString())
    }

    // --- Tier 2: Metadata & Pointers (O(1)) ---

    fun saveMetadata(type: String, snapshot: QueueSnapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            saveMeta(db, "${type}_current_uid", snapshot.currentUid)
            saveMeta(db, "${type}_shuffle_on", snapshot.isShuffleEnabled.toString())
            saveMeta(db, "${type}_repeat_mode", snapshot.repeatMode.name)
            saveMeta(db, "${type}_speed", snapshot.playbackSpeed.toString())
            saveMeta(db, "${type}_pitch", snapshot.playbackPitch.toString())
            saveMeta(db, "${type}_position_ms", snapshot.positionMs.toString())
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            PlaybackLogger.log(TAG, "Failed to save Metadata for $type: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    // --- Tier 3: Full Queue Sync (O(N)) ---

    fun saveFullQueue(type: String, snapshot: QueueSnapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. Save Metadata first
            saveMeta(db, "${type}_current_uid", snapshot.currentUid)
            saveMeta(db, "${type}_shuffle_on", snapshot.isShuffleEnabled.toString())
            saveMeta(db, "${type}_repeat_mode", snapshot.repeatMode.name)
            saveMeta(db, "${type}_speed", snapshot.playbackSpeed.toString())
            saveMeta(db, "${type}_pitch", snapshot.playbackPitch.toString())
            saveMeta(db, "${type}_position_ms", snapshot.positionMs.toString())

            // 2. Clear old items for this stream type (Cascade will handle extras)
            db.delete(TABLE_MEDIA, "$COL_STREAM_TYPE = ?", arrayOf(type))

            // 3. Batch insert new items
            snapshot.masterList.forEachIndexed { index, media ->
                val shuffledIdx = snapshot.shuffledList.indexOfFirst { it.uid == media.uid }
                val values = ContentValues().apply {
                    put(COL_UID, media.uid)
                    put(COL_ID, media.id)
                    put(COL_STREAM_TYPE, type)
                    put(COL_URI, media.uri.toString())
                    put(COL_TITLE, media.title)
                    put(COL_SUBTITLE, media.subtitle)
                    put(COL_ARTWORK_URI, media.artworkUri?.toString())
                    put(COL_LOCAL_PATH, media.localPath)
                    put(COL_DURATION_MS, media.durationMs)
                    put(COL_IS_VIDEO, if (media.isVideo) 1 else 0)
                    put(COL_TRACK_GAIN, media.trackGain)
                    put(COL_ALBUM_GAIN, media.albumGain)
                    put(COL_PEAK, media.peak)
                    put(COL_MASTER_ORDER, index)
                    put(COL_SHUFFLED_ORDER, if (shuffledIdx == -1) null else shuffledIdx)
                }
                db.insertWithOnConflict(TABLE_MEDIA, null, values, SQLiteDatabase.CONFLICT_REPLACE)

                // Insert Extras
                media.extras.forEach { (key, value) ->
                    val extraValues = ContentValues().apply {
                        put(COL_EXTRA_MEDIA_UID, media.uid)
                        put(COL_EXTRA_KEY, key)
                        put(COL_EXTRA_VALUE, value)
                    }
                    db.insertWithOnConflict(TABLE_EXTRAS, null, extraValues, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }

            db.setTransactionSuccessful()
            PlaybackLogger.log(TAG, "Full Sync for $type (${snapshot.masterList.size} items)")
        } catch (e: Exception) {
            PlaybackLogger.log(TAG, "Failed to perform Full Sync for $type: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    fun loadSnapshot(type: String): QueueSnapshot? {
        val db = readableDatabase
        
        val currentUid = getMeta(db, "${type}_current_uid") ?: return null
        val shuffleOn = getMeta(db, "${type}_shuffle_on")?.toBoolean() ?: false
        val repeatModeName = getMeta(db, "${type}_repeat_mode") ?: "OFF"
        val repeatMode = try { aman.playbackengine.enginecore.RepeatMode.valueOf(repeatModeName) } catch(e: Exception) { aman.playbackengine.enginecore.RepeatMode.OFF }
        val speed = getMeta(db, "${type}_speed")?.toFloat() ?: 1.0f
        val pitch = getMeta(db, "${type}_pitch")?.toFloat() ?: 1.0f
        val positionMs = getMeta(db, "${type}_position_ms")?.toLong() ?: 0L

        // Fetch Extras first for all media of this type to avoid N+1
        val extrasMap = mutableMapOf<String, MutableMap<String, String>>()
        val extrasCursor = db.rawQuery("""
            SELECT e.$COL_EXTRA_MEDIA_UID, e.$COL_EXTRA_KEY, e.$COL_EXTRA_VALUE 
            FROM $TABLE_EXTRAS e
            JOIN $TABLE_MEDIA m ON e.$COL_EXTRA_MEDIA_UID = m.$COL_UID
            WHERE m.$COL_STREAM_TYPE = ?
        """.trimIndent(), arrayOf(type))
        
        extrasCursor.use { c ->
            val uidIdx = c.getColumnIndexOrThrow(COL_EXTRA_MEDIA_UID)
            val keyIdx = c.getColumnIndexOrThrow(COL_EXTRA_KEY)
            val valIdx = c.getColumnIndexOrThrow(COL_EXTRA_VALUE)
            while (c.moveToNext()) {
                val uid = c.getString(uidIdx)
                val key = c.getString(keyIdx)
                val value = c.getString(valIdx)
                extrasMap.getOrPut(uid) { mutableMapOf() }[key] = value
            }
        }

        val masterList = mutableListOf<PlayableMedia>()
        val restoredShuffledList = mutableListOf<PlayableMedia>()

        val cursor = db.query(
            TABLE_MEDIA,
            null,
            "$COL_STREAM_TYPE = ?",
            arrayOf(type),
            null,
            null,
            "$COL_MASTER_ORDER ASC"
        )

        cursor.use { c ->
            val uidIdx = c.getColumnIndexOrThrow(COL_UID)
            val idIdx = c.getColumnIndexOrThrow(COL_ID)
            val uriIdx = c.getColumnIndexOrThrow(COL_URI)
            val titleIdx = c.getColumnIndexOrThrow(COL_TITLE)
            val subtitleIdx = c.getColumnIndexOrThrow(COL_SUBTITLE)
            val artIdx = c.getColumnIndexOrThrow(COL_ARTWORK_URI)
            val pathIdx = c.getColumnIndexOrThrow(COL_LOCAL_PATH)
            val durIdx = c.getColumnIndexOrThrow(COL_DURATION_MS)
            val videoIdx = c.getColumnIndexOrThrow(COL_IS_VIDEO)
            val tGainIdx = c.getColumnIndexOrThrow(COL_TRACK_GAIN)
            val aGainIdx = c.getColumnIndexOrThrow(COL_ALBUM_GAIN)
            val peakIdx = c.getColumnIndexOrThrow(COL_PEAK)
            
            while (c.moveToNext()) {
                val uid = c.getString(uidIdx)
                val media = PlayableMedia(
                    id = c.getString(idIdx),
                    uid = uid,
                    uri = Uri.parse(c.getString(uriIdx)),
                    title = c.getString(titleIdx),
                    subtitle = c.getString(subtitleIdx),
                    artworkUri = c.getString(artIdx)?.let { Uri.parse(it) },
                    localPath = c.getString(pathIdx),
                    durationMs = c.getLong(durIdx),
                    isVideo = c.getInt(videoIdx) == 1,
                    extras = extrasMap[uid] ?: emptyMap(),
                    trackGain = if (c.isNull(tGainIdx)) null else c.getDouble(tGainIdx),
                    albumGain = if (c.isNull(aGainIdx)) null else c.getDouble(aGainIdx),
                    peak = if (c.isNull(peakIdx)) null else c.getDouble(peakIdx)
                )
                masterList.add(media)
            }
        }

        // Fetch shuffled list specifically
        val shuffledCursor = db.query(
            TABLE_MEDIA,
            null,
            "$COL_STREAM_TYPE = ? AND $COL_SHUFFLED_ORDER IS NOT NULL",
            arrayOf(type),
            null,
            null,
            "$COL_SHUFFLED_ORDER ASC"
        )
        
        shuffledCursor.use { c ->
            val uidIdx = c.getColumnIndexOrThrow(COL_UID)
            val idIdx = c.getColumnIndexOrThrow(COL_ID)
            val uriIdx = c.getColumnIndexOrThrow(COL_URI)
            val titleIdx = c.getColumnIndexOrThrow(COL_TITLE)
            val subtitleIdx = c.getColumnIndexOrThrow(COL_SUBTITLE)
            val artIdx = c.getColumnIndexOrThrow(COL_ARTWORK_URI)
            val pathIdx = c.getColumnIndexOrThrow(COL_LOCAL_PATH)
            val durIdx = c.getColumnIndexOrThrow(COL_DURATION_MS)
            val videoIdx = c.getColumnIndexOrThrow(COL_IS_VIDEO)
            val tGainIdx = c.getColumnIndexOrThrow(COL_TRACK_GAIN)
            val aGainIdx = c.getColumnIndexOrThrow(COL_ALBUM_GAIN)
            val peakIdx = c.getColumnIndexOrThrow(COL_PEAK)

            while (c.moveToNext()) {
                val uid = c.getString(uidIdx)
                val media = PlayableMedia(
                    id = c.getString(idIdx),
                    uid = uid,
                    uri = Uri.parse(c.getString(uriIdx)),
                    title = c.getString(titleIdx),
                    subtitle = c.getString(subtitleIdx),
                    artworkUri = c.getString(artIdx)?.let { Uri.parse(it) },
                    localPath = c.getString(pathIdx),
                    durationMs = c.getLong(durIdx),
                    isVideo = c.getInt(videoIdx) == 1,
                    extras = extrasMap[uid] ?: emptyMap(),
                    trackGain = if (c.isNull(tGainIdx)) null else c.getDouble(tGainIdx),
                    albumGain = if (c.isNull(aGainIdx)) null else c.getDouble(aGainIdx),
                    peak = if (c.isNull(peakIdx)) null else c.getDouble(peakIdx)
                )
                restoredShuffledList.add(media)
            }
        }

        if (masterList.isEmpty()) return null

        return QueueSnapshot(
            masterList = masterList,
            shuffledList = restoredShuffledList,
            isShuffleEnabled = shuffleOn,
            repeatMode = repeatMode,
            playbackSpeed = speed,
            playbackPitch = pitch,
            currentUid = if (currentUid == "null") null else currentUid,
            positionMs = positionMs
        )
    }

    private fun saveMeta(db: SQLiteDatabase, key: String, value: String?) {
        val values = ContentValues().apply {
            put(COL_META_KEY, key)
            put(COL_META_VALUE, value ?: "null")
        }
        db.insertWithOnConflict(TABLE_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun getMeta(db: SQLiteDatabase, key: String): String? {
        val cursor = db.query(TABLE_META, arrayOf(COL_META_VALUE), "$COL_META_KEY = ?", arrayOf(key), null, null, null)
        return cursor.use { 
            if (it.moveToFirst()) it.getString(0).takeIf { v -> v != "null" } else null
        }
    }
}
