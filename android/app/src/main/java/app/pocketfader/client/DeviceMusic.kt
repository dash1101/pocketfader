package app.pocketfader.client

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Music files already on the phone (through Android's media library). */
object DeviceMusic {
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    /** All songs, sorted by artist then title. Blocking; needs [permission]. */
    fun load(ctx: Context): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID,
        )
        val art = Uri.parse("content://media/external/audio/albumart")
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
            MediaStore.Audio.Media.ARTIST + " COLLATE NOCASE, " + MediaStore.Audio.Media.TITLE + " COLLATE NOCASE",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val artist = c.getString(iArtist).orEmpty().let { if (it == "<unknown>") "" else it }
                out.add(
                    LocalItem(
                        videoId = null, uri = uri.toString(), title = c.getString(iTitle).orEmpty(), artist = artist,
                        art = ContentUris.withAppendedId(art, c.getLong(iAlbumId)).toString(),
                        durationSec = c.getLong(iDur) / 1000.0, album = c.getString(iAlbum).orEmpty(),
                    )
                )
            }
        }
        return out
    }
}
