package com.example.volumiovibe

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
class PlaylistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PlaylistScreen(viewModel = viewModel())
            }
        }
    }
}

data class Track(val title: String, val artist: String, val uri: String, val type: String)
data class Playlist(val name: String, val tracks: List<Track>)

class PlaylistViewModel : ViewModel() {
    private val TAG = "VolumioPlaylistActivity"
    private val webSocketManager = WebSocketManager
    private val xAiApi = XAiApi(BuildConfig.XAI_API_KEY)
    private val client = OkHttpClient()

    var playlists by mutableStateOf<List<Playlist>>(emptyList())
    var selectedPlaylist by mutableStateOf<Playlist?>(null)
    var vibeInput by mutableStateOf("")
    var selectedVibe by mutableStateOf("Choose a vibe...")
    var era by mutableStateOf("Any Era")
    var language by mutableStateOf("Any Language")
    var instrument by mutableStateOf("None")
    var playlistName by mutableStateOf("")
    var artists by mutableStateOf("")
    var numSongs by mutableStateOf("20")
    var maxSongsPerArtist by mutableStateOf("2")
    var isLoading by mutableStateOf(false)
    var aiSuggestions by mutableStateOf<List<Track>>(emptyList())

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
                            newPlaylists.add(Playlist(name = item, tracks = emptyList()))
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
                    val navigation = data.optJSONObject("navigation") ?: return@on
                    val lists = navigation.optJSONArray("lists") ?: return@on
                    val results = mutableListOf<Track>()
                    for (listIdx in 0 until lists.length()) {
                        val list = lists.optJSONObject(listIdx) ?: continue
                        val items = list.optJSONArray("items") ?: continue
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val type = item.optString("type", "")
                            val title = item.optString("title", "")
                            val artist = item.optString("artist", "")
                            val uri = item.optString("uri", "")
                            if ((type == "song" || type == "folder-with-favourites") &&
                                title.isNotBlank() && artist.isNotBlank() && uri.isNotBlank()
                            ) {
                                results.add(Track(title, artist, uri, type))
                            }
                        }
                    }
                    aiSuggestions = results
                    Log.d(TAG, "Search results: ${results.size} tracks, tracks: $results")
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
                    val success = response.getBoolean("success")
                    val reason = response.optString("reason", "No reason provided")
                    if (!success) {
                        Log.e(TAG, "addToPlaylist failed: $reason")
                    } else {
                        Log.d(TAG, "addToPlaylist succeeded for track")
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
    }

    fun removeFromPlaylist(playlistName: String, trackUri: String) {
        val data = JSONObject().apply {
            put("name", playlistName)
            put("uri", trackUri)
        }
        webSocketManager.emit("removeFromPlaylist", data)
        Log.d(TAG, "Emitted removeFromPlaylist: $playlistName, $trackUri")
    }

    fun playPlaylist(playlistName: String) {
        webSocketManager.emit("playPlaylist", JSONObject().put("name", playlistName))
        Log.d(TAG, "Emitted playPlaylist: $playlistName")
    }

    fun deletePlaylist(playlistName: String) {
        webSocketManager.emit("deletePlaylist", JSONObject().put("name", playlistName))
        Log.d(TAG, "Emitted deletePlaylist: $playlistName")
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
                val finalPlaylistName = if (playlistName.isBlank()) {
                    xAiApi.generatePlaylistName(
                        vibe = if (selectedVibe != "Choose a vibe...") selectedVibe else vibeInput,
                        artists = artists.takeIf { it.isNotBlank() },
                        era = era.takeIf { it != "Any Era" },
                        instrument = instrument.takeIf { it != "None" },
                        language = language.takeIf { it != "Any Language" }
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

                val numSongsInt = numSongs.toIntOrNull() ?: 20
                val maxSongsPerArtistInt = if (numSongsInt < 10) 1 else maxSongsPerArtist.toIntOrNull() ?: 2
                val songList = xAiApi.generateSongList(
                    vibe = if (selectedVibe != "Choose a vibe...") selectedVibe else vibeInput,
                    numSongs = numSongsInt,
                    artists = artists.takeIf { it.isNotBlank() },
                    era = era.takeIf { it != "Any Era" },
                    maxSongsPerArtist = maxSongsPerArtistInt,
                    instrument = instrument.takeIf { it != "None" },
                    language = language.takeIf { it != "Any Language" }
                ) ?: throw Exception("No songs from xAI API")

                val tracks = songList.split("\n").mapNotNull { line ->
                    val parts = line.split(" - ", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }.take(numSongsInt) // Enforce numSongs limit
                Log.d(TAG, "Song list: ${tracks.size} tracks: $tracks")

                var addedTracks = 0
                val addedUris = mutableSetOf<String>()
                val artistCounts = mutableMapOf<String, Int>()
                val trackKeys = mutableSetOf<String>() // For deduplication
                aiSuggestions = emptyList()
                for ((artist, title) in tracks) {
                    if (addedTracks >= numSongsInt) break
                    val query = "$artist $title"
                    webSocketManager.emit("search", JSONObject().put("value", query))
                    Log.d(TAG, "Emitted search: $query")
                    val results = waitForSearchResults()
                    if (results.isNotEmpty()) {
                        val track = results.firstOrNull { it.type == "song" } // Only songs, no albums
                        if (track != null) {
                            val trackKey = "${track.artist}:${track.title}".lowercase()
                            if (!trackKeys.contains(trackKey)) {
                                val currentCount = artistCounts.getOrDefault(track.artist, 0)
                                if (currentCount < maxSongsPerArtistInt) {
                                    if (addedUris.add(track.uri)) {
                                        addToPlaylist(finalPlaylistName, track.uri, track.type)
                                        addedTracks++
                                        artistCounts[track.artist] = currentCount + 1
                                        trackKeys.add(trackKey)
                                        Log.d(TAG, "Added track: ${track.title} by ${track.artist}, URI: ${track.uri}, Type: ${track.type}, Artist count: ${artistCounts[track.artist]}")
                                        delay(500) // Avoid duplicate emits
                                    } else {
                                        Log.w(TAG, "Skipped duplicate URI: ${track.uri}")
                                    }
                                } else {
                                    Log.w(TAG, "Skipped track $title by $artist, max songs per artist ($maxSongsPerArtistInt) reached")
                                }
                            } else {
                                Log.w(TAG, "Skipped duplicate track: $title by $artist")
                            }
                        } else {
                            Log.w(TAG, "No valid song track for $artist - $title")
                        }
                    } else {
                        Log.w(TAG, "No search results for $artist - $title")
                    }
                    aiSuggestions = emptyList()
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
                aiSuggestions = emptyList()
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
        val artistText = artists?.let { "Artists like $it, " } ?: ""
        val eraText = era?.let { "From the $it, " } ?: ""
        val instrumentText = instrument?.let { "Featuring the $it, " } ?: ""
        val languageText = language?.let { "In $it, " } ?: ""
        val prompt = "Yo Grok, gimme a dope playlist name for $vibe. ${artistText}${eraText}${instrumentText}${languageText}Keep it short, max 20 chars, and hella fire. No extra fluff, just the name!"
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
        val artistText = artists?.let { "Inspired by artists like $it, " } ?: ""
        val eraText = era?.let { "From the $it, " } ?: ""
        val maxArtistText = "Max $maxSongsPerArtist songs per artist, "
        val instrumentText = instrument?.let { "Songs MUST have the $it as a PRIMARY, AUDIBLE instrument, central to the track’s sound, " } ?: ""
        val languageText = language?.let { "In $it, " } ?: ""
        val prompt = "Yo Grok, you my DJ! Gimme a list of $numSongs songs for $vibe. ${artistText}${eraText}${maxArtistText}${instrumentText}${languageText}Format it like 'Artist - Title' with each song on a new line. No extra bullshit, just the list."
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
    val context = LocalContext.current
    val vibeOptions = listOf(
        "Choose a vibe...", "Sad Boi Tears", "Happy as Fuck", "Slow but Smilin’",
        "Fast Emo Cry Shit", "Punk Rock Rager", "Trap Bangerz", "Stoner Chill"
    )
    val eraOptions = listOf("Any Era", "1960s", "1970s", "1980s", "1990s", "2000s", "2010s", "2020s")
    val languageOptions = listOf("Any Language", "English", "Dutch", "German", "French", "Spanish")
    val instrumentOptions = listOf("None", "Violin", "Steel Drums", "Contrabass", "Theremin", "Sitar", "Banjo")

    var vibeExpanded by remember { mutableStateOf(false) }
    var eraExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var instrumentExpanded by remember { mutableStateOf(false) }

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
                                    viewModel.vibeInput = if (vibe != "Choose a vibe...") vibe else ""
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
                        viewModel.selectedVibe = "Choose a vibe..."
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

                Button(
                    onClick = { viewModel.generateAiPlaylist(context) },
                    enabled = !viewModel.isLoading,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Make That Playlist, Fam!")
                }

                if (viewModel.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    items(viewModel.playlists) { playlist: Playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            onSelect = { viewModel.selectedPlaylist = playlist },
                            onPlay = { viewModel.playPlaylist(playlist.name) },
                            onDelete = { viewModel.deletePlaylist(playlist.name) }
                        )
                    }
                }

                viewModel.selectedPlaylist?.let { playlist ->
                    Text(
                        text = "${playlist.name} Tracks",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        items(playlist.tracks) { track ->
                            TrackCard(
                                track = track,
                                onRemove = {
                                    viewModel.removeFromPlaylist(playlist.name, track.uri)
                                }
                            )
                        }
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
                            TrackCard(
                                track = track,
                                onAdd = {
                                    viewModel.selectedPlaylist?.let { playlist ->
                                        viewModel.addToPlaylist(playlist.name, track.uri, track.type)
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
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect() }
    ) {
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
    }
}

@Composable
fun TrackCard(
    track: Track,
    onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = track.title, style = MaterialTheme.typography.bodyLarge)
                Text(text = track.artist, style = MaterialTheme.typography.bodySmall)
            }
            onAdd?.let {
                IconButton(onClick = it) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_add),
                        contentDescription = "Add"
                    )
                }
            }
            onRemove?.let {
                IconButton(onClick = it) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_delete),
                        contentDescription = "Remove"
                    )
                }
            }
        }
    }
}