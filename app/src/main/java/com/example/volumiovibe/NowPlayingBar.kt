package com.example.volumiovibe

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import androidx.compose.ui.unit.dp

// --- DRY Helper ---
suspend fun runPlayerCommandWithSync(
    context: android.content.Context,
    playerViewModel: PlayerViewModel,
    command: suspend () -> Unit
) {
    playerViewModel.maybeReconnectIfStale()
    command()
    WebSocketManager.emit("getState") // Just once, no waiting!
}

@Composable
fun NowPlayingBar(
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val statusText = playerViewModel.statusText
    val seekPosition = playerViewModel.seekPosition
    val trackDuration = playerViewModel.trackDuration
    val isPlaying = playerViewModel.isPlaying
    val playerReady = playerViewModel.playerReady

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var infinityMode by remember { mutableStateOf(false) }

    PlayerControls(
        statusText = statusText,
        seekPosition = seekPosition,
        trackDuration = trackDuration,
        isPlaying = isPlaying,
        onPlay = {
            when {
                !WebSocketManager.isConnected() -> {
                    Toast.makeText(context, "WebSocket dead, fam! Tryna reconnect...", Toast.LENGTH_SHORT).show()
                    WebSocketManager.reconnect()
                }
                !playerReady -> {
                    Toast.makeText(context, "Yo, player ain't ready!", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    coroutineScope.launch {
                        runPlayerCommandWithSync(context, playerViewModel) {
                            Common.sendCommand(context, "play")
                        }
                    }
                }
            }
        },
        onPause = {
            when {
                !WebSocketManager.isConnected() -> {
                    Toast.makeText(context, "WebSocket dead, fam! Tryna reconnect...", Toast.LENGTH_SHORT).show()
                    WebSocketManager.reconnect()
                }
                !playerReady -> {
                    Toast.makeText(context, "Yo, player ain't ready!", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    coroutineScope.launch {
                        runPlayerCommandWithSync(context, playerViewModel) {
                            Common.sendCommand(context, "pause")
                        }
                    }
                }
            }
        },
        onNext = {
            when {
                !WebSocketManager.isConnected() -> {
                    Toast.makeText(context, "WebSocket dead, fam! Tryna reconnect...", Toast.LENGTH_SHORT).show()
                    WebSocketManager.reconnect()
                }
                !playerReady -> {
                    Toast.makeText(context, "Yo, player ain't ready!", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    coroutineScope.launch {
                        runPlayerCommandWithSync(context, playerViewModel) {
                            Common.sendCommand(context, "next")
                        }
                    }
                }
            }
        },
        onPrevious = {
            when {
                !WebSocketManager.isConnected() -> {
                    Toast.makeText(context, "WebSocket dead, fam! Tryna reconnect...", Toast.LENGTH_SHORT).show()
                    WebSocketManager.reconnect()
                }
                !playerReady -> {
                    Toast.makeText(context, "Yo, player ain't ready!", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    coroutineScope.launch {
                        runPlayerCommandWithSync(context, playerViewModel) {
                            Common.sendCommand(context, "prev")
                        }
                    }
                }
            }
        },
        onSeek = { newValue ->
            coroutineScope.launch {
                when {
                    !WebSocketManager.isConnected() -> {
                        Toast.makeText(context, "WebSocket dead, fam! Tryna reconnect...", Toast.LENGTH_SHORT).show()
                        WebSocketManager.reconnect()
                    }
                    !playerReady -> {
                        Toast.makeText(context, "Yo, player ain't ready!", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        runPlayerCommandWithSync(context, playerViewModel) {
                            WebSocketManager.emit("seek", newValue.toInt())
                        }
                    }
                }
            }
        },
        onToggleInfinity = {
            infinityMode = !infinityMode
            WebSocketManager.emit("enableDynamicMode", infinityMode)
        },
        infinityMode = infinityMode,
        modifier = modifier,
        controlsEnabled = playerReady,
        disabledReason = if (!playerReady) "Yo, no track loaded or player disconnected!" else null
    )
}
