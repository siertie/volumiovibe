package com.example.volumiovibe

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import org.json.JSONArray
import org.json.JSONObject

class SearchActivity : BaseActivity() {
    private val TAG = "VolumioSearchActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity created")
        WebSocketManager.initialize()
        setContent {
            SearchScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Triggering search with query=${SearchStateHolder.query}, results=${SearchStateHolder.results.size}")
        WebSocketManager.reconnect()
        if (SearchStateHolder.query.isNotBlank()) {
            CoroutineScope(Dispatchers.Main).launch {
                searchTracks(this@SearchActivity, SearchStateHolder.query) { newResults ->
                    SearchStateHolder.results = newResults
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Activity destroyed, isFinishing=$isFinishing")
    }

    @Composable
    fun SearchScreen() {
        var query by remember { mutableStateOf(SearchStateHolder.query) }
        var results by remember { mutableStateOf(SearchStateHolder.results) }
        var statusText by remember { mutableStateOf("Volumio Status: connectin’...") }
        var seekPosition by remember { mutableStateOf(0f) }
        var trackDuration by remember { mutableStateOf(1f) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentTrackUri by remember { mutableStateOf("") }
        var tickJob by remember { mutableStateOf<Job?>(null) }
        var ignoreSeekUpdates by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        LaunchedEffect(query) {
            SearchStateHolder.query = query
        }
        LaunchedEffect(results) {
            SearchStateHolder.results = results
        }
        LaunchedEffect(Unit) {
            Common.fetchState(context) { status: String, title: String, artist: String ->
                statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
            }
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
            WebSocketManager.on("pushState") { args ->
                try {
                    val state = args[0] as? JSONObject ?: return@on
                    val status = state.getString("status")
                    val title = state.optString("title", "Nothin’ playin’")
                    val artist = state.optString("artist", "")
                    val seek = state.optLong("seek", -1).toFloat() / 1000f
                    val duration = state.optLong("duration", 1).toFloat().coerceAtLeast(1f)
                    val uri = state.optString("uri", "")
                    tickJob?.cancel()
                    val isTrackChange = uri != currentTrackUri && uri.isNotEmpty()
                    if (isTrackChange) {
                        currentTrackUri = uri
                        seekPosition = 0f
                    }
                    isPlaying = (status == "play")
                    if (!ignoreSeekUpdates && (seek >= 0f || isTrackChange)) {
                        seekPosition = if (seek >= 0f) seek else 0f
                    }
                    trackDuration = duration
                    statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
                    if (isPlaying) {
                        tickJob = coroutineScope.launch {
                            while (isActive && isPlaying && seekPosition < trackDuration) {
                                delay(1000)
                                if (isPlaying && seekPosition < trackDuration) {
                                    seekPosition += 1f
                                }
                            }
                            if (seekPosition >= trackDuration) {
                                isPlaying = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Push state error: $e")
                    statusText = "Volumio Status: error - $e"
                }
            }
            if (WebSocketManager.waitForConnection()) {
                WebSocketManager.emit("getState")
            } else {
                WebSocketManager.reconnect()
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                tickJob?.cancel()
            }
        }

        Scaffold(
            bottomBar = {
                PlayerControls(
                    statusText = statusText,
                    seekPosition = seekPosition,
                    trackDuration = trackDuration,
                    isPlaying = isPlaying,
                    onPlay = {
                        coroutineScope.launch { Common.sendCommand(context, "play") }
                    },
                    onPause = {
                        coroutineScope.launch { Common.sendCommand(context, "pause") }
                    },
                    onNext = {
                        coroutineScope.launch { Common.sendCommand(context, "next") }
                    },
                    onPrevious = {
                        coroutineScope.launch { Common.sendCommand(context, "prev") }
                    },
                    onSeek = { newValue ->
                        seekPosition = newValue
                        coroutineScope.launch {
                            val seekSeconds = newValue.toInt()
                            ignoreSeekUpdates = true
                            WebSocketManager.emit("pause")
                            delay(500)
                            WebSocketManager.emit("seek", seekSeconds)
                            delay(500)
                            WebSocketManager.emit("play")
                            delay(4000)
                            ignoreSeekUpdates = false
                            delay(1000)
                            WebSocketManager.emit("getState")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { newQuery ->
                        query = newQuery
                        Log.d(TAG, "Updating query: $newQuery")
                    },
                    label = { Text("Search Tracks, Yo!") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            searchTracks(context, query) { newResults ->
                                results = newResults
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Search")
                }
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn {
                    items(results) { track ->
                        // specify at least one named arg other than 'track' so Kotlin picks the right overload
                        TrackItem(
                            track = track,
                            index = null,        // <— disambiguates
                            actionButtons = {
                                Button(onClick = { coroutineScope.launch { addToQueue(track) } }) {
                                    Text("Add")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun searchTracks(context: Context, query: String, onResultsReceived: (List<Track>) -> Unit) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Searchin’ for query=$query")
        if (query.isBlank()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Type somethin’, fam!", Toast.LENGTH_SHORT).show()
            }
            onResultsReceived(emptyList())
            return@withContext
        }

        var retries = 0
        while (retries < 3) {
            if (!WebSocketManager.waitForConnection()) {
                Log.e(TAG, "WebSocket ain’t connected for search (retry $retries)")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
                }
                WebSocketManager.reconnect()
                retries++
                delay(2000)
                continue
            }

            val payload = JSONObject().apply {
                put("value", query)
            }
            WebSocketManager.emit("search", payload) { args ->
                try {
                    val jsonObject = args[0] as? JSONObject ?: return@emit
                    val lists = jsonObject.optJSONObject("navigation")?.optJSONArray("lists") ?: return@emit
                    val results = mutableListOf<Track>()

                    for (i in 0 until lists.length()) {
                        val list = lists.getJSONObject(i)
                        if (list.optString("title").contains("Tracks")) {
                            val items = list.optJSONArray("items") ?: continue
                            for (j in 0 until items.length()) {
                                val item = items.getJSONObject(j)
                                if (item.optString("type") == "song" && item.has("title") && item.has("artist") && item.has("uri")) {
                                    results.add(
                                        Track(
                                            title = item.getString("title"),
                                            artist = item.getString("artist"),
                                            uri = item.getString("uri"),
                                            service = item.getString("service"),
                                            albumArt = item.optString("albumart", null)
                                        )
                                    )
                                }
                            }
                        }
                    }
                    Log.d(TAG, "Search response: Found ${results.size} tracks for query=$query")
                    onResultsReceived(results)
                } catch (e: Exception) {
                    Log.e(TAG, "Search parse error: $e")
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(context, "Search broke! $e", Toast.LENGTH_SHORT).show()
                    }
                    onResultsReceived(emptyList())
                }
            }
            Log.d(TAG, "Sent search (retry $retries)")
            return@withContext
        }
        Log.e(TAG, "Gave up searchin’ after $retries tries")
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Search failed after retries, fam!", Toast.LENGTH_SHORT).show()
        }
        onResultsReceived(emptyList())
    }

    private suspend fun addToQueue(track: Track) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.waitForConnection()) {
            Log.e(TAG, "WebSocket ain’t connected for addToQueue")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
            }
            WebSocketManager.reconnect()
            return@withContext
        }

        val payload = JSONObject().apply {
            put("uri", track.uri)
            put("service", track.service)
            put("title", track.title)
            put("artist", track.artist)
            put("type", "song")
        }
        WebSocketManager.emit("addToQueue", payload) { args ->
            CoroutineScope(Dispatchers.Main).launch {
                Log.d(TAG, "Add to queue response: ${args.joinToString()}")
                Toast.makeText(this@SearchActivity, "Added ${track.title}, yo!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

object SearchStateHolder {
    var query: String = ""
    var results: List<Track> = emptyList()
}