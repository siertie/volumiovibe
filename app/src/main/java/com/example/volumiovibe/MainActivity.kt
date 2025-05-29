package com.example.volumiovibe

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val volumioUrl = "http://volumio.local:3000"
    private val client = OkHttpClient()
    private val TAG = "VolumioMainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebSocketManager.initialize()
        setContent { VolumioControlScreen() }
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketManager.disconnect()
    }

    @Composable
    fun VolumioControlScreen() {
        var statusText by remember { mutableStateOf("Volumio Status: connecting...") }
        var seekPosition by remember { mutableStateOf(0f) }
        var trackDuration by remember { mutableStateOf(1f) } // Avoid division by zero
        var isPlaying by remember { mutableStateOf(false) }
        var currentTrackUri by remember { mutableStateOf("") } // Track URI for changes
        var tickJob by remember { mutableStateOf<Job?>(null) }
        var ignoreSeekUpdates by remember { mutableStateOf(false) } // Ignore null seeks after drag
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        // Format seconds to MM:SS
        fun formatTime(seconds: Float): String {
            val mins = (seconds / 60).toInt()
            val secs = (seconds % 60).toInt()
            return String.format("%d:%02d", mins, secs)
        }

        LaunchedEffect(Unit) {
            // Default to REST for initial state
            fetchStateFallback { status, title, artist ->
                statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
            }

            // Log WebSocket connection state
            Log.d(TAG, "WebSocket connected: ${WebSocketManager.isConnected()}")

            // Monitor WebSocket connection
            WebSocketManager.onConnectionChange { isConnected ->
                statusText = if (!isConnected) {
                    "Volumio Status: disconnected"
                } else {
                    statusText // Keep current status if reconnected
                }
                if (!isConnected) {
                    Log.w(TAG, "WebSocket disconnected, attempting reconnect")
                    WebSocketManager.reconnect()
                } else {
                    Log.d(TAG, "WebSocket reconnected successfully")
                }
            }

            // Set up pushState listener for incoming events
            WebSocketManager.on("pushState") { args ->
                try {
                    val state = args[0] as? JSONObject ?: return@on
                    val status = state.getString("status")
                    val title = state.optString("title", "Nothin’ playin’")
                    val artist = state.optString("artist", "")
                    val seek = state.optLong("seek", -1).toFloat() / 1000f // Convert ms to seconds, -1 for null
                    val duration = state.optLong("duration", 1).toFloat().coerceAtLeast(1f) // Avoid zero
                    val uri = state.optString("uri", "")
                    val trackType = state.optString("trackType", "")

                    // Cancel existing tick job
                    tickJob?.cancel()
                    Log.d(TAG, "Cancelled previous tickJob")

                    // Detect track change
                    val isTrackChange = uri != currentTrackUri && uri.isNotEmpty()
                    if (isTrackChange) {
                        Log.d(TAG, "Track changed: oldUri=$currentTrackUri, newUri=$uri")
                        currentTrackUri = uri
                        seekPosition = 0f // Reset on track change
                    }

                    // Update isPlaying first
                    val playing = (status == "play")
                    isPlaying = playing
                    Log.d(TAG, "Set isPlaying=$isPlaying for status=$status, trackType=$trackType")

                    // Update seekPosition only if seek is valid or track changed
                    if (!ignoreSeekUpdates && (seek >= 0f || isTrackChange)) {
                        seekPosition = if (seek >= 0f) seek else 0f
                    } else if (ignoreSeekUpdates) {
                        Log.d(TAG, "Ignored seek update: seek=$seek")
                    }

                    // Update other state atomically
                    trackDuration = duration
                    statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
                    Log.d(TAG, "PushState: status=$status, title=$title, artist=$artist, seek=${seek}s, duration=${duration}s, isPlaying=$isPlaying, trackType=$trackType")

                    // Start new tick job if playing
                    if (playing) {
                        tickJob = coroutineScope.launch {
                            while (isActive && isPlaying && seekPosition < trackDuration) {
                                delay(1000)
                                if (isPlaying && seekPosition < trackDuration) {
                                    seekPosition += 1f
                                    Log.d(TAG, "Ticked seekPosition: ${seekPosition}s")
                                }
                            }
                            if (seekPosition >= trackDuration) {
                                isPlaying = false
                                Log.d(TAG, "Stopped ticking: seekPosition=${seekPosition}s, trackDuration=${trackDuration}s")
                            }
                        }
                        Log.d(TAG, "Started new tickJob")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Push state error: $e")
                    statusText = "Volumio Status: error - $e"
                }
            }

            // Initial getState to trigger pushState
            if (WebSocketManager.isConnected()) {
                WebSocketManager.emit("getState")
                Log.d(TAG, "Sent initial getState")
            }
        }

        // Clean up when composable leaves composition
        DisposableEffect(Unit) {
            onDispose {
                tickJob?.cancel()
                Log.d(TAG, "Disposed tickJob")
            }
        }

        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                // Seekbar for track progress (draggable)
                Slider(
                    value = seekPosition,
                    onValueChange = { newValue ->
                        seekPosition = newValue // Update locally during drag
                    },
                    onValueChangeFinished = {
                        // Send seek command to Volumio on drag end
                        coroutineScope.launch {
                            val seekSeconds = seekPosition.toInt() // Round to integer seconds
                            val intendedSeek = seekPosition
                            Log.d(TAG, "Sending seek: $seekSeconds")
                            WebSocketManager.emit("seek", seekSeconds) // Send raw integer
                            Log.d(TAG, "Sent seek command: $seekSeconds s")

                            // Ignore null seek updates for 4 seconds
                            ignoreSeekUpdates = true
                            launch {
                                delay(4000)
                                ignoreSeekUpdates = false
                                Log.d(TAG, "Stopped ignoring seek updates")
                            }

                            // Pause before seeking to stabilize state
                            WebSocketManager.emit("pause")
                            Log.d(TAG, "Sent pause before seek")
                            delay(500)

                            // Send seek command
                            WebSocketManager.emit("seek", seekSeconds)
                            Log.d(TAG, "Sent seek command: $seekSeconds s")

                            // Resume playback
                            delay(500)
                            WebSocketManager.emit("play")
                            Log.d(TAG, "Sent play after seek")

                            // Retry seek up to 2 times if no valid response
                            repeat(2) { attempt ->
                                delay(1000)
                                if (seekPosition < 2f) {
                                    WebSocketManager.emit("seek", seekSeconds)
                                    Log.d(TAG, "Retried seek command (attempt ${attempt + 1}): $seekSeconds s")
                                    delay(500)
                                }
                            }

                            // Force getState to fetch correct seek
                            delay(1000)
                            WebSocketManager.emit("getState")
                            Log.d(TAG, "Sent getState after seek")
                        }
                    },
                    valueRange = 0f..trackDuration,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    enabled = true // Enable dragging
                )
                Text(
                    text = "${formatTime(seekPosition)} / ${formatTime(trackDuration)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    coroutineScope.launch {
                        sendCommand("play")
                    }
                }) {
                    Text("Play")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    coroutineScope.launch {
                        sendCommand("pause")
                    }
                }) {
                    Text("Pause")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    coroutineScope.launch {
                        sendCommand("next")
                    }
                }) {
                    Text("Next")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    coroutineScope.launch {
                        sendCommand("prev")
                    }
                }) {
                    Text("Previous")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    coroutineScope.launch {
                        if (WebSocketManager.isConnected()) {
                            WebSocketManager.emit("getState")
                            Log.d(TAG, "Sent manual getState")
                        } else {
                            fetchStateFallback { status, title, artist ->
                                statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
                            }
                        }
                    }
                }) {
                    Text("Refresh Status")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    context.startActivity(Intent(context, SearchActivity::class.java))
                }) {
                    Text("Search")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    context.startActivity(Intent(context, QueueActivity::class.java))
                }) {
                    Text("Queue")
                }
            }
        }
    }

    private suspend fun sendCommand(command: String) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for $command")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            sendCommandFallback(command)
            return@withContext
        }

        WebSocketManager.emit(command, null) { args ->
            if (command == "play") {
                Toast.makeText(this@MainActivity, "Playin’, yo!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun sendCommandFallback(command: String) = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/commands/?cmd=$command"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    Log.d(TAG, "REST command: $command")
                    if (command == "play") {
                        Toast.makeText(this@MainActivity, "Playin’, yo!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e(TAG, "REST command failed: ${response.code}")
                    Toast.makeText(this@MainActivity, "$command fucked up!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Log.e(TAG, "REST command error: $e")
                Toast.makeText(this@MainActivity, "$command broke, fam! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun fetchStateFallback(onStateReceived: (String, String, String) -> Unit) = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/getState"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext
                val jsonObject = JSONObject(json)
                val status = jsonObject.getString("status")
                val title = jsonObject.optString("title", "Nothin’ playin’")
                val artist = jsonObject.optString("artist", "")
                withContext(Dispatchers.Main) {
                    onStateReceived(status, title, artist)
                    Log.d(TAG, "REST state fetched: $status, $title by $artist")
                }
            } else {
                Log.e(TAG, "REST state fetch failed: ${response.code}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "REST state fetch fucked up!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST state fetch error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "REST state fetch broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }
}