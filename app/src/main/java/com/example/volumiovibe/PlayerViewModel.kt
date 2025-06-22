package com.example.volumiovibe

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONObject

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    // Observable state for Compose UI
    var statusText by mutableStateOf("Volumio Status: connectin’…")
    var seekPosition by mutableStateOf(0f)
    var trackDuration by mutableStateOf(1f)
    var isPlaying by mutableStateOf(false)
    var currentTrackUri by mutableStateOf("")
    var playerReady by mutableStateOf(false)

    // For ticking the seekbar/timer
    private var tickJob: Job? = null

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive && isPlaying && playerReady) {
                delay(200)
                seekPosition += 0.2f
                if (seekPosition > trackDuration) seekPosition = trackDuration
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    init {
        WebSocketManager.onConnectionChange { isConnected ->
            viewModelScope.launch {
                if (!isConnected) {
                    Toast.makeText(context, "WebSocket ain’t connected, fam!", Toast.LENGTH_SHORT).show()
                    WebSocketManager.reconnect()
                } else {
                    Toast.makeText(context, "WebSocket connected, yo!", Toast.LENGTH_SHORT).show()
                    WebSocketManager.emit("getState")
                }
            }
        }
        WebSocketManager.on("pushState") { args ->
            viewModelScope.launch {
                try {
                    val state = args[0] as? JSONObject ?: return@launch
                    val status = state.getString("status")
                    val title = state.optString("title", "Nothin’ playin’")
                    val artist = state.optString("artist", "")
                    val seek = state.optLong("seek", -1).toFloat() / 1000f
                    val duration = state.optLong("duration", 1).toFloat().coerceAtLeast(1f)
                    val uri = state.optString("uri", "")
                    val isTrackChange = uri != currentTrackUri && uri.isNotEmpty()
                    if (isTrackChange) {
                        currentTrackUri = uri
                        seekPosition = 0f
                    }
                    isPlaying = (status == "play")
                    if (seek >= 0f || isTrackChange) {
                        seekPosition = if (seek >= 0f) seek else 0f
                    }
                    trackDuration = duration
                    statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
                    playerReady = uri.isNotEmpty()
                } catch (e: Exception) {
                    statusText = "Volumio Status: error - $e"
                    playerReady = false
                }

                // --- Start or stop tick job based on play state ---
                stopTicking()
                if (isPlaying && playerReady) startTicking()
            }
        }
    }
}
