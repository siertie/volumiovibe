package com.example.volumiovibe

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object Common {
    private const val volumioUrl = "http://volumio.local:3000"
    private val client = OkHttpClient()
    private const val TAG = "VolumioCommon"

    suspend fun sendCommand(context: Context, command: String) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for $command")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            sendCommandFallback(context, command)
            return@withContext
        }

        WebSocketManager.emit(command, null) { args ->
            if (command == "play") {
                Toast.makeText(context, "Playin’, yo!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun sendCommandFallback(context: Context, command: String) = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/commands/?cmd=$command"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    Log.d(TAG, "REST command: $command")
                    if (command == "play") {
                        Toast.makeText(context, "Playin’, yo!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e(TAG, "REST command failed: ${response.code}")
                    Toast.makeText(context, "$command fucked up!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Log.e(TAG, "REST command error: $e")
                Toast.makeText(context, "$command broke, fam! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun fetchStateFallback(context: Context, onStateReceived: (String, String, String) -> Unit) = withContext(Dispatchers.IO) {
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
                    Toast.makeText(context, "REST state fetch fucked up!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST state fetch error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "REST state fetch broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }
}