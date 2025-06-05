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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlaylistViewModel(application: Application) : ViewModel() {
    private val TAG = "VolumioCache"
    private val webSocketManager = WebSocketManager
    private val xAiApi = XAiApi(BuildConfig.XAI_API_KEY)
    private val cacheDao = AppDatabase.getDatabase(application).cacheDao()

    var playlists by mutableStateOf<List<Playlist>>(emptyList())
    var isLoading by mutableStateOf(false)
    private val pendingTracks = mutableMapOf<String, MutableList<Track>>()
    private var cacheJob: Job? = null
    private val browseRequests = ConcurrentHashMap<String, String>()
    private var lastPlaylistStateUpdate: Long = 0
    private var lastValidationTime: Long = 0

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
        viewModelScope.launch { validateAndCachePlaylists() }
    }

    private fun connectWebSocket() {
        viewModelScope.launch {
            webSocketManager.initialize()
            webSocketManager.debugAllEvents()
            webSocketManager.on("pushListPlaylist") { args: Array<out Any> ->
                Log.d(TAG, "Got pushListPlaylist: ${args[0]}")
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastPlaylistStateUpdate < 2000) {
                        Log.d(TAG, "Debouncin’ pushListPlaylist, too soon")
                        return@on
                    }
                    lastPlaylistStateUpdate = now
                    val data: JSONArray = when (val arg = args[0]) {
                        is String -> if (arg == "undefined") JSONArray() else JSONArray(arg)
                        is JSONArray -> arg
                        else -> {
                            Log.w(TAG, "Weird pushListPlaylist type: ${arg?.javaClass?.simpleName}")
                            return@on
                        }
                    }
                    val playlistNames = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val name = data.optString(i).trim()
                        if (name != "undefined") {
                            playlistNames.add(name)
                        }
                    }
                    playlistNames.reverse()
                    Log.d(TAG, "Parsed playlists: ${playlistNames.joinToString()}")
                    playlists = playlistNames.map { Playlist(it, emptyList()) }
                    Log.d(TAG, "Updated playlists state: ${playlists.map { it.name }.joinToString()}")
                    viewModelScope.launch {
                        val existingPlaylists = withContext(Dispatchers.IO) { cacheDao.getPlaylists() }
                        val cachedPlaylists = playlistNames.mapIndexed { index, name ->
                            val existing = existingPlaylists.find { it.name == name }
                            PlaylistCache(
                                name = name,
                                lastUpdated = now - index,
                                lastFetched = existing?.lastFetched ?: now,
                                contentHash = existing?.contentHash,
                                isEmpty = existing?.isEmpty ?: false
                            )
                        }
                        withContext(Dispatchers.IO) {
                            cacheDao.insertPlaylists(cachedPlaylists)
                            fetchTracksUntilLimitChanged(playlistNames)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "pushListPlaylist parse failed: ${e.message}")
                }
            }
            webSocketManager.on("pushCreatePlaylist") { args: Array<out Any> ->
                Log.d(TAG, "Got pushCreatePlaylist: ${args[0]}")
                fetchPlaylists()
            }
            webSocketManager.on("pushAddToPlaylist") { args: Array<out Any> ->
                Log.d(TAG, "Got pushAddToPlaylist: ${args[0]}")
                try {
                    val response = args[0] as JSONObject
                    val playlistName = response.optString("name", "").trim()
                    if (playlistName.isNotBlank()) {
                        Log.d(TAG, "New track added to $playlistName, refreshing tracks")
                        viewModelScope.launch {
                            withContext(Dispatchers.IO) {
                                val tracks = fetchTracksForPlaylist(playlistName)
                                cacheDao.insertPlaylists(listOf(PlaylistCache(playlistName, System.currentTimeMillis(), System.currentTimeMillis(), computeTrackHash(tracks), tracks.isEmpty())))
                                cacheDao.insertOrIgnoreTracks(tracks)
                            }
                        }
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
                        Log.d(TAG, "Track $uri removed from $playlistName, updatin’ cache")
                        viewModelScope.launch {
                            withContext(Dispatchers.IO) {
                                val tracks = fetchTracksForPlaylist(playlistName)
                                cacheDao.insertPlaylists(listOf(PlaylistCache(playlistName, System.currentTimeMillis(), System.currentTimeMillis(), computeTrackHash(tracks), tracks.isEmpty())))
                                cacheDao.insertOrIgnoreTracks(tracks)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "pushRemoveFromPlaylist failed: ${e.message}")
                }
            }
            webSocketManager.on("pushBrowseLibrary") { args: Array<out Any> ->
                Log.d(TAG, "Received pushBrowseLibrary: ${args[0]} at ${System.currentTimeMillis()}")
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
                    val navigation = data.optJSONObject("navigation") ?: return@on
                    val lists = navigation.optJSONArray("lists") ?: return@on
                    val results = mutableListOf<Track>()
                    var playlistName: String? = null

                    // Extract playlist name from "info" section for playlist responses
                    val info = navigation.optJSONObject("info")
                    val type = info?.optString("type")
                    val title = info?.optString("title")?.trim()
                    if (type == "play-playlist" && title != null && title.isNotBlank()) {
                        playlistName = title
                    }

                    // Process track items
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
                            val albumArt = item.optString("albumart")
                            if ((type == "song" || type == "folder-with-favourites") && uri.isNotBlank()) {
                                results.add(Track(title, artist, uri, service, albumArt, type))
                            }
                        }
                    }

                    // Handle playlist response
                    if (playlistName != null) {
                        synchronized(browseRequests) {
                            val requestId = browseRequests.entries.find { it.value == playlistName }?.key
                            if (requestId != null) {
                                playlists = playlists.map { playlist ->
                                    if (playlist.name == playlistName) Playlist(playlist.name, results) else playlist
                                }
                                pendingTracks[playlistName] = results.toMutableList()
                                browseRequests["$requestId:results"] = results.map { "${it.title},${it.artist},${it.uri},${it.service},${it.albumArt ?: ""},${it.type}" }.joinToString("|")
                                Log.d(TAG, "Updated tracks for $playlistName: ${results.size} tracks")
                                browseRequests.remove(requestId)
                            } else {
                                Log.w(TAG, "No request found for $playlistName, ignoring response")
                            }
                        }
                    } else {
                        // Handle non-playlist (e.g., search) responses
                        synchronized(browseRequests) {
                            browseRequests["search:results"] = results.map { "${it.title},${it.artist},${it.uri},${it.service},${it.albumArt ?: ""},${it.type}" }.joinToString("|")
                        }
                        Log.d(TAG, "Search results: ${results.size} tracks")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse pushBrowseLibrary: ${e.message}")
                }
            }
        }
    }

    private fun computeTrackHash(tracks: List<TrackCache>): Int {
        val sortedUris = tracks.map { it.uri }.sorted().joinToString("")
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(sortedUris.toByteArray())
        return hashBytes.fold(0) { acc, byte -> (acc shl 8) + (byte.toInt() and 0xFF) }
    }

    fun fetchPlaylists() {
        Log.d(TAG, "Emittin’ listPlaylist")
        webSocketManager.emit("listPlaylist", JSONObject())
    }

    private suspend fun validateAndCachePlaylists() {
        isLoading = true
        try {
            val startTime = System.currentTimeMillis()
            val cachedPlaylists = withContext(Dispatchers.IO) { cacheDao.getPlaylists() }
            val allCachedTracks = withContext(Dispatchers.IO) { cacheDao.getRecentTracks() }
            Log.d(TAG, "Validatin’ cache: ${cachedPlaylists.size} playlists, ${allCachedTracks.size} tracks, took ${System.currentTimeMillis() - startTime}ms")
            val now = System.currentTimeMillis()
            val cacheTTL = 24 * 60 * 60 * 1000 // 24 hours
            var needsRefresh = false
            for (playlist in cachedPlaylists) {
                val trimmedPlaylistName = playlist.name.trim()
                val cacheStart = System.currentTimeMillis()
                val cachedTracks = withContext(Dispatchers.IO) { cacheDao.getTracksForPlaylist(trimmedPlaylistName) }
                Log.d(TAG, "Retrieved ${cachedTracks.size} cached tracks for playlist '$trimmedPlaylistName' in ${System.currentTimeMillis() - cacheStart}ms")
                val storedHash = withContext(Dispatchers.IO) { cacheDao.getPlaylistHash(trimmedPlaylistName) }
                val currentHash = computeTrackHash(cachedTracks)
                if (playlist.lastFetched == 0L || now - playlist.lastFetched > cacheTTL) {
                    Log.w(TAG, "Cache stale for $trimmedPlaylistName: tracks=${cachedTracks.size}, lastFetched=${playlist.lastFetched}, hash=$currentHash vs $storedHash")
                    needsRefresh = true
                    withContext(Dispatchers.IO) {
                        val tracks = fetchTracksForPlaylist(trimmedPlaylistName)
                        cacheDao.insertPlaylists(listOf(PlaylistCache(trimmedPlaylistName, now, now, computeTrackHash(tracks), tracks.isEmpty())))
                        try {
                            cacheDao.insertOrIgnoreTracks(tracks)
                            val insertedTracks = cacheDao.getTracksForPlaylist(trimmedPlaylistName)
                            Log.d(TAG, "After insertion, retrieved ${insertedTracks.size} tracks for '$trimmedPlaylistName'")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to insert tracks for '$trimmedPlaylistName': ${e.message}")
                        }
                        Log.d(TAG, "Updated cache for $trimmedPlaylistName: ${tracks.size} tracks")
                    }
                } else if (playlist.isEmpty || cachedTracks.isNotEmpty()) {
                    Log.d(TAG, "Usin’ cached tracks for $trimmedPlaylistName: ${if (playlist.isEmpty) "empty" else "${cachedTracks.size} tracks"}")
                } else {
                    Log.w(TAG, "No tracks in cache for $trimmedPlaylistName, fetching")
                    needsRefresh = true
                    withContext(Dispatchers.IO) {
                        val tracks = fetchTracksForPlaylist(trimmedPlaylistName)
                        cacheDao.insertPlaylists(listOf(PlaylistCache(trimmedPlaylistName, now, now, computeTrackHash(tracks), tracks.isEmpty())))
                        try {
                            cacheDao.insertOrIgnoreTracks(tracks)
                            val insertedTracks = cacheDao.getTracksForPlaylist(trimmedPlaylistName)
                            Log.d(TAG, "After insertion, retrieved ${insertedTracks.size} tracks for '$trimmedPlaylistName'")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to insert tracks for '$trimmedPlaylistName': ${e.message}")
                        }
                    }
                }
            }
            lastValidationTime = now
            fetchPlaylists()
            if (cachedPlaylists.isNotEmpty() && needsRefresh && now - lastPlaylistStateUpdate > 2000) {
                withContext(Dispatchers.IO) {
                    fetchTracksUntilLimitChanged(cachedPlaylists.map { it.name.trim() }.reversed())
                }
            }
            // Log all cached tracks for debugging
            val allTracksStart = System.currentTimeMillis()
            val allTracks = withContext(Dispatchers.IO) { cacheDao.getRecentTracks() }
            Log.d(TAG, "All cached tracks: ${allTracks.map { "${it.playlistName}: ${it.artist} - ${it.title}" }}, took ${System.currentTimeMillis() - allTracksStart}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Cache validation failed: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    private suspend fun fetchTracksUntilLimitChanged(playlistNames: List<String>) {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Checkin’ cache before fetchin’ tracks")
                val allTracks = mutableListOf<TrackCache>()
                val uniqueUris = hashSetOf<String>()
                val now = System.currentTimeMillis()
                val cacheTTL = 24 * 60 * 60 * 1000
                val allCachedTracksStart = System.currentTimeMillis()
                val allCachedTracks = withContext(Dispatchers.IO) { cacheDao.getRecentTracks() }
                Log.d(TAG, "Current track_cache: ${allCachedTracks.size} tracks: ${allCachedTracks.map { "${it.playlistName}: ${it.artist} - ${it.title}" }}, took ${System.currentTimeMillis() - allCachedTracksStart}ms")
                for (playlistName in playlistNames.map { it.trim() }) {
                    if (allTracks.size >= 200) {
                        Log.d(TAG, "Hit 200 tracks, stoppin’")
                        break
                    }
                    val cachedPlaylist = withContext(Dispatchers.IO) {
                        cacheDao.getPlaylists().find { it.name.trim() == playlistName }
                    }
                    val cacheStart = System.currentTimeMillis()
                    val cachedTracks = withContext(Dispatchers.IO) {
                        cacheDao.getTracksForPlaylist(playlistName)
                    }
                    Log.d(TAG, "Retrieved ${cachedTracks.size} cached tracks for playlist '$playlistName' in ${System.currentTimeMillis() - cacheStart}ms")
                    val currentHash = computeTrackHash(cachedTracks)
                    val storedHash = withContext(Dispatchers.IO) { cacheDao.getPlaylistHash(playlistName) }
                    val tracks = if (cachedPlaylist != null && (cachedPlaylist.isEmpty || cachedTracks.isNotEmpty()) && (cachedPlaylist.lastFetched == 0L || now - cachedPlaylist.lastFetched < cacheTTL)) {
                        Log.d(TAG, "Usin’ cached tracks for $playlistName: ${if (cachedPlaylist.isEmpty) "empty" else "${cachedTracks.size} tracks"}")
                        cachedTracks
                    } else {
                        Log.d(TAG, "Fetchin’ tracks for $playlistName: tracks=${cachedTracks.size}, lastFetched=${cachedPlaylist?.lastFetched}, hash=$currentHash vs $storedHash")
                        withContext(Dispatchers.IO) {
                            val tracks = fetchTracksForPlaylist(playlistName)
                            cacheDao.insertPlaylists(listOf(PlaylistCache(playlistName, now, now, computeTrackHash(tracks), tracks.isEmpty())))
                            try {
                                cacheDao.insertOrIgnoreTracks(tracks)
                                val insertedTracks = cacheDao.getTracksForPlaylist(playlistName)
                                Log.d(TAG, "After insertion, retrieved ${insertedTracks.size} tracks for '$playlistName'")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to insert tracks for '$playlistName': ${e.message}")
                            }
                            Log.d(TAG, "Updated cache for $playlistName: ${tracks.size} tracks")
                            tracks
                        }
                    }
                    allTracks.addAll(tracks.filter { newTrack ->
                        uniqueUris.add(newTrack.uri)
                    })
                    Log.d(TAG, "After $playlistName, got ${allTracks.size}/200 tracks, unique URIs: ${uniqueUris.size}")
                }
                withContext(Dispatchers.IO) {
                    try {
                        cacheDao.insertOrIgnoreTracks(allTracks.take(200))
                        Log.d(TAG, "Successfully cached ${allTracks.size}/200 tracks")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cache tracks: ${e.message}")
                    }
                }
                Log.d(TAG, "Cached ${allTracks.size}/200 tracks: ${allTracks.map { "${it.artist} - ${it.title}" }}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Cache job cancelled normally")
            }
        }
    }

    private suspend fun fetchTracksForPlaylist(playlistName: String): List<TrackCache> {
        val trimmedPlaylistName = playlistName.trim()
        Log.d(TAG, "Fetchin’ tracks for playlist: $trimmedPlaylistName")
        val requestId = UUID.randomUUID().toString()
        synchronized(browseRequests) {
            browseRequests[requestId] = trimmedPlaylistName
        }
        var tracks = listOf<TrackCache>()
        var attempt = 0
        val maxRetries = 1
        val baseDelay = 200L
        val fetchStart = System.currentTimeMillis()
        while (attempt < maxRetries) {
            attempt++
            webSocketManager.emit("browseLibrary", JSONObject().put("uri", "playlists/$trimmedPlaylistName"))
            val timeout = 2000L
            val startTime = System.currentTimeMillis()
            var shouldContinue = true
            while (System.currentTimeMillis() - startTime < timeout && shouldContinue) {
                synchronized(browseRequests) {
                    val resultKey = "$requestId:results"
                    if (browseRequests.containsKey(resultKey)) {
                        val results = browseRequests[resultKey]!!.split("|").mapNotNull {
                            val parts = it.split(",")
                            if (parts.size >= 6) Track(parts[0], parts[1], parts[2], parts[3], parts[4].takeIf { it.isNotEmpty() }, parts[5]) else null
                        }
                        tracks = results.map { track ->
                            TrackCache(
                                uri = track.uri,
                                title = track.title,
                                artist = track.artist,
                                playlistName = trimmedPlaylistName,
                                service = track.service,
                                albumArt = track.albumArt,
                                type = track.type,
                                lastUpdated = System.currentTimeMillis()
                            )
                        }.distinctBy { "${it.uri}:${it.playlistName}" }
                        browseRequests.remove(resultKey)
                        shouldContinue = false
                    }
                }
                delay(50)
            }
            synchronized(browseRequests) {
                browseRequests.entries.removeIf { it.value == trimmedPlaylistName }
            }
            if (tracks.isNotEmpty() || shouldContinue == false) break
            if (System.currentTimeMillis() - startTime >= timeout) {
                Log.w(TAG, "Timeout fetching tracks for $trimmedPlaylistName, attempt $attempt/$maxRetries")
                if (attempt < maxRetries) delay(baseDelay shl attempt)
            }
        }
        if (tracks.isEmpty()) {
            Log.e(TAG, "Failed to fetch tracks for $trimmedPlaylistName after $maxRetries attempts")
        }
        Log.d(TAG, "Got ${tracks.size} unique tracks for $trimmedPlaylistName: ${tracks.map { "${it.artist} - ${it.title}" }}, took ${System.currentTimeMillis() - fetchStart}ms")
        withContext(Dispatchers.IO) {
            cacheDao.insertPlaylists(listOf(PlaylistCache(trimmedPlaylistName, System.currentTimeMillis(), System.currentTimeMillis(), computeTrackHash(tracks), tracks.isEmpty())))
            try {
                cacheDao.insertOrIgnoreTracks(tracks)
                val insertedTracks = cacheDao.getTracksForPlaylist(trimmedPlaylistName)
                Log.d(TAG, "After insertion, retrieved ${insertedTracks.size} tracks for '$trimmedPlaylistName'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert tracks for '$trimmedPlaylistName': ${e.message}")
            }
        }
        return tracks
    }

    private fun fetchAndCacheRecentPlaylists() {
        viewModelScope.launch {
            isLoading = true
            try {
                fetchPlaylists()
            } catch (e: Exception) {
                Log.e(TAG, "Fetch and cache failed: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    suspend fun getCachedTrackUris(): List<String> {
        val startTime = System.currentTimeMillis()
        val tracks = withContext(Dispatchers.IO) { cacheDao.getRecentTracks() }
        Log.d(TAG, "Sendin’ ${tracks.size} cached track URIs to Grok, took ${System.currentTimeMillis() - startTime}ms")
        return tracks.map { it.uri }.distinct()
    }

    fun browsePlaylistTracks(playlistName: String) {
        val trimmedPlaylistName = playlistName.trim()
        webSocketManager.emit("browseLibrary", JSONObject().put("uri", "playlists/$trimmedPlaylistName"))
        Log.d(TAG, "Emitted browseLibrary for playlist: $trimmedPlaylistName")
    }

    fun createPlaylist(name: String) {
        val trimmedName = name.trim()
        webSocketManager.emit("createPlaylist", JSONObject().put("name", trimmedName))
        Log.d(TAG, "Emitted createPlaylist: $trimmedName")
    }

    fun addToPlaylist(playlistName: String, trackUri: String, trackType: String) {
        val trimmedPlaylistName = playlistName.trim()
        val service = when {
            trackUri.startsWith("tidal://") -> "tidal"
            trackType == "folder-with-favourites" -> "tidal"
            else -> "mpd"
        }
        val data = JSONObject().apply {
            put("name", trimmedPlaylistName)
            put("service", service)
            put("uri", trackUri)
        }
        webSocketManager.emit("addToPlaylist", data)
        Log.d(TAG, "Emitted addToPlaylist: $trimmedPlaylistName, $trackUri, service=$service")
        val track = Track(
            title = "Unknown Title",
            artist = "Unknown Artist",
            uri = trackUri,
            service = service,
            albumArt = null,
            type = trackType
        )
        playlists = playlists.map { playlist ->
            if (playlist.name == trimmedPlaylistName) Playlist(playlist.name, playlist.tracks + track) else playlist
        }
        pendingTracks[trimmedPlaylistName] = (pendingTracks[trimmedPlaylistName] ?: mutableListOf()).apply { add(track) }
        Log.d(TAG, "Locally added track to $trimmedPlaylistName: ${track.title} by ${track.artist}")
    }

    fun removeFromPlaylist(playlistName: String, trackUri: String) {
        val trimmedPlaylistName = playlistName.trim()
        val data = JSONObject().apply {
            put("name", trimmedPlaylistName)
            put("uri", trackUri)
        }
        webSocketManager.emit("removeFromPlaylist", data)
        Log.d(TAG, "Emitted removeFromPlaylist: $trimmedPlaylistName, $trackUri")
        playlists = playlists.map { playlist ->
            if (playlist.name == trimmedPlaylistName) Playlist(playlist.name, playlist.tracks.filter { it.uri != trackUri }) else playlist
        }
        pendingTracks[trimmedPlaylistName] = pendingTracks[trimmedPlaylistName]?.filter { it.uri != trackUri }?.toMutableList() ?: mutableListOf()
        Log.d(TAG, "Updated pendingTracks for $trimmedPlaylistName: ${pendingTracks[trimmedPlaylistName]?.size ?: 0} tracks")
    }

    fun playPlaylist(playlistName: String) {
        val trimmedPlaylistName = playlistName.trim()
        webSocketManager.emit("playPlaylist", JSONObject().put("name", trimmedPlaylistName))
        Log.d(TAG, "Emitted playPlaylist: $trimmedPlaylistName")
    }

    fun deletePlaylist(playlistName: String) {
        val trimmedPlaylistName = playlistName.trim()
        webSocketManager.emit("deletePlaylist", JSONObject().put("name", trimmedPlaylistName))
        Log.d(TAG, "Emitted deletePlaylist: $trimmedPlaylistName")
        playlists = playlists.filter { it.name != trimmedPlaylistName }
        pendingTracks.remove(trimmedPlaylistName)
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
                        if (parts.size >= 6) Track(parts[0], parts[1], parts[2], parts[3], parts[4].takeIf { it.isNotEmpty() }, parts[5]) else null
                    }
                    browseRequests.remove("search:results")
                    return results
                }
            }
            delay(100)
        }
        return emptyList()
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
                    playlistName.trim()
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
                val excludedUris = getCachedTrackUris()
                val songList = xAiApi.generateSongList(
                    vibe = vibe,
                    numSongs = numSongsInt,
                    artists = artists.takeIf { it.isNotBlank() },
                    era = era.takeIf { it != GrokConfig.ERA_OPTIONS.first() },
                    maxSongsPerArtist = maxSongsPerArtistInt,
                    instrument = instrument.takeIf { it != GrokConfig.INSTRUMENT_OPTIONS.first() },
                    language = language.takeIf { it != GrokConfig.LANGUAGE_OPTIONS.first() },
                    excludedUris = excludedUris
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
                                    Log.d(TAG, "Added track: ${track.title} by ${track.artist}, URI: ${track.uri}, total added=$addedTracks")
                                    break
                                } else {
                                    Log.w(TAG, "Skipped duplicate URI: ${track.uri}")
                                }
                            } else {
                                Log.w(TAG, "Skipped track: $title by $artist, max songs per artist ($maxSongsPerArtistInt) reached")
                            }
                        } else {
                            Log.w(TAG, "Skipped duplicate track: $title by $artist")
                        }
                    }
                    if (results.isEmpty()) {
                        Log.w(TAG, "No results for $query, movin’ on")
                    }
                }
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