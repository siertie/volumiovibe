package com.example.volumiovibe

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PlaylistViewModel(application: Application) : ViewModel() {
    private val TAG = "VolumioCache" // Filter in Logcat with tag:VolumioCache
    private val webSocketManager = WebSocketManager
    private val xAiApi = XAiApi(BuildConfig.XAI_API_KEY)
    private val cacheDao = AppDatabase.getDatabase(application).cacheDao()

    var playlists by mutableStateOf<List<Playlist>>(emptyList())
    var isLoading by mutableStateOf(false)
    var aiSuggestions by mutableStateOf<List<Track>>(emptyList())
    private val pendingTracks = mutableMapOf<String, MutableList<Track>>()

    // Existing state properties
    private val _selectedVibe = mutableStateOf(PlaylistStateHolder.selectedVibe)
    var selectedVibe: String
        get() = _selectedVibe.value
        set(value) {
            _selectedVibe.value = value
            PlaylistStateHolder.selectedVibe = value
        }

    private val _vibeInput = mutableStateOf(PlaylistStateHolder.vibeInput)
    var vibeInput: String
        get() = _vibeInput.value
        set(value) {
            _vibeInput.value = value
            PlaylistStateHolder.vibeInput = value
        }

    private val _era = mutableStateOf(PlaylistStateHolder.era)
    var era: String
        get() = _era.value
        set(value) {
            _era.value = value
            PlaylistStateHolder.era = value
        }

    private val _language = mutableStateOf(PlaylistStateHolder.language)
    var language: String
        get() = _language.value
        set(value) {
            _language.value = value
            PlaylistStateHolder.language = value
        }

    private val _instrument = mutableStateOf(PlaylistStateHolder.instrument)
    var instrument: String
        get() = _instrument.value
        set(value) {
            _instrument.value = value
            PlaylistStateHolder.instrument = value
        }

    private val _playlistName = mutableStateOf(PlaylistStateHolder.playlistName)
    var playlistName: String
        get() = _playlistName.value
        set(value) {
            _playlistName.value = value
            PlaylistStateHolder.playlistName = value
        }

    private val _artists = mutableStateOf(PlaylistStateHolder.artists)
    var artists: String
        get() = _artists.value
        set(value) {
            _artists.value = value
            PlaylistStateHolder.artists = value
        }

    private val _numSongs = mutableStateOf(PlaylistStateHolder.numSongs)
    var numSongs: String
        get() = _numSongs.value
        set(value) {
            _numSongs.value = value
            PlaylistStateHolder.numSongs = value
        }

    private val _maxSongsPerArtist = mutableStateOf(PlaylistStateHolder.maxSongsPerArtist)
    var maxSongsPerArtist: String
        get() = _maxSongsPerArtist.value
        set(value) {
            _maxSongsPerArtist.value = value
            PlaylistStateHolder.maxSongsPerArtist = value
        }

    init {
        connectWebSocket()
        fetchAndCacheRecentPlaylists()
    }

    private fun connectWebSocket() {
        viewModelScope.launch {
            webSocketManager.initialize()
            webSocketManager.debugAllEvents()
            webSocketManager.on("pushListPlaylist") { args: Array<Any> ->
                Log.d(TAG, "Got pushListPlaylist: ${args[0]}")
                try {
                    val data = when (args[0]) {
                        is String -> if (args[0] == "undefined") JSONArray() else JSONArray(args[0] as String)
                        is JSONArray -> args[0] as JSONArray
                        else -> {
                            Log.w(TAG, "Weird pushListPlaylist type: ${args[0]?.javaClass?.simpleName}")
                            return@on
                        }
                    }
                    val playlistNames = (0 until data.length()).mapNotNull { data.optString(it).takeIf { it != "undefined" } }
                        .reversed() // Latest playlists are last, so reverse for newest first
                    Log.d(TAG, "Playlists (newest first): ${playlistNames.joinToString()}")
                    val now = System.currentTimeMillis()
                    val cachedPlaylists = playlistNames.mapIndexed { index, name ->
                        PlaylistCache(name = name, lastUpdated = now - index) // Simulate recency
                    }
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) {
                            cacheDao.insertPlaylists(cachedPlaylists)
                        }
                    }
                    viewModelScope.launch { // Fix for line 135
                        withContext(Dispatchers.IO) {
                            fetchTracksUntilLimit(playlistNames)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "pushListPlaylist parse failed: ${e.message}")
                }
            }
            webSocketManager.on("pushCreatePlaylist") { args: Array<Any> ->
                Log.d(TAG, "Got pushCreatePlaylist: ${args[0]}")
                fetchPlaylists() // Refresh playlist list
            }
            webSocketManager.on("pushAddToPlaylist") { args: Array<Any> ->
                Log.d(TAG, "Got pushAddToPlaylist: ${args[0]}")
                try {
                    val response = args[0] as JSONObject
                    val playlistName = response.optString("name", "")
                    if (playlistName.isNotBlank()) {
                        Log.d(TAG, "New track added to $playlistName, refreshing tracks")
                        viewModelScope.launch {
                            withContext(Dispatchers.IO) {
                                fetchTracksForPlaylist(playlistName)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "pushAddToPlaylist parse failed: ${e.message}")
                }
            }
            webSocketManager.on("pushRemoveFromPlaylist") { args: Array<Any> ->
                Log.d(TAG, "Got pushRemoveFromPlaylist: ${args[0]}")
                try {
                    val response = args[0] as JSONObject
                    val playlistName = response.optString("name", "")
                    val uri = response.optString("uri", "")
                    if (playlistName.isNotBlank() && uri.isNotBlank()) {
                        Log.d(TAG, "Track $uri removed from $playlistName, updating cache")
                        viewModelScope.launch {
                            withContext(Dispatchers.IO) {
                                cacheDao.clearTracksForPlaylist(playlistName)
                                fetchTracksForPlaylist(playlistName)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "pushRemoveFromPlaylist parse failed: ${e.message}")
                }
            }
            webSocketManager.on("pushBrowseLibrary") { args: Array<Any> ->
                Log.d(TAG, "Received pushBrowseLibrary: ${args[0]}")
                try {
                    val data = when (val arg = args[0]) {
                        is String -> {
                            if (arg == "undefined" || arg.isBlank()) {
                                Log.w(TAG, "Invalid pushBrowseLibrary response: $arg")
                                return@on
                            }
                            JSONObject(arg)
                        }
                        is JSONObject -> arg
                        else -> {
                            Log.w(TAG, "Unexpected pushBrowseLibrary arg type: ${arg?.javaClass?.simpleName}")
                            return@on
                        }
                    }
                    val navigation = data.optJSONObject("navigation") ?: return@on
                    val lists = navigation.optJSONArray("lists") ?: return@on
                    val results = mutableListOf<Track>()
                    var playlistName: String? = null
                    for (listIdx in 0 until lists.length()) {
                        val list = lists.optJSONObject(listIdx) ?: continue
                        val items = list.optJSONArray("items") ?: continue
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val type = item.optString("type", "")
                            val title = item.optString("title", "Unknown Title")
                            val artist = item.optString("artist", "Unknown Artist")
                            val uri = item.optString("uri", "")
                            val service = item.optString("service", "mpd")
                            val albumArt = item.optString("albumart", null)
                            if ((type == "song" || type == "folder-with-favourites") && uri.isNotBlank()) {
                                results.add(Track(title, artist, uri, service, albumArt, type))
                            }
                            if (item.has("uri") && item.getString("uri").startsWith("playlists/")) {
                                playlistName = item.getString("uri").removePrefix("playlists/")
                            }
                        }
                    }
                    if (playlistName != null) {
                        playlists = playlists.map { playlist ->
                            if (playlist.name == playlistName) Playlist(playlist.name, results) else playlist
                        }
                        pendingTracks[playlistName] = results.toMutableList()
                        Log.d(TAG, "Updated tracks for $playlistName: ${results.size} tracks")
                    } else {
                        aiSuggestions = results
                        Log.d(TAG, "Search results: ${results.size} tracks")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse pushBrowseLibrary: ${e.message}")
                }
            }
        }
    }

    fun fetchPlaylists() {
        Log.d(TAG, "Emittin’ listPlaylist")
        webSocketManager.emit("listPlaylist", JSONObject())
    }

    private suspend fun fetchTracksUntilLimit(playlistNames: List<String>) {
        Log.d(TAG, "Fetchin’ tracks until we hit 200 unique ones")
        val allTracks = mutableListOf<TrackCache>()
        for (playlistName in playlistNames) {
            if (allTracks.size >= 200) {
                Log.d(TAG, "Hit 200 tracks, stoppin’ fetch")
                break
            }
            val tracks = withContext(Dispatchers.IO) { fetchTracksForPlaylist(playlistName) }
            allTracks.addAll(tracks.filter { newTrack ->
                allTracks.none { it.uri == newTrack.uri } // Ensure no dupes
            })
            Log.d(TAG, "After $playlistName, got ${allTracks.size}/200 tracks")
        }
        withContext(Dispatchers.IO) {
            cacheDao.clearAllTracks()
            cacheDao.insertTracks(allTracks.take(200))
        }
        Log.d(TAG, "Cached ${allTracks.size}/200 tracks: ${allTracks.map { "${it.artist} - ${it.title}" }}")
    }

    private suspend fun fetchTracksForPlaylist(playlistName: String): List<TrackCache> {
        Log.d(TAG, "Fetchin’ tracks for playlist: $playlistName")
        aiSuggestions = emptyList() // Clear before new fetch
        webSocketManager.emit("browseLibrary", JSONObject().put("uri", "playlists/$playlistName"))
        val startTime = System.currentTimeMillis()
        while (aiSuggestions.isEmpty() && System.currentTimeMillis() - startTime < 5000) {
            delay(100)
        }
        val tracks = aiSuggestions.mapNotNull { track ->
            if (track.type == "song" && track.uri.isNotBlank()) {
                TrackCache(
                    uri = track.uri,
                    title = track.title,
                    artist = track.artist,
                    playlistName = playlistName,
                    service = track.service,
                    type = track.type,
                    lastUpdated = System.currentTimeMillis()
                )
            } else null
        }.distinctBy { it.uri }
        Log.d(TAG, "Got ${tracks.size} unique tracks for $playlistName: ${tracks.map { "${it.artist} - ${it.title}" }}")
        return tracks
    }

    private fun fetchAndCacheRecentPlaylists() {
        viewModelScope.launch {
            isLoading = true
            try {
                fetchPlaylists()
                val cachedPlaylists = withContext(Dispatchers.IO) { cacheDao.getPlaylists() }
                Log.d(TAG, "Cached playlists: ${cachedPlaylists.map { it.name }}")
                if (cachedPlaylists.isEmpty()) {
                    Log.d(TAG, "No cached playlists, waitin’ for pushListPlaylist")
                } else {
                    fetchTracksUntilLimit(cachedPlaylists.map { it.name }.reversed()) // Newest first
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch and cache failed: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    suspend fun getCachedTrackUris(): List<String> {
        val tracks = withContext(Dispatchers.IO) { cacheDao.getRecentTracks() }
        Log.d(TAG, "Sendin’ ${tracks.size} cached track URIs to Grok")
        return tracks.map { it.uri }
    }

    // Existing methods (unchanged)
    fun browsePlaylistTracks(playlistName: String) {
        webSocketManager.emit("browseLibrary", JSONObject().put("uri", "playlists/$playlistName"))
        Log.d(TAG, "Emitted browseLibrary for playlist: $playlistName")
    }

    fun createPlaylist(name: String) {
        webSocketManager.emit("createPlaylist", JSONObject().put("name", name))
        Log.d(TAG, "Emitted createPlaylist: $name")
    }

    fun addToPlaylist(playlistName: String, trackUri: String, trackType: String) {
        val service = when {
            trackUri.startsWith("tidal://") -> "tidal"
            trackType == "folder-with-favourites" -> "tidal"
            else -> "mpd"
        }
        val data = JSONObject().apply {
            put("name", playlistName)
            put("service", service)
            put("uri", trackUri)
        }
        webSocketManager.emit("addToPlaylist", data)
        Log.d(TAG, "Emitted addToPlaylist: $playlistName, $trackUri, service: $service")
        val track = aiSuggestions.find { it.uri == trackUri } ?: Track(
            title = "Unknown Title",
            artist = "Unknown Artist",
            uri = trackUri,
            service = service,
            albumArt = null,
            type = trackType
        )
        playlists = playlists.map { playlist ->
            if (playlist.name == playlistName) Playlist(playlist.name, playlist.tracks + track) else playlist
        }
        pendingTracks[playlistName] = (pendingTracks[playlistName] ?: mutableListOf()).apply { add(track) }
        Log.d(TAG, "Locally added track to $playlistName: ${track.title} by ${track.artist}")
    }

    fun removeFromPlaylist(playlistName: String, trackUri: String) {
        val data = JSONObject().apply {
            put("name", playlistName)
            put("uri", trackUri)
        }
        webSocketManager.emit("removeFromPlaylist", data)
        Log.d(TAG, "Emitted removeFromPlaylist: $playlistName, $trackUri")
        playlists = playlists.map { playlist ->
            if (playlist.name == playlistName) Playlist(playlist.name, playlist.tracks.filter { it.uri != trackUri }) else playlist
        }
        pendingTracks[playlistName] = pendingTracks[playlistName]?.filter { it.uri != trackUri }?.toMutableList() ?: mutableListOf()
        Log.d(TAG, "Updated pendingTracks for $playlistName: ${pendingTracks[playlistName]?.size ?: 0} tracks")
    }

    fun playPlaylist(playlistName: String) {
        webSocketManager.emit("playPlaylist", JSONObject().put("name", playlistName))
        Log.d(TAG, "Emitted playPlaylist: $playlistName")
    }

    fun deletePlaylist(playlistName: String) {
        webSocketManager.emit("deletePlaylist", JSONObject().put("name", playlistName))
        Log.d(TAG, "Emitted deletePlaylist: $playlistName")
        playlists = playlists.filter { it.name != playlistName }
        pendingTracks.remove(playlistName)
    }

    fun search(query: String) {
        webSocketManager.emit("search", JSONObject().put("value", query))
        Log.d(TAG, "Emitted search: $query")
    }

    suspend fun waitForSearchResults(timeoutMs: Long = 5000): List<Track> {
        val startTime = System.currentTimeMillis()
        while (aiSuggestions.isEmpty() && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(100)
        }
        return aiSuggestions
    }

    fun generateAiPlaylist(context: Context) {
        viewModelScope.launch {
            isLoading = true
            try {
                val vibe = if (selectedVibe != GrokConfig.VIBE_OPTIONS.first()) selectedVibe else vibeInput
                if (vibe.isNotBlank() && !PlaylistStateHolder.recentVibes.contains(vibe)) {
                    PlaylistStateHolder.recentVibes.add(0, vibe)
                    if (PlaylistStateHolder.recentVibes.size > 5) PlaylistStateHolder.recentVibes.removeLast()
                }
                val finalPlaylistName = if (playlistName.isBlank()) {
                    xAiApi.generatePlaylistName(
                        vibe = vibe,
                        artists = artists.takeIf { it.isNotBlank() },
                        era = era.takeIf { it != GrokConfig.ERA_OPTIONS.first() },
                        instrument = instrument.takeIf { it != GrokConfig.INSTRUMENT_OPTIONS.first() },
                        language = language.takeIf { it != GrokConfig.LANGUAGE_OPTIONS.first() }
                    )
                } else {
                    playlistName
                }
                createPlaylist(finalPlaylistName)
                delay(1000)
                fetchPlaylists()
                delay(1000)
                val playlistExists = playlists.any { it.name == finalPlaylistName }
                if (!playlistExists) {
                    Log.e(TAG, "Playlist $finalPlaylistName not found after creation!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Yo, playlist $finalPlaylistName didn’t stick!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.d(TAG, "Playlist $finalPlaylistName found in playlists!")
                }

                val numSongsInt = numSongs.toIntOrNull() ?: GrokConfig.DEFAULT_NUM_SONGS.toInt()
                val maxSongsPerArtistInt = if (numSongsInt < 10) 1 else maxSongsPerArtist.toIntOrNull() ?: GrokConfig.DEFAULT_MAX_SONGS_PER_ARTIST.toInt()
                val songList = xAiApi.generateSongList(
                    vibe = vibe,
                    numSongs = numSongsInt,
                    artists = artists.takeIf { it.isNotBlank() },
                    era = era.takeIf { it != GrokConfig.ERA_OPTIONS.first() },
                    maxSongsPerArtist = maxSongsPerArtistInt,
                    instrument = instrument.takeIf { it != GrokConfig.INSTRUMENT_OPTIONS.first() },
                    language = language.takeIf { it != GrokConfig.LANGUAGE_OPTIONS.first() }
                ) ?: throw Exception("No songs from xAI API")

                val tracks = songList.split("\n").mapNotNull { line ->
                    val parts = line.split(" - ", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }.take(numSongsInt)
                Log.d(TAG, "Song list: ${tracks.size} tracks: $tracks")

                var addedTracks = 0
                val addedUris = mutableSetOf<String>()
                val artistCounts = mutableMapOf<String, Int>()
                val trackKeys = mutableSetOf<String>()
                for ((artist, title) in tracks) {
                    if (addedTracks >= numSongsInt) break
                    val query = "$artist $title".replace(Regex("^\\d+\\.\\s*"), "")
                    aiSuggestions = emptyList()
                    search(query)
                    Log.d(TAG, "Searchin’ for: $query")
                    val results = waitForSearchResults()
                    Log.d(TAG, "Got ${results.size} results for $query: ${results.map { it.title }}")
                    for (track in results.filter { it.type == "song" }) {
                        val trackKey = "${track.artist}:${track.title}".lowercase()
                        if (!trackKeys.contains(trackKey)) {
                            val currentCount = artistCounts.getOrDefault(track.artist, 0)
                            if (currentCount < maxSongsPerArtistInt) {
                                if (addedUris.add(track.uri)) {
                                    addToPlaylist(finalPlaylistName, track.uri, track.type)
                                    addedTracks++
                                    artistCounts[track.artist] = currentCount + 1
                                    trackKeys.add(trackKey)
                                    Log.d(TAG, "Added track: ${track.title} by ${track.artist}, URI: ${track.uri}, Type: ${track.type}, Total added: $addedTracks")
                                    break
                                } else {
                                    Log.w(TAG, "Skipped duplicate URI: ${track.uri}")
                                }
                            } else {
                                Log.w(TAG, "Skipped track $title by $artist, max songs per artist ($maxSongsPerArtistInt) reached")
                            }
                        } else {
                            Log.w(TAG, "Skipped duplicate track: $title by $artist")
                        }
                    }
                    if (results.isEmpty()) {
                        Log.w(TAG, "No results for $query, movin’ on")
                    }
                }
                aiSuggestions = emptyList()
                Log.d(TAG, "Final track count: $addedTracks/$numSongsInt")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Created '$finalPlaylistName' with $addedTracks/$numSongsInt tracks!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI playlist generation failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Shit broke, fam! ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isLoading = false
            }
        }
    }
}
