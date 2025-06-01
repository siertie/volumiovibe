package com.example.volumiovibe

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object Common {
    private const val TAG = "VolumioCommon"

    suspend fun sendCommand(context: Context, command: String) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.waitForConnection()) {
            Log.e(TAG, "WebSocket ain’t connected for $command")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
            }
            WebSocketManager.reconnect()
            return@withContext
        }

        WebSocketManager.emit(command, null) { args ->
            CoroutineScope(Dispatchers.Main).launch {
                if (command == "play") {
                    Toast.makeText(context, "Playin’, yo!", Toast.LENGTH_SHORT).show()
                }
                Log.d(TAG, "Command $command response: ${args.joinToString()}")
            }
        }
    }

    suspend fun fetchState(context: Context, onStateReceived: (String, String, String) -> Unit) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.waitForConnection()) {
            Log.e(TAG, "WebSocket ain’t connected for fetchState")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
            }
            WebSocketManager.reconnect()
            return@withContext
        }

        WebSocketManager.emit("getState", null) { args ->
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val state = args[0] as? JSONObject ?: return@launch
                    val status = state.getString("status")
                    val title = state.optString("title", "Nothin’ playin’")
                    val artist = state.optString("artist", "")
                    onStateReceived(status, title, artist)
                    Log.d(TAG, "WebSocket state fetched: $status, $title by $artist")
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket state parse error: $e")
                    Toast.makeText(context, "State fetch fucked up! $e", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}