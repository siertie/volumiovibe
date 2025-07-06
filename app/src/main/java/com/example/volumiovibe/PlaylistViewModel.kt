package com.example.volumiovibe

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody


data class ResolvedTrack(
    val artist: String,
    val title: String,
    val volumioUri: String? = null,
    val tidalId: Int? = null
)

class PlaylistViewModel(application: Application) : ViewModel() {
    private val TAG = "VIBE_DEBUG"
    private val webSocketManager = WebSocketManager
    //    private val xAiApi = XAiApi(BuildConfig.XAI_API_KEY)
    init {
        Log.d("OpenAiApi", "API KEY BEING USED: '${BuildConfig.OPENAI_API_KEY}'")
//        testTidalPlaylistRequest()
    }
    var isGeneratingPlaylist by mutableStateOf(false)
    var generationProgress by mutableStateOf(0f) // 0.0 .. 1.0 (for progress bar)
    var generationStatus by mutableStateOf<String>("Generating playlist...")
    var playlistGenerationFinished by mutableStateOf(false)

    private val xAiApi = OpenAiApi(BuildConfig.OPENAI_API_KEY)
    private val prefs = application.getSharedPreferences("VolumioVibePrefs", Context.MODE_PRIVATE)
    private val application = application

    var playlists by mutableStateOf<List<Playlist>>(emptyList())
    var isLoading by mutableStateOf(false)
    private val pendingTracks = mutableMapOf<String, MutableList<Track>>()
    private val browseRequests = ConcurrentHashMap<String, String>()
    private var lastPlaylistStateUpdate: Long = 0
    private val loadedPlaylists = mutableSetOf<String>()
    var playlistDialogVisible by mutableStateOf(false)
        private set

    private val _selectedVibe = mutableStateOf(prefs.getString("selectedVibe", GrokConfig.VIBE_OPTIONS.first()) ?: GrokConfig.VIBE_OPTIONS.first())
    var selectedVibe: String
        get() = _selectedVibe.value
        set(value) {
            _selectedVibe.value = value
            PlaylistStateHolder.selectedVibe = value
            prefs.edit { putString("selectedVibe", value) }
        }

    private val _vibeInput = mutableStateOf(prefs.getString("vibeInput", "") ?: "")
    var vibeInput: String
        get() = _vibeInput.value
        set(value) {
            _vibeInput.value = value
            PlaylistStateHolder.vibeInput = value
            prefs.edit { putString("vibeInput", value) }
        }

    private val _era = mutableStateOf(prefs.getString("era", GrokConfig.ERA_OPTIONS.first()) ?: GrokConfig.ERA_OPTIONS.first())
    var era: String
        get() = _era.value
        set(value) {
            _era.value = value
            PlaylistStateHolder.era = value
            prefs.edit { putString("era", value) }
        }

    private val _language = mutableStateOf(prefs.getString("language", GrokConfig.LANGUAGE_OPTIONS.first()) ?: GrokConfig.LANGUAGE_OPTIONS.first())
    var language: String
        get() = _language.value
        set(value) {
            _language.value = value
            PlaylistStateHolder.language = value
            prefs.edit { putString("language", value) }
        }

    private val _instrument = mutableStateOf(prefs.getString("instrument", GrokConfig.INSTRUMENT_OPTIONS.first()) ?: GrokConfig.INSTRUMENT_OPTIONS.first())
    var instrument: String
        get() = _instrument.value
        set(value) {
            _instrument.value = value
            PlaylistStateHolder.instrument = value
            prefs.edit { putString("instrument", value) }
        }

    private val _playlistName = mutableStateOf(prefs.getString("playlistName", "") ?: "")
    var playlistName: String
        get() = _playlistName.value
        set(value) {
            _playlistName.value = value
            PlaylistStateHolder.playlistName = value
            prefs.edit { putString("playlistName", value) }
        }

    private val _artists = mutableStateOf(prefs.getString("artists", "") ?: "")
    var artists: String
        get() = _artists.value
        set(value) {
            _artists.value = value
            PlaylistStateHolder.artists = value
            prefs.edit { putString("artists", value) }
        }

    private val _numSongs = mutableStateOf(prefs.getString("numSongs", GrokConfig.DEFAULT_NUM_SONGS) ?: GrokConfig.DEFAULT_NUM_SONGS)
    var numSongs: String
        get() = _numSongs.value
        set(value) {
            _numSongs.value = value
            PlaylistStateHolder.numSongs = value
            prefs.edit { putString("numSongs", value) }
        }

    private val _maxSongsPerArtist = mutableStateOf(prefs.getString("maxSongsPerArtist", GrokConfig.DEFAULT_MAX_SONGS_PER_ARTIST) ?: GrokConfig.DEFAULT_MAX_SONGS_PER_ARTIST)
    var maxSongsPerArtist: String
        get() = _maxSongsPerArtist.value
        set(value) {
            _maxSongsPerArtist.value = value
            PlaylistStateHolder.maxSongsPerArtist = value
            prefs.edit { putString("maxSongsPerArtist", value) }
        }

    init {
        connectWebSocket()
    }

    suspend fun searchTidalTrack(query: String): ResolvedTrack? {
        return try {
            val client = okhttp3.OkHttpClient()
            val requestBody = org.json.JSONObject().apply { put("query", query) }
                .toString().toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("http://192.168.0.250:5050/search") // 🔁 Replace IP!
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val tracks = json.optJSONArray("tracks") ?: return null
            if (tracks.length() > 0) {
                val first = tracks.getJSONObject(0)
                ResolvedTrack(
                    artist = first.getString("artist"),
                    title = first.getString("name"),
                    tidalId = first.getInt("tidal_id")
                )
            } else null
        } catch (e: Exception) {
            Log.e("VIBE_DEBUG", "TIDAL search failed for $query: ${e.message}")
            null
        }
    }

    fun cancelPlaylistGeneration() {
        playlistDialogVisible = false
        isGeneratingPlaylist = false
        playlistGenerationFinished = false
        generationStatus = "Cancelled."
        generationProgress = 0f
    }

    fun showPlaylistDialog() {
        playlistDialogVisible = true
        isGeneratingPlaylist = true
        playlistGenerationFinished = false
    }

    fun finishPlaylistDialog() {
        playlistGenerationFinished = true
    }

    fun dismissPlaylistDialog() {
        playlistDialogVisible = false
        isGeneratingPlaylist = false
        playlistGenerationFinished = false
    }

    private fun connectWebSocket() {
        viewModelScope.launch {
            Log.d(TAG, "Startin’ WebSocket init")
            webSocketManager.initialize()
            webSocketManager.debugAllEvents()
            Log.d(TAG, "WebSocket connected: ${webSocketManager.isConnected()}")
            if (webSocketManager.isConnected()) {
                Log.d(TAG, "WebSocket good, fetchin’ playlists")
                fetchPlaylists()
            } else {
                Log.w(TAG, "WebSocket not connected, tryin’ to reconnect")
                webSocketManager.reconnect()
                delay(1000)
                if (webSocketManager.isConnected()) {
                    Log.d(TAG, "Reconnect worked, fetchin’ playlists")
                    fetchPlaylists()
                } else {
                    Log.e(TAG, "WebSocket still down, fam")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(application, "Server’s ghostin’, fam!", Toast.LENGTH_LONG).show()
                    }
                }
            }
            webSocketManager.on("pushListPlaylist") { args: Array<out Any> ->
                Log.d(TAG, "Got pushListPlaylist, args count: ${args.size}, raw: ${args.getOrNull(0)}")
                try {
                    // Parse WebSocket data into playlist names
                    val data: JSONArray = when (val arg = args.getOrNull(0)) {
                        is String -> {
                            Log.d(TAG, "Arg is String: $arg")
                            if (arg == "undefined" || arg.isBlank()) JSONArray() else JSONArray(arg)
                        }
                        is JSONArray -> {
                            Log.d(TAG, "Arg is JSONArray: $arg")
                            arg
                        }
                        is JSONObject -> {
                            Log.d(TAG, "Arg is JSONObject: $arg")
                            arg.optJSONArray("playlists") ?: JSONArray()
                        }
                        else -> {
                            Log.w(TAG, "Weird arg type: ${arg?.javaClass?.simpleName}")
                            JSONArray()
                        }
                    }
                    Log.d(TAG, "Parsed JSONArray: $data, length: ${data.length()}")
                    val playlistNames = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val name = data.optString(i).trim()
                        Log.d(TAG, "Playlist item $i: '$name'")
                        if (name.isNotBlank() && name != "undefined") {
                            playlistNames.add(name)
                        }
                    }
                    playlistNames.reverse()
                    Log.d(TAG, "Got ${playlistNames.size} playlist names: ${playlistNames.joinToString()}")

                    // Batch update: Clean up old data and set new playlists once
                    pendingTracks.keys.retainAll { it in playlistNames }
                    loadedPlaylists.retainAll { it in playlistNames }
                    val oldSize = playlists.size
                    val newPlaylists = playlistNames.map { Playlist(it, pendingTracks[it] ?: emptyList()) }
                    playlists = newPlaylists
                    Log.d(TAG, "Updated playlists, old size: $oldSize, new size: ${playlists.size}, names: ${playlists.map { it.name }}")
                    playlists = playlists.toList() // Force UI refresh

                    // After updating playlists with names, fetch track counts in parallel (background)
                    viewModelScope.launch {
                        playlistNames.forEach { playlistName ->
                            launch {
                                if (playlistName !in loadedPlaylists) {
                                    val tracks = fetchTracksForPlaylist(playlistName)
                                    playlists = playlists.map { pl ->
                                        if (pl.name == playlistName) Playlist(pl.name, tracks) else pl
                                    }
                                    pendingTracks[playlistName] = tracks.toMutableList()
                                    loadedPlaylists.add(playlistName)
                                    // Force UI update
                                    playlists = playlists.toList()
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "pushListPlaylist parse crashed: ${e.message}")
                }
            }
            webSocketManager.on("pushCreatePlaylist") { args: Array<out Any> ->
                Log.d(TAG, "Got pushCreatePlaylist: ${args[0]}")
                fetchPlaylists()
            }
            webSocketManager.on("pushAddToPlaylist") { args: Array<out Any> ->
                Log.d(TAG, "Got pushAddToPlaylist: ${args.getOrNull(0)}")
                try {
                    val response = args.getOrNull(0)
                    if (response is JSONObject) {
                        val playlistName = response.optString("name", "").trim()
                        if (playlistName.isNotBlank()) {
                            Log.d(TAG, "New track added to $playlistName, refreshing tracks")
                            viewModelScope.launch {
                                val tracks = fetchTracksForPlaylist(playlistName)
                                playlists = playlists.map { playlist ->
                                    if (playlist.name == playlistName) Playlist(playlist.name, tracks) else playlist
                                }
                                pendingTracks[playlistName] = tracks.toMutableList()
                                loadedPlaylists.add(playlistName)
                                Log.d("EXCLUDED_SONGS_DEBUG", "Tracks loaded for playlist after add: $playlistName")
                            }
                        }
                    } else {
                        Log.w(TAG, "pushAddToPlaylist: Null or invalid payload, skipping fetch")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "pushAddToPlaylist parse failed: ${e.message}")
                }
            }
            webSocketManager.on("pushRemoveFromPlaylist") { args: Array<out Any> ->
                Log.d(TAG, "Got pushRemoveFromPlaylist: ${args[0]}")
                try {
                    val response = args[0] as JSONObject
                    val playlistName = response.optString("name", "").trim()
                    val uri = response.optString("uri", "")
                    if (playlistName.isNotBlank() && uri.isNotBlank()) {
                        Log.d(TAG, "Track $uri removed from $playlistName, updatin’ tracks")
                        viewModelScope.launch {
                            val tracks = fetchTracksForPlaylist(playlistName)
                            playlists = playlists.map { playlist ->
                                if (playlist.name == playlistName) Playlist(playlist.name, tracks) else playlist
                            }
                            pendingTracks[playlistName] = tracks.toMutableList()
                            loadedPlaylists.add(playlistName)
                            Log.d("EXCLUDED_SONGS_DEBUG", "Tracks loaded for playlist after remove: $playlistName")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "pushRemoveFromPlaylist failed: ${e.message}")
                }
            }
            webSocketManager.on("pushBrowseLibrary") { args: Array<out Any> ->
                Log.d(TAG, "Received pushBrowseLibrary: ${args[0]} at ${System.currentTimeMillis()}")
                Log.d("EXCLUDED_SONGS_DEBUG", "Processing browseLibrary response: ${args[0]}")
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
                            Log.w(TAG, "Unexpected pushBrowseLibrary type: ${arg?.javaClass?.simpleName}")
                            return@on
                        }
                    }
                    val navigation = data.optJSONObject("navigation") ?: run {
                        Log.w(TAG, "No navigation object in response")
                        return@on
                    }
                    val lists = navigation.optJSONArray("lists") ?: run {
                        Log.w(TAG, "No lists array in navigation")
                        return@on
                    }
                    val results = mutableListOf<Track>()
                    val isSearchResult = navigation.optBoolean("isSearchResult", false)
                    var playlistName: String? = null

                    for (listIdx in 0 until lists.length()) {
                        val list = lists.getJSONObject(listIdx)
                        val items = list.optJSONArray("items") ?: continue
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val itemType = item.optString("type", "")
                            val title = item.optString("title", "Unknown Title")
                            val artist = item.optString("artist", "Unknown Artist")
                            val uri = item.optString("uri", "")
                            val service = item.optString("service", "mpd")
                            val albumArt = item.optString("albumart")
                            if ((itemType == "song" || itemType == "album") && uri.isNotBlank()) {
                                results.add(Track(title, artist, uri, service, albumArt, itemType))
                            }
                        }
                    }

                    if (isSearchResult) {
                        synchronized(browseRequests) {
                            browseRequests["search:results"] = results.map {
                                "${it.title},${it.artist},${it.uri},${it.service},${it.albumArt ?: ""},${it.type}"
                            }.joinToString("|")
                            Log.d(TAG, "Set search:results with ${results.size} tracks")
                        }
                    } else {
                        val info = navigation.optJSONObject("info")
                        val type = info?.optString("type")
                        val title = info?.optString("title")?.trim()
                        if (type == "play-playlist" && title != null && title.isNotBlank()) {
                            playlistName = title
                            Log.d(TAG, "Extracted playlist name from info: $playlistName")
                        } else if (lists.length() > 0) {
                            val list = lists.optJSONObject(0)
                            val listTitle = list?.optString("title")?.trim()
                            if (listTitle != null && listTitle.isNotBlank()) {
                                playlistName = listTitle
                                Log.d(TAG, "Fallback: Using list title '$listTitle' as playlist name")
                            }
                        }

                        if (playlistName != null) {
                            synchronized(browseRequests) {
                                Log.d(TAG, "Checkin’ browseRequests for $playlistName, current: ${browseRequests.entries.joinToString { "${it.key}=${it.value}" }}")
                                val requestId = browseRequests.entries.find { it.value == playlistName }?.key
                                if (requestId != null) {
                                    playlists = playlists.map { playlist ->
                                        if (playlist.name == playlistName) Playlist(playlist.name, results) else playlist
                                    }
                                    pendingTracks[playlistName] = results.toMutableList()
                                    browseRequests["$requestId:results"] = results.map {
                                        "${it.title},${it.artist},${it.uri},${it.service},${it.albumArt ?: ""},${it.type}"
                                    }.joinToString("|")
                                    Log.d(TAG, "Updated tracks for $playlistName: ${results.size} tracks: ${results.map { "${it.artist} - ${it.title}" }}")
                                    browseRequests.remove(requestId)
                                    loadedPlaylists.add(playlistName)
                                    Log.d("EXCLUDED_SONGS_DEBUG", "Tracks loaded for playlist: $playlistName")
                                    playlists = playlists.toList() // Force UI refresh
                                } else {
                                    Log.w(TAG, "No request found for $playlistName, tryin’ fallback")
                                    // Fallback: Update playlist if name matches any playlist
                                    if (playlists.any { it.name == playlistName }) {
                                        playlists = playlists.map { playlist ->
                                            if (playlist.name == playlistName) Playlist(playlist.name, results) else playlist
                                        }
                                        pendingTracks[playlistName] = results.toMutableList()
                                        loadedPlaylists.add(playlistName)
                                        Log.d(TAG, "Fallback: Updated tracks for $playlistName: ${results.size} tracks: ${results.map { "${it.artist} - ${it.title}" }}")
                                        playlists = playlists.toList() // Force UI refresh
                                    } else {
                                        Log.w(TAG, "Fallback failed: $playlistName not in playlists")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse pushBrowseLibrary: ${e.message}")
                }
            }
        }
    }

    fun fetchPlaylists() {
        isLoading = true
        Log.d(TAG, "Emittin’ listPlaylist to server")
        webSocketManager.emit("listPlaylist", JSONObject())
        viewModelScope.launch {
            isLoading = false
            Log.d(TAG, "Playlist fetch done, got ${playlists.size} playlists: ${playlists.map { it.name }}")
            delay(1000)
            if (playlists.isEmpty()) {
                Log.w(TAG, "No playlists loaded, fam")
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "No playlists found, dawg!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun fetchTracksForPlaylist(playlistName: String): List<Track> {
        val trimmedPlaylistName = playlistName.trim()
        Log.d(TAG, "Fetchin’ tracks for: $trimmedPlaylistName")
        val requestId = UUID.randomUUID().toString()
        synchronized(browseRequests) {
            browseRequests[requestId] = trimmedPlaylistName
            Log.d(TAG, "Added browse request: $requestId -> $trimmedPlaylistName, current requests: ${browseRequests.entries.joinToString { "${it.key}=${it.value}" }}")
        }
        var tracks = listOf<Track>()
        var attempt = 0
        val maxRetries = 3
        val baseDelay = 500L
        val fetchStart = System.currentTimeMillis()
        while (attempt < maxRetries) {
            attempt++
            Log.d(TAG, "Attempt $attempt/$maxRetries for $trimmedPlaylistName")
            // Use the correct playlist URI
            webSocketManager.emit("browseLibrary", JSONObject().put("uri", "playlists/$trimmedPlaylistName"))
            Log.d(TAG, "Emitted browseLibrary with uri: playlists/$trimmedPlaylistName")
            val timeout = 10000L
            val startTime = System.currentTimeMillis()
            var shouldContinue = true
            while (System.currentTimeMillis() - startTime < timeout && shouldContinue) {
                synchronized(browseRequests) {
                    val resultKey = "$requestId:results"
                    if (browseRequests.containsKey(resultKey)) {
                        tracks = browseRequests[resultKey]!!.split("|").mapNotNull {
                            val parts = it.split(",")
                            if (parts.size >= 6) Track(parts[0], parts[1], parts[2], parts[3], parts[4].takeIf { it.isNotBlank() }, parts[5]) else null
                        }.distinctBy { it.uri }
                        browseRequests.remove(resultKey)
                        shouldContinue = false
                        Log.d(TAG, "Got ${tracks.size} tracks for $trimmedPlaylistName: ${tracks.map { "${it.artist} - ${it.title}" }}")
                    }
                }
                delay(50)
            }
            synchronized(browseRequests) {
                browseRequests.entries.removeIf { it.value == trimmedPlaylistName }
                Log.d(TAG, "Cleaned browseRequests, remaining: ${browseRequests.entries.joinToString { "${it.key}=${it.value}" }}")
            }
            if (tracks.isNotEmpty() || !shouldContinue) break
            Log.w(TAG, "Timeout on attempt $attempt/$maxRetries for $trimmedPlaylistName")
            if (attempt < maxRetries) delay(baseDelay * attempt)
        }
        if (tracks.isEmpty()) {
            Log.e(TAG, "No tracks fetched for $trimmedPlaylistName after $maxRetries attempts")
            withContext(Dispatchers.Main) {
                Toast.makeText(application, "Couldn’t load tracks for $trimmedPlaylistName, fam!", Toast.LENGTH_LONG).show()
            }
        }
        return tracks
    }

    suspend fun fetchAllPlaylistTracks() {
        // Only fetch for playlists not already loaded
        val playlistsToFetch: List<Playlist> = playlists.filter { it.name !in loadedPlaylists }
        Log.d(TAG, "Fetchin’ tracks for ${playlistsToFetch.size} playlists in parallel (BATCH UPDATE MODE)")

        // Temporary map to batch results
        val tracksByPlaylist = mutableMapOf<String, List<Track>>()

        coroutineScope {
            playlistsToFetch.map { playlist ->
                async(Dispatchers.IO) {
                    try {
                        val tracks = fetchTracksForPlaylist(playlist.name)
                        tracksByPlaylist[playlist.name] = tracks
                        pendingTracks[playlist.name] = tracks.toMutableList()
                        loadedPlaylists.add(playlist.name)
                        Log.d(TAG, "Loaded ${tracks.size} tracks for ${playlist.name} (batch)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load tracks for ${playlist.name}: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(application, "Failed to load tracks for ${playlist.name}, fam!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.awaitAll()
        }

        // Batch update all playlists in one go
        playlists = playlists.map { playlist ->
            if (tracksByPlaylist.containsKey(playlist.name))
                Playlist(playlist.name, tracksByPlaylist[playlist.name] ?: emptyList())
            else
                playlist
        }.toList()
        Log.d(TAG, "Batch playlists update complete: ${playlists.map { it.name to it.tracks.size }}")
    }


    fun browsePlaylistTracks(playlistName: String) {
        val trimmedPlaylistName = playlistName.trim()
        webSocketManager.emit("browseLibrary", JSONObject().put("uri", "playlists/$trimmedPlaylistName"))
        Log.d(TAG, "Emitted browseLibrary for playlist: $trimmedPlaylistName")
        Log.d("EXCLUDED_SONGS_DEBUG", "Requested tracks for playlist: $trimmedPlaylistName")
    }

    fun createPlaylist(name: String) {
        val trimmedName = name.trim()
        webSocketManager.emit("createPlaylist", JSONObject().put("name", trimmedName))
        Log.d(TAG, "Emitted createPlaylist: $trimmedName")
    }

    fun addToPlaylist(
        playlistName: String,
        resolved: ResolvedTrack,
        trackType: String
    ) {
        val trimmed = playlistName.trim()

        // Pull the real URI out of your ResolvedTrack
        val uri = resolved.volumioUri ?: run {
            Log.w(TAG, "addToPlaylist: no URI for ${resolved.artist} – ${resolved.title}")
            return
        }

        // Figure out the service based on URI
        val service = if (uri.startsWith("tidal://")) "tidal" else "mpd"

        // Emit to Volumio
        val data = JSONObject().apply {
            put("name", trimmed)
            put("service", service)
            put("uri", uri)
        }
        webSocketManager.emit("addToPlaylist", data)
        Log.d(TAG, "Emitted addToPlaylist: $trimmed, $uri, service=$service")

        // Locally update UI state with the real metadata
        val track = Track(
            title    = resolved.title,
            artist   = resolved.artist,
            uri      = uri,
            service  = service,
            albumArt = null,
            type     = trackType
        )
        playlists = playlists.map { pl ->
            if (pl.name == trimmed) Playlist(pl.name, pl.tracks + track)
            else pl
        }
        pendingTracks[trimmed] = (pendingTracks[trimmed] ?: mutableListOf()).apply { add(track) }
        Log.d(TAG, "Locally added track to $trimmed: ${track.title} by ${track.artist}")
    }


    fun removeFromPlaylist(playlistName: String, trackUri: String) {
        val trimmed = playlistName.trim()
        val data = JSONObject().apply {
            put("name", trimmed)
            put("uri", trackUri)
        }
        webSocketManager.emit("removeFromPlaylist", data)
        Log.d(TAG, "Emitted removeFromPlaylist: $trimmed, $trackUri")
        playlists = playlists.map { playlist ->
            if (playlist.name == trimmed) Playlist(playlist.name, playlist.tracks.filter { it.uri != trackUri }) else playlist
        }
        pendingTracks[trimmed] = pendingTracks[trimmed]?.filter { it.uri != trackUri }?.toMutableList() ?: mutableListOf()
        Log.d(TAG, "Updated pendingTracks for $trimmed: ${pendingTracks[trimmed]?.size ?: 0} tracks")
    }

    fun playPlaylist(playlistName: String) {
        val trimmed = playlistName.trim()
        webSocketManager.emit("playPlaylist", JSONObject().put("name", trimmed))
        Log.d(TAG, "Emitted playPlaylist: $trimmed")
    }

    fun deletePlaylist(playlistName: String) {
        val trimmed = playlistName.trim()
        webSocketManager.emit("deletePlaylist", JSONObject().put("name", trimmed))
        Log.d(TAG, "Emitted deletePlaylist: $trimmed")
        playlists = playlists.filter { it.name != trimmed }
        pendingTracks.remove(trimmed)
        loadedPlaylists.remove(trimmed)
    }

    fun search(query: String) {
        webSocketManager.emit("search", JSONObject().put("value", query))
        Log.d(TAG, "Emitted search: $query")
    }

    suspend fun waitForSearchResults(timeoutMs: Long = 10000): List<Track> {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            synchronized(browseRequests) {
                if (browseRequests.containsKey("search:results")) {
                    val results = browseRequests["search:results"]!!.split("|").mapNotNull {
                        val parts = it.split(",")
                        if (parts.size >= 6) Track(parts[0], parts[1], parts[2], parts[3], parts[4].takeIf { it.isNotBlank() }, parts[5]) else null
                    }
                    browseRequests.remove("search:results")
                    return results
                }
            }
            delay(100)
        }
        return emptyList()
    }

    private fun isTrackMatch(requestedTitle: String, requestedArtist: String, trackTitle: String, trackArtist: String): Boolean {
        val cleanRequestedTitle = requestedTitle.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex(" +"), " ")
            .trim()
        val cleanTrackTitle = trackTitle.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex(" +"), " ")
            .trim()
        val cleanRequestedArtist = requestedArtist.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex(" +"), " ")
            .trim()
        val cleanTrackArtist = trackArtist.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex(" +"), " ")
            .trim()

        val titleMatch = cleanTrackTitle.contains(cleanRequestedTitle) ||
                cleanRequestedTitle.contains(cleanTrackTitle)
        val artistInTitle = cleanTrackTitle.contains(cleanRequestedArtist)
        val artistMatch = cleanTrackArtist.contains(cleanRequestedArtist) ||
                cleanRequestedArtist.contains(cleanTrackArtist)
        return titleMatch && (artistMatch || artistInTitle)
    }

    fun generateAiPlaylist(context: Context) {
        viewModelScope.launch {
            showPlaylistDialog()
            generationProgress = 0f
            generationStatus = "Starting playlist generation…"
            try {
                Log.d("VIBE_DEBUG", "🚀 Startin’ AI playlist generation")
                val vibe = if (selectedVibe != GrokConfig.VIBE_OPTIONS.first()) selectedVibe else vibeInput
                Log.d("VIBE_DEBUG", "🎶 Vibe selected: $vibe")
                if (vibe.isNotBlank() && !PlaylistStateHolder.recentVibes.contains(vibe)) {
                    PlaylistStateHolder.recentVibes.add(0, vibe)
                    if (PlaylistStateHolder.recentVibes.size > 5) PlaylistStateHolder.recentVibes.removeLast()
                    Log.d("VIBE_DEBUG", "📜 Added vibe to recent: ${PlaylistStateHolder.recentVibes}")
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
                    playlistName.trim()
                }
                Log.d("VIBE_DEBUG", "📋 Playlist name set: $finalPlaylistName")
                createPlaylist(finalPlaylistName)
                delay(1000)
                fetchPlaylists()
                delay(1000)
                val playlistExists = playlists.any { it.name == finalPlaylistName }
                if (!playlistExists) {
                    Log.e("VIBE_DEBUG", "❌ Playlist $finalPlaylistName not found after creation!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Yo, playlist $finalPlaylistName didn’t stick!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.d("VIBE_DEBUG", "✅ Playlist $finalPlaylistName found in playlists!")
                }

                if (!webSocketManager.isConnected()) {
                    Log.w("VIBE_DEBUG", "🔌 WebSocket disconnected, tryin’ to reconnect")
                    webSocketManager.reconnect()
                    delay(2000)
                    if (!webSocketManager.isConnected()) {
                        Log.e("VIBE_DEBUG", "🔌 WebSocket still disconnected, proceedin’ with empty excluded songs")
                    }
                }

                fetchAllPlaylistTracks()

                Log.d("VIBE_DEBUG", "📜 Playlists state before excludedSongs: ${playlists.map { it.name to it.tracks.size }}")
                Log.d("VIBE_DEBUG", "📜 Pending tracks state: ${pendingTracks.mapValues { it.value.size }}")

                val numSongsInt = numSongs.toIntOrNull() ?: GrokConfig.DEFAULT_NUM_SONGS.toInt()
                val maxSongsPerArtistInt = if (numSongsInt < 10) 1 else maxSongsPerArtist.toIntOrNull() ?: GrokConfig.DEFAULT_MAX_SONGS_PER_ARTIST.toInt()
                val excludedSongs = playlists.flatMap { it.tracks }
                    .distinctBy { it.uri }
                    .take(GrokConfig.MAX_EXCLUDED_URIS)
                Log.d("VIBE_DEBUG", "🚫 Excluded songs: ${excludedSongs.map { "${it.title} by ${it.artist}" }}")

                generationStatus = "Asking AI for song list…"
                val songList = xAiApi.generateSongList(
                    vibe = vibe,
                    numSongs = numSongsInt,
                    artists = artists.takeIf { it.isNotBlank() },
                    era = era.takeIf { it != GrokConfig.ERA_OPTIONS.first() },
                    maxSongsPerArtist = maxSongsPerArtistInt,
                    instrument = instrument.takeIf { it != GrokConfig.INSTRUMENT_OPTIONS.first() },
                    language = language.takeIf { it != GrokConfig.LANGUAGE_OPTIONS.first() },
                    excludedSongs = excludedSongs
                ) ?: throw Exception("No songs from xAI API")
                Log.d("VIBE_DEBUG", "🎵 Song list from Grok: $songList")

                val tracks = songList.split("\n").mapNotNull { line ->
                    val parts = line.split(" - ", limit = 2)
                    if (parts.size == 2) {
                        val artist = parts[0].trim().replace(Regex("^\\d+\\.\\s*"), "")
                        val title = parts[1].trim()
                        artist to title
                    } else null
                }.take(numSongsInt)
                Log.d("VIBE_DEBUG", "📋 Parsed tracks: ${tracks.size} tracks: $tracks")

                var addedTracks = 0
                val resolvedTracks = mutableListOf<ResolvedTrack>()
                Log.d("VIBE_TIDAL", "🔍 Startin’ track resolution for ${tracks.size} tracks")
                val totalTracks = tracks.size

                for ((index, pair) in tracks.withIndex()) {
                    val (artist, title) = pair
                    generationStatus = "Searching: $artist – $title"
                    // 🔥 Progress bar update:
                    generationProgress = if (totalTracks == 0) 1f else ((index + 1).toFloat() / totalTracks)

                    val query = "$artist $title"
                    Log.d("VIBE_TIDAL", "🔎 Searchin’ Volumio for: $query")
                    search(query)
                    val volumioResults = waitForSearchResults()
                    Log.d("VIBE_TIDAL", "📊 Volumio results: ${volumioResults.size} tracks")
                    val match = volumioResults
                        .filter { it.type == "song" }
                        .firstOrNull { isTrackMatch(title, artist, it.title, it.artist) }
                    if (match != null) {
                        Log.d("VIBE_TIDAL", "✅ Volumio match found: ${match.artist} - ${match.title} (URI: ${match.uri})")
                        resolvedTracks.add(ResolvedTrack(artist, title, volumioUri = match.uri))
                        continue
                    }

                    Log.d("VIBE_TIDAL", "🔎 No Volumio match, searchin’ TIDAL for: $artist - $title")
                    val tidalResult = searchTidalTrack("$artist - $title")
                    if (tidalResult != null) {
                        Log.d("VIBE_TIDAL", "✅ TIDAL match found: ${tidalResult.artist} - ${tidalResult.title} (TIDAL ID: ${tidalResult.tidalId})")
                        resolvedTracks.add(tidalResult.copy(artist = artist, title = title))
                    } else {
                        Log.w("VIBE_TIDAL", "❌ No match found for: $artist - $title")
                    }
                }
                // Final progress at 100% before add
                generationProgress = 1f

                Log.d("VIBE_TIDAL", "📋 Resolved tracks: ${resolvedTracks.size} (Volumio: ${resolvedTracks.count { it.volumioUri != null }}, TIDAL: ${resolvedTracks.count { it.tidalId != null }})")

                val tidalTracksJson = JSONArray()

                for (track in resolvedTracks) {
                    var tidalId: Int? = null

                    if (track.volumioUri != null) {
                        addToPlaylist(finalPlaylistName, track, "song")
                        addedTracks++
                        delay(200)

                        if (track.volumioUri.startsWith("tidal://song/")) {
                            tidalId = track.volumioUri.substringAfter("tidal://song/").toIntOrNull()
                        }
                    }
                    // This block ADDS all TIDAL tracks as Volumio playlist entries if there was no local match:
                    else if (track.tidalId != null) {
                        val tidalTrack = track.copy(volumioUri = "tidal://song/${track.tidalId}")
                        addToPlaylist(finalPlaylistName, tidalTrack, "song")
                        addedTracks++
                        delay(200)
                        tidalId = track.tidalId
                    }

                    if (tidalId == null) {
                        tidalId = track.tidalId
                    }

                    if (tidalId != null) {
                        // TIDAL playlist JSON
                        tidalTracksJson.put(JSONObject().apply {
                            put("artist", track.artist)
                            put("title", track.title)
                            put("tidal_id", tidalId)
                        })
                        Log.d("VIBE_TIDAL", "🎯 Queued for TIDAL: ${track.artist} - ${track.title} (ID: $tidalId)")
                    } else {
                        Log.w("VIBE_TIDAL", "⚠️ Skipped TIDAL add — no ID for: ${track.artist} - ${track.title}")
                    }
                }


                Log.d("VIBE_TIDAL", "📦 TIDAL tracks JSON: ${tidalTracksJson.length()} tracks prepared")

                if (tidalTracksJson.length() > 0) {
                    val requestBodyString = JSONObject().apply {
                        put("name", finalPlaylistName)
                        put("songs", tidalTracksJson)
                    }.toString()

                    Log.d("VIBE_TIDAL", "📤 Sending TIDAL playlist request: $requestBodyString")

                    try {
                        val responseBody = sendTidalPlaylistRequest(requestBodyString)
                        val json = JSONObject(responseBody ?: "{}")
                        val status = json.optString("status", "unknown")
                        val addedTracksTidal = json.optInt("added_tracks", -1)
                        Log.d("VIBE_TIDAL", "📊 TIDAL response parsed - status: $status, added_tracks: $addedTracksTidal")

                        if (status != "success" || addedTracksTidal <= 0) {
                            val errorMsg = json.optString("error", "No tracks added or unknown error")
                            Log.e("VIBE_TIDAL", "❌ TIDAL playlist add failed: $errorMsg")
                            throw Exception("TIDAL error: $errorMsg")
                        }

                        Log.d("VIBE_TIDAL", "✅ TIDAL playlist '$finalPlaylistName' created with $addedTracksTidal tracks")
                    } catch (e: Exception) {
                        Log.e("VIBE_TIDAL", "❌ Exception addin’ TIDAL playlist", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "TIDAL playlist failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Log.w("VIBE_TIDAL", "⚠️ No TIDAL tracks to send, skippin’ TIDAL playlist creation")
                }

                Log.d("VIBE_DEBUG", "🏁 Final track count: $addedTracks/$numSongsInt Volumio, ${tidalTracksJson.length()}/$numSongsInt TIDAL")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Created '$finalPlaylistName': $addedTracks Volumio, ${tidalTracksJson.length()} TIDAL", Toast.LENGTH_LONG).show()
                }
                val refreshedTracks = fetchTracksForPlaylist(finalPlaylistName)
                // Update UI state with the newly fetched list (with albumArt)
                playlists = playlists.map { pl ->
                    if (pl.name == finalPlaylistName) Playlist(pl.name, refreshedTracks)
                    else pl
                }
                pendingTracks[finalPlaylistName] = refreshedTracks.toMutableList()
                loadedPlaylists.add(finalPlaylistName)
            } catch (e: Exception) {
                Log.e("VIBE_DEBUG", "💥 AI playlist generation crashed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Shit broke, fam! ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                generationStatus = "Done!"
                generationProgress = 1f
                finishPlaylistDialog()
                Log.d("VIBE_DEBUG", "🛑 Playlist generation done, isGeneratingPlaylist set to false")
            }
        }
    }

    private suspend fun sendTidalPlaylistRequest(requestBodyString: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("http://192.168.0.250:5050/add_playlist")
                .post(requestBodyString.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                responseBody
            }
        } catch (e: Exception) {
            Log.e("VIBE_TIDAL", "❌ Network call fucked up: ${e.message}")
            throw e
        }
    }
//    fun testTidalPlaylistRequest() {
//        viewModelScope.launch {
//            val testJson = JSONObject().apply {
//                put("name", "TestPlaylist_Kotlin")
//                put("songs", JSONArray().apply {
//                    put(JSONObject().apply {
//                        put("artist", "Glass Animals")
//                        put("title", "Gooey")
//                        put("tidal_id", 30209245)
//                    })
//                    put(JSONObject().apply {
//                        put("artist", "The Knife")
//                        put("title", "Pass This On")
//                        put("tidal_id", 241824197)
//                    })
//                    put(JSONObject().apply {
//                        put("artist", "Machel Montano")
//                        put("title", "Fast Wine")
//                        put("tidal_id", 310247914)
//                    })
//                })
//            }
//
//            val jsonString = testJson.toString()
//            Log.d("VIBE_TIDAL", "🧪 Sending test TIDAL playlist JSON: $jsonString")
//
//            try {
//                withContext(Dispatchers.IO) {
//                    val client = okhttp3.OkHttpClient()
//                    val request = okhttp3.Request.Builder()
//                        .url("http://192.168.0.250:5050/add_playlist")
//                        .post(jsonString.toRequestBody("application/json".toMediaType()))
//                        .build()
//
//                    client.newCall(request).execute().use { response ->
//                        val responseBody = response.body?.string()
//                        Log.d("VIBE_TIDAL", "📝 Test response code: ${response.code}")
//                        Log.d("VIBE_TIDAL", "📝 Test response body: $responseBody")
//
//                        if (!response.isSuccessful) {
//                            Log.e("VIBE_TIDAL", "❌ Test request failed: HTTP ${response.code}")
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e("VIBE_TIDAL", "💥 Test request crashed", e)
//            }
//        }
//    }
}