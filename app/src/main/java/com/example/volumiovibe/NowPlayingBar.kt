package com.example.volumiovibe

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import org.json.JSONObject
import androidx.compose.ui.unit.dp

@Composable
fun NowPlayingBar(
    modifier: Modifier = Modifier
) {
    var statusText by remember { mutableStateOf("Volumio Status: connectin’...") }
    var seekPosition by remember { mutableStateOf(0f) }
    var trackDuration by remember { mutableStateOf(1f) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTrackUri by remember { mutableStateOf("") }
    var tickJob by remember { mutableStateOf<Job?>(null) }
    var ignoreSeekUpdates by remember { mutableStateOf(false) }
    var playerReady by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        WebSocketManager.onConnectionChange { isConnected ->
            coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    if (!isConnected) {
                        Toast.makeText(context, "WebSocket ain’t connected, fam!", Toast.LENGTH_SHORT).show()
                        WebSocketManager.reconnect()
                    } else {
                        Toast.makeText(context, "WebSocket connected, yo!", Toast.LENGTH_SHORT).show()
                        WebSocketManager.emit("getState")
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
                playerReady = uri.isNotEmpty()
            } catch (e: Exception) {
                statusText = "Volumio Status: error - $e"
                playerReady = false
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

    if (playerReady) {
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
            },
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(88.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}
