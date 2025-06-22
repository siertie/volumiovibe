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
                        Common.sendCommand(context, "play")
                        WebSocketManager.emit("getState")
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
                        Common.sendCommand(context, "pause")
                        WebSocketManager.emit("getState")
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
                        Common.sendCommand(context, "next")
                        WebSocketManager.emit("getState")
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
                        Common.sendCommand(context, "prev")
                        WebSocketManager.emit("getState")
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
                        WebSocketManager.emit("seek", newValue.toInt())
                        WebSocketManager.emit("getState")
                    }
                }
            }
        },
        modifier = modifier,
        controlsEnabled = playerReady,
        disabledReason = if (!playerReady) "Yo, no track loaded or player disconnected!" else null
    )
}

