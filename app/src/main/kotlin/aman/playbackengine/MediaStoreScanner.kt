package aman.playbackengine

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import aman.playbackengine.enginecore.PlayableMedia

object MediaStoreScanner {

    fun fetchAudio(context: Context): List<PlayableMedia> {
        val audioList = mutableListOf<PlayableMedia>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                val artist = cursor.getString(artistColumn)
                val duration = cursor.getLong(durationColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val artworkUri = ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )

                audioList.add(
                    PlayableMedia(
                        id = id.toString(),
                        uri = contentUri,
                        title = title,
                        subtitle = artist,
                        artworkUri = artworkUri,
                        durationMs = duration,
                        isVideo = false
                    )
                )
            }
        }
        return audioList
    }

    fun fetchVideo(context: Context): List<PlayableMedia> {
        val videoList = mutableListOf<PlayableMedia>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION
        )

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                val duration = cursor.getLong(durationColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                videoList.add(
                    PlayableMedia(
                        id = id.toString(),
                        uri = contentUri,
                        title = title,
                        durationMs = duration,
                        isVideo = true,
                        artworkUri = contentUri // MediaStore can often provide thumbnails via the content URI itself or separate thumbnail APIs
                    )
                )
            }
        }
        return videoList
    }
}
