package com.example.volumiovibe

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

@OptIn(ExperimentalMaterial3Api::class)
class PlaylistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dynamicColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                MaterialTheme.colorScheme
            }
            MaterialTheme(colorScheme = dynamicColor) {
                PlaylistScreen(viewModel = viewModel())
            }
        }
    }
}

data class Playlist(val name: String, val tracks: List<Track>)

object PlaylistStateHolder {
    var selectedVibe: String = GrokConfig.VIBE_OPTIONS.first()
    var vibeInput: String = ""
    var era: String = GrokConfig.ERA_OPTIONS.first()
    var language: String = GrokConfig.LANGUAGE_OPTIONS.first()
    var instrument: String = GrokConfig.INSTRUMENT_OPTIONS.first()
    var playlistName: String = ""
    var artists: String = ""
    var numSongs: String = GrokConfig.DEFAULT_NUM_SONGS
    var maxSongsPerArtist: String = GrokConfig.DEFAULT_MAX_SONGS_PER_ARTIST
    val recentVibes: MutableList<String> = mutableListOf()
}

class PlaylistViewModel : ViewModel() {
    private val TAG = "VolumioPlaylistActivity"
    private val webSocketManager = WebSocketManager
    private val xAiApi = XAiApi(BuildConfig.XAI_API_KEY)
    private val client = OkHttpClient()

    var playlists by mutableStateOf<List<Playlist>>(emptyList())
    var isLoading by mutableStateOf(false)
    var aiSuggestions by mutableStateOf<List<Track>>(emptyList())
    private val pendingTracks = mutableMapOf<String, MutableList<Track>>()

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
        fetchPlaylists()
    }

    private fun connectWebSocket() {
        viewModelScope.launch {
            webSocketManager.initialize()
            webSocketManager.debugAllEvents()
            webSocketManager.on("pushListPlaylist") { args: Array<Any> ->
                Log.d(TAG, "Received pushListPlaylist: ${args[0]}")
                try {
                    val data = when (args[0]) {
                        is String -> if (args[0] == "undefined") JSONArray() else JSONArray(args[0] as String)
                        is JSONArray -> args[0] as JSONArray
                        else -> {
                            Log.w(TAG, "Unexpected pushListPlaylist type: ${args[0]?.javaClass?.simpleName}")
                            return@on
                        }
                    }
                    val newPlaylists = mutableListOf<Playlist>()
                    for (i in 0 until data.length()) {
                        val item = data.get(i)
                        if (item is String && item != "undefined") {
                            val tracks = pendingTracks[item] ?: emptyList<Track>()
                            newPlaylists.add(Playlist(name = item, tracks = tracks))
                            Log.d(TAG, "Added playlist $item with ${tracks.size} tracks from pendingTracks")
                        } else {
                            Log.w(TAG, "Skipping invalid playlist item: $item")
                        }
                    }
                    playlists = newPlaylists
                    Log.d(TAG, "Playlists updated: ${playlists.size} playlists: ${playlists.map { it.name }}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse pushListPlaylist: ${e.message}")
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
                    Log.d(TAG, "Raw pushBrowseLibrary response: $data")
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
                            if ((type == "song" || type == "folder-with-favourites") &&
                                uri.isNotBlank()
                            ) {
                                results.add(Track(title, artist, uri, service, albumArt, type))
                            }
                            // Extract playlist name from uri
                            if (item.has("uri") && item.getString("uri").startsWith("playlists/")) {
                                playlistName = item.getString("uri").removePrefix("playlists/")
                            }
                        }
                    }
                    if (playlistName != null) {
                        playlists = playlists.map { playlist ->
                            if (playlist.name == playlistName) {
                                Playlist(playlist.name, results)
                            } else {
                                playlist
                            }
                        }
                        pendingTracks[playlistName] = results.toMutableList()
                        Log.d(TAG, "Updated tracks for $playlistName: ${results.size} tracks")
                    } else {
                        aiSuggestions = results
                        Log.d(TAG, "Search results: ${results.size} tracks, tracks: $results")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse pushBrowseLibrary: ${e.message}")
                }
            }
            webSocketManager.on("pushCreatePlaylist") { args: Array<Any> ->
                Log.d(TAG, "Received pushCreatePlaylist: ${args[0]}")
                try {
                    if (args[0] == null) {
                        Log.e(TAG, "pushCreatePlaylist response is null")
                        return@on
                    }
                    val response = args[0] as JSONObject
                    val success = response.getBoolean("success")
                    val reason = response.optString("reason", "No reason provided")
                    if (!success) {
                        Log.e(TAG, "createPlaylist failed: $reason")
                    } else {
                        Log.d(TAG, "createPlaylist succeeded for playlist")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse pushCreatePlaylist: ${e.message}")
                }
            }
            webSocketManager.on("pushAddToPlaylist") { args: Array<Any> ->
                Log.d(TAG, "Received pushAddToPlaylist: ${args[0]}")
                try {
                    if (args[0] == null) {
                        Log.e(TAG, "pushAddToPlaylist response is null")
                        return@on
                    }
                    val response = args[0] as JSONObject
                    Log.d(TAG, "Raw pushAddToPlaylist response: $response")
                    val success = response.getBoolean("success")
                    val reason = response.optString("reason", "No reason provided")
                    if (!success) {
                        Log.e(TAG, "addToPlaylist failed: $reason")
                    } else {
                        Log.d(TAG, "addToPlaylist succeeded for track")
                        val playlistName = response.optString("name", "")
                        val uri = response.optString("uri", "")
                        val title = response.optString("title", "Unknown Title")
                        val artist = response.optString("artist", "Unknown Artist")
                        val service = response.optString("service", "mpd")
                        val type = response.optString("type", "song")
                        if (playlistName.isNotBlank() && uri.isNotBlank()) {
                            val newTrack = Track(title, artist, uri, service, null, type)
                            playlists = playlists.map { playlist ->
                                if (playlist.name == playlistName) {
                                    Playlist(playlist.name, playlist.tracks + newTrack)
                                } else {
                                    playlist
                                }
                            }
                            pendingTracks[playlistName] = (pendingTracks[playlistName] ?: mutableListOf()).apply { add(newTrack) }
                            Log.d(TAG, "Updated playlist $playlistName with track: $title by $artist")
                        } else {
                            Log.w(TAG, "Invalid pushAddToPlaylist data: name=$playlistName, uri=$uri")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse pushAddToPlaylist: ${e.message}")
                }
            }
            webSocketManager.on("createPlaylist") { args: Array<Any> ->
                Log.d(TAG, "Received createPlaylist: ${args[0]}")
                try {
                    if (args[0] == null) {
                        Log.e(TAG, "createPlaylist response is null")
                        return@on
                    }
                    val response = args[0] as JSONObject
                    val success = response.getBoolean("success")
                    val reason = response.optString("reason", "No reason provided")
                    if (!success) {
                        Log.e(TAG, "createPlaylist failed: $reason")
                    } else {
                        Log.d(TAG, "createPlaylist succeeded for playlist")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse createPlaylist: ${e.message}")
                }
            }
            webSocketManager.on("addToPlaylist") { args: Array<Any> ->
                Log.d(TAG, "Received addToPlaylist: ${args[0]}")
                try {
                    if (args[0] == null) {
                        Log.e(TAG, "addToPlaylist response is null")
                        return@on
                    }
                    val response = args[0] as JSONObject
                    val success = response.getBoolean("success")
                    val reason = response.optString("reason", "No reason provided")
                    if (!success) {
                        Log.e(TAG, "addToPlaylist failed: $reason")
                    } else {
                        Log.d(TAG, "addToPlaylist succeeded for track")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse addToPlaylist: ${e.message}")
                }
            }
        }
    }

    fun fetchPlaylists() {
        webSocketManager.emit("listPlaylist", JSONObject())
        Log.d(TAG, "Emitted listPlaylist")
    }

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
        // Add track locally
        val track = aiSuggestions.find { it.uri == trackUri } ?: Track(
            title = "Unknown Title",
            artist = "Unknown Artist",
            uri = trackUri,
            service = service,
            albumArt = null,
            type = trackType
        )
        playlists = playlists.map { playlist ->
            if (playlist.name == playlistName) {
                Playlist(playlist.name, playlist.tracks + track)
            } else {
                playlist
            }
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
        // Update playlists
        playlists = playlists.map { playlist ->
            if (playlist.name == playlistName) {
                Playlist(playlist.name, playlist.tracks.filter { it.uri != trackUri })
            } else {
                playlist
            }
        }
        // Update pendingTracks - handle null case properly
        pendingTracks[playlistName] = pendingTracks[playlistName]?.filter { it.uri != trackUri }?.toMutableList()
            ?: mutableListOf()
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

    fun generateAiPlaylist(context: android.content.Context) {
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
                    val query = "$artist $title".replace(Regex("^\\d+\\.\\s*"), "") // Strip number prefix
                    aiSuggestions = emptyList() // Clear before search
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

class XAiApi(private val apiKey: String) {
    private val client = OkHttpClient()
    private val mediaType = "application/json".toMediaType()

    init { Log.d("XAiApi", "API Key: $apiKey") }

    suspend fun generatePlaylistName(
        vibe: String,
        artists: String? = null,
        era: String? = null,
        instrument: String? = null,
        language: String? = null
    ): String = withContext(Dispatchers.IO) {
        val artistText = GrokConfig.getArtistText(artists)
        val eraText = GrokConfig.getEraText(era)
        val instrumentText = GrokConfig.getInstrumentText(instrument)
        val languageText = GrokConfig.getLanguageText(language)
        val prompt = String.format(
            GrokConfig.PLAYLIST_NAME_PROMPT,
            vibe, artistText, eraText, instrumentText, languageText, GrokConfig.MAX_PLAYLIST_NAME_LENGTH
        )
        val payload = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You a dope AI namer, droppin’ short, fire playlist names.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("model", "grok-3-latest")
            put("stream", false)
            put("temperature", 0.7)
        }
        val request = Request.Builder()
            .url("https://api.x.ai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        try {
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            Log.e("XAiApi", "Failed to get playlist name: ${e.message}")
            "Grok’s Fire Mix"
        }
    }

    suspend fun generateSongList(
        vibe: String,
        numSongs: Int,
        artists: String? = null,
        era: String? = null,
        maxSongsPerArtist: Int,
        instrument: String? = null,
        language: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val artistText = GrokConfig.getArtistText(artists)
        val eraText = GrokConfig.getEraText(era)
        val maxArtistText = GrokConfig.getMaxArtistText(maxSongsPerArtist)
        val instrumentText = GrokConfig.getInstrumentText(instrument)
        val languageText = GrokConfig.getLanguageText(language)
        val prompt = String.format(
            GrokConfig.SONG_LIST_PROMPT,
            numSongs, vibe, artistText, eraText, maxArtistText, instrumentText, languageText
        )
        val payload = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You a dope AI DJ, spittin’ song lists in 'Artist - Title' format.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("model", "grok-3-latest")
            put("stream", false)
            put("temperature", 0)
        }
        val request = Request.Builder()
            .url("https://api.x.ai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        try {
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            Log.e("XAiApi", "Failed to get song list: ${e.message}")
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel) {
    val TAG = "PlaylistScreen"
    val context = LocalContext.current
    val vibeOptions = GrokConfig.VIBE_OPTIONS
    val eraOptions = GrokConfig.ERA_OPTIONS
    val languageOptions = GrokConfig.LANGUAGE_OPTIONS
    val instrumentOptions = GrokConfig.INSTRUMENT_OPTIONS
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var optionsExpanded by remember { mutableStateOf(false) }
    var vibeExpanded by remember { mutableStateOf(false) }
    var eraExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var instrumentExpanded by remember { mutableStateOf(false) }
    var expandedPlaylist by remember { mutableStateOf<String?>(null) }

    Log.d(TAG, "PlaylistScreen initialized with vibeOptions: ${vibeOptions.joinToString()}")

    LaunchedEffect(Unit) {
        WebSocketManager.onConnectionChange { isConnected ->
            coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    if (!isConnected) {
                        Toast.makeText(context, "WebSocket ain’t connected, fam!", Toast.LENGTH_SHORT).show()
                        WebSocketManager.reconnect()
                    } else {
                        Toast.makeText(context, "WebSocket connected, yo!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlist Vibe", textAlign = TextAlign.Center) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Search Bar with Options Toggle
                SearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        viewModel.vibeInput = it
                        viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                    },
                    onSearch = { viewModel.generateAiPlaylist(context) },
                    active = false,
                    onActiveChange = {},
                    placeholder = { Text("Enter Vibe for Playlist") },
                    trailingIcon = {
                        IconButton(onClick = {
                            optionsExpanded = !optionsExpanded
                        }) {
                            Icon(
                                painter = painterResource(
                                    id = if (optionsExpanded) android.R.drawable.ic_menu_close_clear_cancel
                                    else android.R.drawable.ic_menu_add
                                ),
                                contentDescription = if (optionsExpanded) "Collapse Options" else "Expand Options"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {}

                // Recent Vibes Chips
                if (PlaylistStateHolder.recentVibes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlaylistStateHolder.recentVibes.take(3).forEach { vibe ->
                            FilterChip(
                                selected = searchQuery == vibe,
                                onClick = {
                                    searchQuery = vibe
                                    viewModel.vibeInput = vibe
                                    viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                                },
                                label = { Text(vibe) }
                            )
                        }
                    }
                }

                // Options Dropdown
                if (optionsExpanded) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ExposedDropdownMenuBox(
                                expanded = vibeExpanded,
                                onExpandedChange = { vibeExpanded = !vibeExpanded }
                            ) {
                                TextField(
                                    value = viewModel.selectedVibe,
                                    onValueChange = { viewModel.selectedVibe = it },
                                    label = { Text("Pick a Vibe or Type Your Own") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true
                                )
                                ExposedDropdownMenu(
                                    expanded = vibeExpanded,
                                    onDismissRequest = { vibeExpanded = false }
                                ) {
                                    vibeOptions.forEach { vibe ->
                                        DropdownMenuItem(
                                            text = { Text(vibe) },
                                            onClick = {
                                                viewModel.selectedVibe = vibe
                                                viewModel.vibeInput = if (vibe != GrokConfig.VIBE_OPTIONS.first()) vibe else ""
                                                vibeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = viewModel.vibeInput,
                                onValueChange = {
                                    viewModel.vibeInput = it
                                    viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                                },
                                label = { Text("Custom Vibe") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            ExposedDropdownMenuBox(
                                expanded = eraExpanded,
                                onExpandedChange = { eraExpanded = !eraExpanded }
                            ) {
                                TextField(
                                    value = viewModel.era,
                                    onValueChange = { viewModel.era = it },
                                    label = { Text("Pick an Era") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true
                                )
                                ExposedDropdownMenu(
                                    expanded = eraExpanded,
                                    onDismissRequest = { eraExpanded = false }
                                ) {
                                    eraOptions.forEach { era ->
                                        DropdownMenuItem(
                                            text = { Text(era) },
                                            onClick = {
                                                viewModel.era = era
                                                eraExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            ExposedDropdownMenuBox(
                                expanded = languageExpanded,
                                onExpandedChange = { languageExpanded = !languageExpanded }
                            ) {
                                TextField(
                                    value = viewModel.language,
                                    onValueChange = { viewModel.language = it },
                                    label = { Text("Music Language") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true
                                )
                                ExposedDropdownMenu(
                                    expanded = languageExpanded,
                                    onDismissRequest = { languageExpanded = false }
                                ) {
                                    languageOptions.forEach { lang ->
                                        DropdownMenuItem(
                                            text = { Text(lang) },
                                            onClick = {
                                                viewModel.language = lang
                                                languageExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            ExposedDropdownMenuBox(
                                expanded = instrumentExpanded,
                                onExpandedChange = { instrumentExpanded = !instrumentExpanded }
                            ) {
                                TextField(
                                    value = viewModel.instrument,
                                    onValueChange = { viewModel.instrument = it },
                                    label = { Text("Featured Instrument") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true
                                )
                                ExposedDropdownMenu(
                                    expanded = instrumentExpanded,
                                    onDismissRequest = { instrumentExpanded = false }
                                ) {
                                    instrumentOptions.forEach { inst ->
                                        DropdownMenuItem(
                                            text = { Text(inst) },
                                            onClick = {
                                                viewModel.instrument = inst
                                                instrumentExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = viewModel.playlistName,
                                onValueChange = { viewModel.playlistName = it },
                                label = { Text("Playlist Name (blank for Grok’s pick)") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = viewModel.artists,
                                onValueChange = { viewModel.artists = it },
                                label = { Text("Example Artists (e.g., Green Day)") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = viewModel.numSongs,
                                onValueChange = { viewModel.numSongs = it },
                                label = { Text("How Many Tracks?") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = viewModel.maxSongsPerArtist,
                                onValueChange = { viewModel.maxSongsPerArtist = it },
                                label = { Text("Max Songs per Artist") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Generate Playlist Button
                if (optionsExpanded) {
                    FilledTonalButton(
                        onClick = { viewModel.generateAiPlaylist(context) },
                        enabled = !viewModel.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Text("Make That Playlist, Fam!")
                    }
                }

                if (viewModel.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                }

                if (!optionsExpanded) {
                    FilledTonalButton(
                        onClick = { viewModel.generateAiPlaylist(context) },
                        enabled = !viewModel.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Text("Make That Playlist, Fam!")
                    }
                }

                // Playlist List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(viewModel.playlists) { playlist: Playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            isExpanded = expandedPlaylist == playlist.name,
                            onToggle = {
                                Log.d(TAG, "Toggling playlist: ${playlist.name}, current expanded: $expandedPlaylist")
                                expandedPlaylist = if (expandedPlaylist == playlist.name) null else playlist.name
                                if (expandedPlaylist == playlist.name) {
                                    viewModel.browsePlaylistTracks(playlist.name)
                                }
                                Log.d(TAG, "New expandedPlaylist: $expandedPlaylist")
                            },
                            onPlay = { viewModel.playPlaylist(playlist.name) },
                            onDelete = { viewModel.deletePlaylist(playlist.name) },
                            onRemoveTrack = { trackUri -> viewModel.removeFromPlaylist(playlist.name, trackUri) }
                        )
                    }
                }

                if (viewModel.aiSuggestions.isNotEmpty()) {
                    Text(
                        text = "AI Suggested Tracks",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        items(viewModel.aiSuggestions) { track ->
                            PlaylistTrackItem(
                                track = track,
                                actionButtons = {
                                    IconButton(onClick = {
                                        viewModel.playlists.find { it.name == expandedPlaylist }?.let { playlist ->
                                            viewModel.addToPlaylist(playlist.name, track.uri, track.type)
                                        }
                                    }) {
                                        Icon(
                                            painter = painterResource(id = android.R.drawable.ic_menu_add),
                                            contentDescription = "Add"
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun PlaylistCard(
    playlist: Playlist,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onRemoveTrack: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onToggle() }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onPlay) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_media_play),
                        contentDescription = "Play"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_delete),
                        contentDescription = "Delete"
                    )
                }
            }
            Log.d("PlaylistCard", "Playlist: ${playlist.name}, isExpanded: $isExpanded, tracks: ${playlist.tracks}")
            if (isExpanded && playlist.tracks.isNotEmpty()) {
                Text(
                    text = "${playlist.name} Tracks",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(playlist.tracks) { track ->
                        PlaylistTrackItem(
                            track = track,
                            actionButtons = {
                                IconButton(onClick = { onRemoveTrack(track.uri) }) {
                                    Icon(
                                        painter = painterResource(id = android.R.drawable.ic_delete),
                                        contentDescription = "Remove"
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistTrackItem(track: Track, actionButtons: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall
            )
        }
        actionButtons()
    }
}