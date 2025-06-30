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

    var lastPushStateTime by mutableStateOf(System.currentTimeMillis())

    // Declare tickJob as a class property
    private var tickJob: Job? = null

    fun maybeReconnectIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastPushStateTime > 10_000) { // 10 seconds
            WebSocketManager.reconnect()
            CoroutineScope(Dispatchers.Main).launch {
                repeat(3) {
                    delay(800)
                    if (WebSocketManager.isConnected()) {
                        WebSocketManager.emit("getState")
                        return@launch
                    }
                }
            }
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var lastCheck = System.currentTimeMillis()
            while (isActive && isPlaying && playerReady) {
                delay(200)
                seekPosition += 0.2f
                if (seekPosition > trackDuration) seekPosition = trackDuration
                val now = System.currentTimeMillis()
                if (now - lastCheck >= 5000) {
                    checkForStaleState()
                    lastCheck = now
                }
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
    }

    fun checkForStaleState() {
        val now = System.currentTimeMillis()
        if (now - lastPushStateTime > 10_000) { // 10 secs since last pushState
            if (WebSocketManager.isConnected()) {
                WebSocketManager.emit("getState")
            } else {
                WebSocketManager.reconnect()
                CoroutineScope(Dispatchers.Main).launch {
                    repeat(3) {
                        delay(800)
                        if (WebSocketManager.isConnected()) {
                            WebSocketManager.emit("getState")
                            return@launch
                        }
                    }
                }
            }
        }
    }

    init {
        WebSocketManager.emit("getState")
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
                    lastPushStateTime = System.currentTimeMillis()
                    currentTrackUri = uri
                    seekPosition = if (seek >= 0f) seek else 0f
                    trackDuration = duration
                    isPlaying = (status == "play")
                    statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
                    playerReady = uri.isNotEmpty()
                    if (uri.isEmpty()) {
                        Log.w("PlayerViewModel", "pushState got empty URI: ${args.joinToString()}")
                    }
                } catch (e: Exception) {
                    statusText = "Volumio Status: error - $e"
                    playerReady = false
                    Log.e("PlayerViewModel", "pushState error: $e")
                }
                stopTicking()
                if (isPlaying && playerReady) startTicking()
            }
        }
    }
}