package com.example.volumiovibe

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException

class QueueActivity : ComponentActivity() {
    private val volumioUrl = "http://volumio.local:3000"
    private val client = OkHttpClient()
    private var socket: Socket? = null
    private val TAG = "VolumioQueueActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QueueScreen() }
        connectWebSocket()
    }

    private fun connectWebSocket() {
        try {
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 1000
                transports = arrayOf("websocket")
                query = "EIO=3"
            }
            socket = IO.socket(volumioUrl, opts)
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "WebSocket connected")
                runOnUiThread {
                    Toast.makeText(this, "WebSocket connected, yo!", Toast.LENGTH_SHORT).show()
                }
            }
            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "WebSocket disconnected: ${it.joinToString()}")
            }
            socket?.on(Socket.EVENT_CONNECT_ERROR) {
                Log.e(TAG, "WebSocket error: ${it.joinToString()}")
                runOnUiThread {
                    Toast.makeText(this, "WebSocket connect failed!", Toast.LENGTH_SHORT).show()
                }
            }
            socket?.connect()
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid WebSocket URL: $e")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        socket?.disconnect()
        socket?.off()
        Log.d(TAG, "WebSocket disconnected on destroy")
    }

    @Composable
    fun QueueScreen() {
        var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            queue = fetchQueue()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    coroutineScope.launch {
                        clearQueue()
                        queue = fetchQueue()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Queue")
            }
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn {
                itemsIndexed(queue) { index, track ->
                    QueueItem(
                        track = track,
                        index = index,
                        onPlay = {
                            coroutineScope.launch {
                                playTrack(index, queue)
                            }
                        },
                        onRemove = {
                            coroutineScope.launch {
                                removeFromQueue(index)
                                queue = fetchQueue()
                            }
                        },
                        onMoveUp = if (index > 0) {
                            {
                                coroutineScope.launch {
                                    moveTrack(index, index - 1)
                                    queue = fetchQueue()
                                }
                            }
                        } else null,
                        onMoveDown = if (index < queue.size - 1) {
                            {
                                coroutineScope.launch {
                                    moveTrack(index, index + 1)
                                    queue = fetchQueue()
                                }
                            }
                        } else null
                    )
                }
            }
        }
    }

    @Composable
    fun QueueItem(
        track: Track,
        index: Int,
        onPlay: () -> Unit,
        onRemove: () -> Unit,
        onMoveUp: (() -> Unit)?,
        onMoveDown: (() -> Unit)?
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay() }
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${index + 1}. ${track.title}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row {
                    if (onMoveUp != null) {
                        IconButton(onClick = onMoveUp) {
                            Text("↑")
                        }
                    }
                    if (onMoveDown != null) {
                        IconButton(onClick = onMoveDown) {
                            Text("↓")
                        }
                    }
                    IconButton(onClick = onRemove) {
                        Text("X")
                    }
                }
            }
        }
    }

    private suspend fun fetchQueue(): List<Track> = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/getQueue"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Queue fetch failed: ${response.code}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Queue fetch fucked up!", Toast.LENGTH_SHORT).show()
                }
                return@withContext emptyList()
            }

            val json = response.body?.string() ?: return@withContext emptyList()
            Log.d(TAG, "Queue response: $json")
            val results = mutableListOf<Track>()
            val jsonObject = JSONObject(json)
            val queueArray = jsonObject.optJSONArray("queue") ?: return@withContext emptyList()

            for (i in 0 until queueArray.length()) {
                val item = queueArray.getJSONObject(i)
                if (item.has("name") && item.has("artist") && item.has("uri")) {
                    results.add(
                        Track(
                            title = item.getString("name"),
                            artist = item.getString("artist"),
                            uri = item.getString("uri"),
                            service = item.getString("service")
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Queue fetch error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@QueueActivity, "Queue fetch broke! $e", Toast.LENGTH_LONG).show()
            }
            emptyList()
        }
    }

    private suspend fun clearQueue() = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/commands/?cmd=clearQueue"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            Log.d(TAG, "Clear queue response: $responseBody")
            if (!response.isSuccessful) {
                Log.e(TAG, "Clear queue failed: ${response.code}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Clear queue fucked up!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clear queue error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@QueueActivity, "Clear queue broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun playTrack(index: Int, queue: List<Track>) = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/replaceAndPlay"
        val payload = JSONObject().apply {
            put("list", JSONArray().apply {
                queue.forEach { track ->
                    put(JSONObject().apply {
                        put("uri", track.uri)
                        put("service", track.service)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("type", "song")
                    })
                }
            })
            put("index", index)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            Log.d(TAG, "Play track response: $responseBody")
            if (!response.isSuccessful) {
                Log.e(TAG, "Play track failed: ${response.code}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Play track fucked up!", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Playin’ ${queue[index].title}, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Play track error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@QueueActivity, "Play track broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun removeFromQueue(index: Int) = withContext(Dispatchers.IO) {
        val queue = fetchQueue().toMutableList()
        if (index < 0 || index >= queue.size) return@withContext
        queue.removeAt(index)
        updateQueue(queue)
    }

    private suspend fun moveTrack(fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        if (socket?.connected() != true) {
            Log.e(TAG, "WebSocket not connected")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@QueueActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        val payload = JSONObject().apply {
            put("from", fromIndex)
            put("to", toIndex)
        }
        socket?.emit("moveQueue", payload)
        Log.d(TAG, "Emitted moveQueue: from=$fromIndex, to=$toIndex")

        socket?.once("pushQueue") { args ->
            CoroutineScope(Dispatchers.Main).launch {
                Log.d(TAG, "Received pushQueue: ${args.joinToString()}")
                Toast.makeText(this@QueueActivity, "Moved track, yo!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun updateQueue(queue: List<Track>) = withContext(Dispatchers.IO) {
        val currentUri = getCurrentTrackUri()
        var playIndex = 0
        if (currentUri != null) {
            queue.forEachIndexed { index, track ->
                if (track.uri == currentUri) {
                    playIndex = index
                }
            }
        }

        val url = "$volumioUrl/api/v1/replaceAndPlay"
        val payload = JSONObject().apply {
            put("list", JSONArray().apply {
                queue.forEach { track ->
                    put(JSONObject().apply {
                        put("uri", track.uri)
                        put("service", track.service)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("type", "song")
                    })
                }
            })
            put("index", playIndex)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            Log.d(TAG, "Update queue response: $responseBody")
            if (!response.isSuccessful) {
                Log.e(TAG, "Update queue failed: ${response.code}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Queue update fucked up!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update queue error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@QueueActivity, "Queue update broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun getCurrentTrackUri(): String? = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/getState"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Get state failed: ${response.code}")
                return@withContext null
            }
            val json = response.body?.string() ?: return@withContext null
            val jsonObject = JSONObject(json)
            return@withContext jsonObject.optString("uri", null)
        } catch (e: Exception) {
            Log.e(TAG, "Get state error: $e")
            return@withContext null
        }
    }

    data class Track(
        val title: String,
        val artist: String,
        val uri: String,
        val service: String
    )
}