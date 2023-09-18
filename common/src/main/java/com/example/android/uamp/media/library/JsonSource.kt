/*
 * Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.uamp.media.library

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.EXTRA_MEDIA_GENRE
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat.STATUS_NOT_DOWNLOADED
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import com.example.android.uamp.media.MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS
import com.example.android.uamp.media.extensions.album
import com.example.android.uamp.media.extensions.albumArtUri
import com.example.android.uamp.media.extensions.albumArtist
import com.example.android.uamp.media.extensions.artist
import com.example.android.uamp.media.extensions.containsCaseInsensitive
import com.example.android.uamp.media.extensions.displayDescription
import com.example.android.uamp.media.extensions.displayIconUri
import com.example.android.uamp.media.extensions.displaySubtitle
import com.example.android.uamp.media.extensions.displayTitle
import com.example.android.uamp.media.extensions.downloadStatus
import com.example.android.uamp.media.extensions.duration
import com.example.android.uamp.media.extensions.flag
import com.example.android.uamp.media.extensions.genre
import com.example.android.uamp.media.extensions.id
import com.example.android.uamp.media.extensions.mediaUri
import com.example.android.uamp.media.extensions.title
import com.example.android.uamp.media.extensions.trackCount
import com.example.android.uamp.media.extensions.trackNumber
import com.google.android.exoplayer2.C
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Source of [MediaMetadataCompat] objects created from a basic JSON stream.
 *
 * The definition of the JSON is specified in the docs of [JsonMusic] in this file,
 * which is the object representation of it.
 */
internal class JsonSource(private val source: Uri) : AbstractMusicSource() {


    companion object {
        const val ORIGINAL_ARTWORK_URI_KEY = "com.example.android.uamp.JSON_ARTWORK_URI"
    }
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var catalog: List<MediaMetadataCompat> = emptyList()

    private val _albums = MutableSharedFlow<List<Album>>()

    private val _songToPlay = MutableSharedFlow<SongToPlay>()
    override val songToPlay: SharedFlow<SongToPlay>
        get() = _songToPlay

    init {
        state = STATE_INITIALIZING
        scope.launch {
            _albums.collect{
                Log.d(TAG, "Albums: $it")
                val recommended = it.take(it.size/2)
                val albums = it.drop(it.size/2)
                val recommendedMediaItems = recommended.toMediaMetadataCompats(UAMP_ALBUMS_ROOT)
                val albumMediaItems = albums.toMediaMetadataCompats(UAMP_RECOMMENDED_ROOT)
                val updatedList = buildList {
                    addAll(recommendedMediaItems)
                    addAll(albumMediaItems)
                }
                Log.d(TAG, "recommendedMediaItems: $recommendedMediaItems")
                Log.d(TAG, "albumMediaItems: $albumMediaItems")
                catalog = updatedList
                state = when(it.isEmpty()){
                    true -> STATE_ERROR
                    else -> STATE_INITIALIZED
                }
            }
        }
    }

    private fun loadAlbums() {
        scope.launch {
            val catalog = getJsonCatalog(source)
            val albums = catalog?.let {
                    catalog ->
                catalog.music.groupBy {
                    it.album
                }
            }?.map {
                val firstItem = it.value.first()
                Album(
                    title = it.key,
                    artist = firstItem.artist,
                    icon = firstItem.image,
                    genre = firstItem.genre,
                    totalTrack = firstItem.totalTrackCount
                )
            }
            _albums.emit(albums.orEmpty().toList())
        }
    }

    override fun iterator(): Iterator<MediaMetadataCompat> = catalog.iterator()

    override suspend fun load() {
        Log.d(TAG, "Load")
        loadAlbums()
    }

    override fun load(parentId: String, onSuccess: (List<MediaMetadataCompat>) -> Unit, onFailure: (Exception) -> Unit) {
        Log.d(TAG, "load:: parentId: $parentId")
        scope.launch {
            when(val catalog = getJsonCatalog(source)){
                null -> onFailure(IOException("Items not available"))
                else -> {
                    val baseUri = source.toString().removeSuffix(source.lastPathSegment ?: "")

                    val mediaMetadataCompats = catalog.music.filter { parentId == it.album }.map {
                            song ->
                        source.scheme?.let { scheme ->
                            if (!song.source.startsWith(scheme)) {
                                song.source = baseUri + song.source
                            }
                            if (!song.image.startsWith(scheme)) {
                                song.image = baseUri + song.image
                            }
                        }
                        val jsonImageUri = Uri.parse(song.image)
                        val imageUri = AlbumArtContentProvider.mapUri(jsonImageUri)
                        MediaMetadataCompat.Builder()
                            .from(song)
                            .apply {
                                displayIconUri = imageUri.toString() // Used by ExoPlayer and Notification
                                albumArtUri = imageUri.toString()
                                // Keep the original artwork URI for being included in Cast metadata object.
                                putString(ORIGINAL_ARTWORK_URI_KEY, jsonImageUri.toString())
                            }
                            .build()
                    }
                    // Add description keys to be used by the ExoPlayer MediaSession extension when
                    // announcing metadata changes.
                    mediaMetadataCompats.forEach { it.description.extras?.putAll(it.bundle) }
                    onSuccess(mediaMetadataCompats)
                }
            }
        }
    }

    override fun find(playbackStartPositionMs: Long, playWhenReady: Boolean, id: String) {
        scope.launch {
            val music = getJsonCatalog(source)?.music.orEmpty()
            when(music.isEmpty()){
                true ->  _songToPlay.emit(
                    SongToPlay(
                        playbackStartPositionMs,
                        playWhenReady,
                        null,
                        emptyList()
                    )
                )
                else -> {
                    val currentSong = music.firstOrNull { it.id == id }
                    val album = currentSong?.album
                    val albumSongs = music.filter { it.id != id && it.album == album }
                    Log.d(TAG, "find:: current Song: $currentSong, albums: $albumSongs")
                    val currentSongMetaData = currentSong?.toPlayableMediaCompat()
                    val albumMetaData = (listOf(currentSongMetaData) + albumSongs.map {
                        it.toPlayableMediaCompat()
                    }).filterNotNull()
                    _songToPlay.emit(
                        SongToPlay(
                            playbackStartPositionMs,
                            playWhenReady,
                            currentSongMetaData,
                            albumMetaData
                        )
                    )
                }
            }


        }
    }


    private suspend fun getJsonCatalog(catalogUri: Uri): JsonCatalog? = withContext(Dispatchers.IO){
        return@withContext try {
            downloadJson(catalogUri)
        } catch (ioException: IOException) {
            return@withContext null
        }
    }


    /**
     * Attempts to download a catalog from a given Uri.
     *
     * @param catalogUri URI to attempt to download the catalog form.
     * @return The catalog downloaded, or an empty catalog if an error occurred.
     */
    @Throws(IOException::class)
    private fun downloadJson(catalogUri: Uri): JsonCatalog {
        val catalogConn = URL(catalogUri.toString())
        val reader = BufferedReader(InputStreamReader(catalogConn.openStream()))
        return Gson().fromJson(reader, JsonCatalog::class.java)
    }

    override fun search(query: String, playWhenReady: Boolean, extras: Bundle) {
        Log.d(TAG, "search: Query: $query, playWhenReady: $playWhenReady")
        scope.launch {
            val playbackStartPositionMs =
                extras.getLong(MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS, C.TIME_UNSET)

            val result = search(extras, query)
            _songToPlay.emit(
                SongToPlay(
                    playbackStartPositionMs,
                    playWhenReady,
                    result.getOrNull(0),
                    result
                )
            )
        }
    }

    override fun search(
        query: String,
        playWhenReady: Boolean,
        extras: Bundle,
        onResult: (List<MediaMetadataCompat>) -> Unit
    ) {
        scope.launch {
            val result = search(extras, query)
            onResult(
                result
            )
        }
    }

    private suspend fun search(
        extras: Bundle,
        query: String
    ): List<MediaMetadataCompat> {
        val songs = getJsonCatalog(source)?.let {
            it.music.map {
                it.toPlayableMediaCompat()
            }
        }?.filterNotNull().orEmpty()

        // First attempt to search with the "focus" that's provided in the extras.
        val focusSearchResult = when (extras.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE -> {
                // For a Genre focused search, only genre is set.
                val genre = extras.getString(EXTRA_MEDIA_GENRE)
                Log.d(TAG, "Focused genre search: '$genre'")
                songs.filter { song ->
                    song.genre == genre
                }
            }

            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> {
                // For an Artist focused search, only the artist is set.
                val artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                Log.d(TAG, "Focused artist search: '$artist'")
                songs.filter { song ->
                    (song.artist == artist || song.albumArtist == artist)
                }
            }

            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> {
                // For an Album focused search, album and artist are set.
                val artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                val album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                Log.d(TAG, "Focused album search: album='$album' artist='$artist")
                songs.filter { song ->
                    (song.artist == artist || song.albumArtist == artist) && song.album == album
                }
            }

            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> {
                // For a Song (aka Media) focused search, title, album, and artist are set.
                val title = extras.getString(MediaStore.EXTRA_MEDIA_TITLE)
                val album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                val artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                Log.d(TAG, "Focused media search: title='$title' album='$album' artist='$artist")
                songs.filter { song ->
                    (song.artist == artist || song.albumArtist == artist) && song.album == album
                            && song.title == title
                }
            }

            else -> {
                // There isn't a focus, so no results yet.
                emptyList()
            }
        }

        // If there weren't any results from the focused search (or if there wasn't a focus
        // to begin with), try to find any matches given the 'query' provided, searching against
        // a few of the fields.
        // In this sample, we're just checking a few fields with the provided query, but in a
        // more complex app, more logic could be used to find fuzzy matches, etc...


        return when (focusSearchResult.isEmpty()) {
            true -> when (query.isNotBlank()) {
                true -> {
                    Log.d(TAG, "Unfocused search for '$query'")
                    songs.filter { song ->
                        song.title.containsCaseInsensitive(query)
                                || song.genre.containsCaseInsensitive(query)
                    }
                }

                else -> {
                    // If the user asked to "play music", or something similar, the query will also
                    // be blank. Given the small catalog of songs in the sample, just return them
                    // all, shuffled, as something to play.
                    Log.d(TAG, "Unfocused search without keyword")
                    songs.shuffled()
                }
            }

            else -> focusSearchResult
        }
    }
}

private fun JsonMusic?.toPlayableMediaCompat(): MediaMetadataCompat? = this?.let{
    MediaMetadataCompat.Builder()
        .from(it)
        .apply {
            val jsonImageUri = Uri.parse(it.image)
            val imageUri = AlbumArtContentProvider.mapUri(jsonImageUri)
            displayIconUri = imageUri.toString() // Used by ExoPlayer and Notification
            albumArtUri = imageUri.toString()
            // Keep the original artwork URI for being included in Cast metadata object.
            putString(JsonSource.ORIGINAL_ARTWORK_URI_KEY, jsonImageUri.toString())
        }
        .build()
}



private fun List<Album>.toMediaMetadataCompats(root: String): List<MediaMetadataCompat>{
    val mediaMetadataCompats = map {
        val jsonImageUri = Uri.parse(it.icon)
        val imageUri = AlbumArtContentProvider.mapUri(jsonImageUri)
        MediaMetadataCompat.Builder()
            .from(root, it)
            .apply {
                displayIconUri = imageUri.toString() // Used by ExoPlayer and Notification
                albumArtUri = imageUri.toString()
                // Keep the original artwork URI for being included in Cast metadata object.
                putString(JsonSource.ORIGINAL_ARTWORK_URI_KEY, jsonImageUri.toString())
            }
            .build()
    }.toList()
    mediaMetadataCompats.forEach { it.description.extras?.putAll(it.bundle) }
    return mediaMetadataCompats
}

fun MediaMetadataCompat.Builder.from(root: String, album: Album): MediaMetadataCompat.Builder{
    id = album.title
    title = album.title
    artist = album.artist
    genre = album.genre
    albumArtUri = album.icon
    trackCount = album.totalTrack
    flag = MediaItem.FLAG_BROWSABLE
    displayTitle = album.title
    displaySubtitle = album.artist
    displayIconUri = album.icon
    this.album = album.title
    putString("root", root)
    return this
}

/**
 * Extension method for [MediaMetadataCompat.Builder] to set the fields from
 * our JSON constructed object (to make the code a bit easier to see).
 */
fun MediaMetadataCompat.Builder.from(jsonMusic: JsonMusic): MediaMetadataCompat.Builder {
    // The duration from the JSON is given in seconds, but the rest of the code works in
    // milliseconds. Here's where we convert to the proper units.
    val durationMs = TimeUnit.SECONDS.toMillis(jsonMusic.duration)

    id = jsonMusic.id
    title = jsonMusic.title
    artist = jsonMusic.artist
    album = jsonMusic.album
    duration = durationMs
    genre = jsonMusic.genre
    mediaUri = jsonMusic.source
    albumArtUri = jsonMusic.image
    trackNumber = jsonMusic.trackNumber
    trackCount = jsonMusic.totalTrackCount
    flag = MediaItem.FLAG_PLAYABLE

    // To make things easier for *displaying* these, set the display properties as well.
    displayTitle = jsonMusic.title
    displaySubtitle = jsonMusic.artist
    displayDescription = jsonMusic.album
    displayIconUri = jsonMusic.image

    // Add downloadStatus to force the creation of an "extras" bundle in the resulting
    // MediaMetadataCompat object. This is needed to send accurate metadata to the
    // media session during updates.
    downloadStatus = STATUS_NOT_DOWNLOADED

    // Allow it to be used in the typical builder style.
    return this
}

/**
 * Wrapper object for our JSON in order to be processed easily by GSON.
 */
class JsonCatalog {
    var music: List<JsonMusic> = ArrayList()
}

/**
 * An individual piece of music included in our JSON catalog.
 * The format from the server is as specified:
 * ```
 *     { "music" : [
 *     { "title" : // Title of the piece of music
 *     "album" : // Album title of the piece of music
 *     "artist" : // Artist of the piece of music
 *     "genre" : // Primary genre of the music
 *     "source" : // Path to the music, which may be relative
 *     "image" : // Path to the art for the music, which may be relative
 *     "trackNumber" : // Track number
 *     "totalTrackCount" : // Track count
 *     "duration" : // Duration of the music in seconds
 *     "site" : // Source of the music, if applicable
 *     }
 *     ]}
 * ```
 *
 * `source` and `image` can be provided in either relative or
 * absolute paths. For example:
 * ``
 *     "source" : "https://www.example.com/music/ode_to_joy.mp3",
 *     "image" : "ode_to_joy.jpg"
 * ``
 *
 * The `source` specifies the full URI to download the piece of music from, but
 * `image` will be fetched relative to the path of the JSON file itself. This means
 * that if the JSON was at "https://www.example.com/json/music.json" then the image would be found
 * at "https://www.example.com/json/ode_to_joy.jpg".
 */
@Suppress("unused")
class JsonMusic {
    var id: String = ""
    var title: String = ""
    var album: String = ""
    var artist: String = ""
    var genre: String = ""
    var source: String = ""
    var image: String = ""
    var trackNumber: Long = 0
    var totalTrackCount: Long = 0
    var duration: Long = C.TIME_UNSET
    var site: String = ""
}

data class Album(
    val title: String,
    var artist: String = "",
    val icon: String = "",
    val genre: String,
    val totalTrack: Long,
)


data class SongToPlay(val playBackMs: Long, val playWhenReady: Boolean, val song: MediaMetadataCompat?, val playList: List<MediaMetadataCompat>)

private val TAG: String = JsonSource::class.java.simpleName