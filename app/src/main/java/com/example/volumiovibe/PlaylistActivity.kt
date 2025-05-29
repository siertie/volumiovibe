package com.example.volumiovibe

import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
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

data class Track(val title: String, val artist: String, val uri: String)
data class Playlist(val name: String, val tracks: List<Track>)

class PlaylistViewModel : ViewModel() {
    private val TAG = "VolumioPlaylistActivity"
    private val webSocketManager = WebSocketManager
    private val xAiApi = XAiApi("YOUR_XAI_API_KEY") // Replace with xAI API setup

    var playlists by mutableStateOf<List<Playlist>>(emptyList())
    var selectedPlaylist by mutableStateOf<Playlist?>(null)
    var vibeInput by mutableStateOf("")
    var aiSuggestions by mutableStateOf<List<Track>>(emptyList())

    init {
        connectWebSocket()
        fetchPlaylists()
    }

    private fun connectWebSocket() {
        viewModelScope.launch {
            webSocketManager.initialize()
            webSocketManager.on("pushListPlaylist") { args: Array<Any> -> // Fixed: Array<Any>
                val data = args[0] as JSONArray
                val newPlaylists = mutableListOf<Playlist>()
                for (i in 0 until data.length()) {
                    val playlist = data.getJSONObject(i)
                    val name = playlist.getString("name")
                    val tracks = playlist.getJSONArray("tracks").let { array ->
                        List(array.length()) { idx ->
                            val track = array.getJSONObject(idx)
                            Track(
                                title = track.getString("title"),
                                artist = track.getString("artist"),
                                uri = track.getString("uri")
                            )
                        }
                    }
                    newPlaylists.add(Playlist(name, tracks))
                }
                playlists = newPlaylists
                Log.d(TAG, "Playlists updated: ${playlists.size}")
            }
            webSocketManager.on("pushSearch") { args: Array<Any> -> // Fixed: Array<Any>
                val data = args[0] as JSONObject
                val results = data.getJSONArray("results").let { array ->
                    List(array.length()) { idx ->
                        val track = array.getJSONObject(idx)
                        Track(
                            title = track.getString("title"),
                            artist = track.getString("artist"),
                            uri = track.getString("uri")
                        )
                    }
                }
                aiSuggestions = results
                Log.d(TAG, "Search results: ${results.size} tracks")
            }
        }
    }

    fun fetchPlaylists() {
        webSocketManager.emit("listPlaylist", JSONObject())
        Log.d(TAG, "Emitted listPlaylist")
    }

    fun createPlaylist(name: String) {
        webSocketManager.emit("createPlaylist", JSONObject().put("value", name))
        Log.d(TAG, "Emitted createPlaylist: $name")
    }

    fun addToPlaylist(playlistName: String, trackUri: String) {
        val data = JSONObject().apply {
            put("value", playlistName)
            put("uri", trackUri)
        }
        webSocketManager.emit("addToPlaylist", data)
        Log.d(TAG, "Emitted addToPlaylist: $playlistName, $trackUri")
    }

    fun removeFromPlaylist(playlistName: String, trackUri: String) {
        val data = JSONObject().apply {
            put("value", playlistName)
            put("uri", trackUri)
        }
        webSocketManager.emit("removeFromPlaylist", data)
        Log.d(TAG, "Emitted removeFromPlaylist: $playlistName, $trackUri")
    }

    fun playPlaylist(playlistName: String) {
        webSocketManager.emit("playPlaylist", JSONObject().put("value", playlistName))
        Log.d(TAG, "Emitted playPlaylist: $playlistName")
    }

    fun deletePlaylist(playlistName: String) {
        webSocketManager.emit("deletePlaylist", JSONObject().put("value", playlistName))
        Log.d(TAG, "Emitted deletePlaylist: $playlistName")
    }

    fun generateAiPlaylist(vibe: String) {
        viewModelScope.launch {
            try {
                // Call xAI API for vibe-based suggestions
                val xAiResponse = xAiApi.generatePlaylistSuggestions(vibe)
                val searchQuery = xAiResponse.suggestions.joinToString(" ") { it.query }

                // Search Volumio for tracks (local + TIDAL)
                val searchData = JSONObject().put("value", searchQuery)
                webSocketManager.emit("search", searchData)
                Log.d(TAG, "Emitted search for AI suggestions: $searchQuery")
            } catch (e: Exception) {
                Log.e(TAG, "AI playlist generation failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        webSocketManager.disconnect()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel) {
    var newPlaylistName by remember { mutableStateOf("") }

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
                // Create Playlist
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("New Playlist Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylist(newPlaylistName)
                            newPlaylistName = ""
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Create Playlist")
                }

                // Playlist List
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

                // Selected Playlist Tracks
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

                // AI Playlist Generation
                OutlinedTextField(
                    value = viewModel.vibeInput,
                    onValueChange = { viewModel.vibeInput = it },
                    label = { Text("Vibe/Mood (e.g., Chill, Hype)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                Button(
                    onClick = {
                        if (viewModel.vibeInput.isNotBlank()) {
                            viewModel.generateAiPlaylist(viewModel.vibeInput)
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Generate AI Playlist")
                }

                // AI Suggestions
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
                                        viewModel.addToPlaylist(playlist.name, track.uri)
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

// Data classes for xAI API response
data class XAIPResponse(val suggestions: List<Suggestion>)
data class Suggestion(val query: String)

// Hypothetical xAI API class (replace with actual implementation)
class XAiApi(apiKey: String) {
    suspend fun generatePlaylistSuggestions(vibe: String): XAIPResponse {
        // Implement xAI API call to get vibe-based suggestions
        return XAIPResponse(emptyList()) // Placeholder
    }
}