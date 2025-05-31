package com.example.volumiovibe

import android.content.Intent
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

class QueueActivity : ComponentActivity() {
    private val volumioUrl = "http://volumio.local:3000"
    private val client = OkHttpClient()
    private val TAG = "VolumioQueueActivity"
    private var refreshQueueCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var keepSplash = true
        installSplashScreen().setKeepOnScreenCondition { keepSplash }
        CoroutineScope(Dispatchers.Main).launch {
            WebSocketManager.initialize()
            if (WebSocketManager.waitForConnection()) {
                Log.d(TAG, "WebSocket connected")
            } else {
                Log.d(TAG, "WebSocket failed, using REST fallback")
            }
            keepSplash = false
        }
        setContent {
            VolumioTheme {
                QueueScreen { refreshQueueCallback = it }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Checking WebSocket")
        WebSocketManager.reconnect()
        refreshQueueCallback?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Activity destroyed, isFinishing=$isFinishing")
    }

    @Composable
    fun QueueScreen(onRefreshCallback: (() -> Unit) -> Unit) {
        var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            onRefreshCallback {
                coroutineScope.launch {
                    if (WebSocketManager.waitForConnection()) {
                        fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
                    } else {
                        fetchQueueFallback { newQueue -> queue = newQueue }
                    }
                }
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
            // Fetch queue directly
            if (WebSocketManager.isConnected()) {
                fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
            } else {
                fetchQueueFallback { newQueue -> queue = newQueue }
            }
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
                        clearQueue(coroutineScope)
                        fetchQueue(coroutineScope) { newQueue ->
                            queue = newQueue
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Queue")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    Log.d(TAG, "Navigating to SearchActivity with CLEAR_TOP | SINGLE_TOP")
                    context.startActivity(Intent(context, SearchActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Go to Search")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    context.startActivity(Intent(context, PlaylistActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Go to Playlist")
            }
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn {
                itemsIndexed(queue) { index, track ->
                    QueueItem(
                        track = track,
                        index = index,
                        onPlay = {
                            coroutineScope.launch {
                                playTrack(index, coroutineScope)
                            }
                        },
                        onRemove = {
                            coroutineScope.launch {
                                removeFromQueue(index, coroutineScope)
                                fetchQueue(coroutineScope) { newQueue ->
                                    queue = newQueue
                                }
                            }
                        },
                        onMoveUp = if (index > 0) {
                            {
                                coroutineScope.launch {
                                    moveTrack(index, index - 1, coroutineScope)
                                    fetchQueue(coroutineScope) { newQueue ->
                                        queue = newQueue
                                    }
                                }
                            }
                        } else null,
                        onMoveDown = if (index < queue.size - 1) {
                            {
                                coroutineScope.launch {
                                    moveTrack(index, index + 1, coroutineScope)
                                    fetchQueue(coroutineScope) { newQueue ->
                                        queue = newQueue
                                    }
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
        // Log albumArt for debugging
        Log.d("VolumioQueueActivity", "Track: ${track.title}, AlbumArt: ${track.albumArt}")

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onPlay() }
                .clip(RoundedCornerShape(16.dp)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Album art
                    val albumArtUrl = when {
                        track.albumArt.isNullOrEmpty() -> "https://via.placeholder.com/64"
                        track.albumArt.startsWith("http") -> track.albumArt
                        else -> "http://volumio.local:3000${track.albumArt}"
                    }
                    AsyncImage(
                        model = albumArtUrl,
                        contentDescription = "Album Art",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        onError = { Log.e("VolumioQueueActivity", "Failed to load album art: $albumArtUrl") }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "${index + 1}. ${track.title}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0B0B0)
                        )
                    }
                }
                Row {
                    if (onMoveUp != null) {
                        IconButton(onClick = onMoveUp) {
                            Text("↑", color = Color(0xFF03DAC6))
                        }
                    }
                    if (onMoveDown != null) {
                        IconButton(onClick = onMoveDown) {
                            Text("↓", color = Color(0xFF03DAC6))
                        }
                    }
                    IconButton(onClick = onRemove) {
                        Text("X", color = Color(0xFFFF5555))
                    }
                }
            }
        }
    }

    private suspend fun fetchQueue(scope: CoroutineScope, onQueueReceived: (List<Track>) -> Unit) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for fetchQueue")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            fetchQueueFallback(onQueueReceived)
            return@withContext
        }

        WebSocketManager.emit("getQueue", null) { args ->
            try {
                val queueArray = args[0] as? JSONArray ?: return@emit
                val results = mutableListOf<Track>()
                for (i in 0 until queueArray.length()) {
                    val item = queueArray.getJSONObject(i)
                    if (item.has("name") && item.has("artist") && item.has("uri")) {
                        results.add(
                            Track(
                                title = item.getString("name"),
                                artist = item.getString("artist"),
                                uri = item.getString("uri"),
                                service = item.getString("service"),
                                albumArt = item.optString("albumart", null) // Grab albumart
                            )
                        )
                    }
                }
                Log.d(TAG, "Received queue: $queueArray")
                onQueueReceived(results)
            } catch (e: Exception) {
                Log.e(TAG, "Queue parse error: $e")
                scope.launch {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@QueueActivity, "Queue fetch broke! $e", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private suspend fun fetchQueueFallback(onQueueReceived: (List<Track>) -> Unit) = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/getQueue"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "REST queue fetch failed: ${response.code}")
                withContext(Main) {
                    Toast.makeText(this@QueueActivity, "REST queue fetch fucked up!", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val json = response.body?.string() ?: return@withContext
            Log.d(TAG, "REST queue response: $json")
            val results = mutableListOf<Track>()
            val jsonObject = JSONObject(json)
            val queueArray = jsonObject.optJSONArray("queue") ?: return@withContext

            for (i in 0 until queueArray.length()) {
                val item = queueArray.getJSONObject(i)
                if (item.has("name") && item.has("artist") && item.has("uri")) {
                    results.add(
                        Track(
                            title = item.getString("name"),
                            artist = item.getString("artist"),
                            uri = item.getString("uri"),
                            service = item.getString("service"),
                            albumArt = item.optString("albumart", null) // Grab albumart
                        )
                    )
                }
            }
            withContext(Main) {
                onQueueReceived(results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST queue fetch error: $e")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "REST queue fetch broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun clearQueue(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for clearQueue")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            clearQueueFallback()
            return@withContext
        }

        WebSocketManager.emit("clearQueue", null) { args ->
            Log.d(TAG, "Clear queue response: ${args.joinToString()}")
            scope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Queue cleared, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun clearQueueFallback() = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/commands/?cmd=clearQueue"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            Log.d(TAG, "REST clear queue response: $responseBody")
            if (!response.isSuccessful) {
                Log.e(TAG, "REST clear queue failed: ${response.code}")
                withContext(Main) {
                    Toast.makeText(this@QueueActivity, "REST clear queue fucked up!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST clear queue error: $e")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "REST clear queue broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun playTrack(index: Int, scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for playTrack")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            playTrackFallback(index)
            return@withContext
        }

        val payload = JSONObject().apply {
            put("value", index)
        }
        WebSocketManager.emit("play", payload) { args ->
            Log.d(TAG, "Play response: ${args.joinToString()}")
            scope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Playin’ track $index, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun playTrackFallback(index: Int) = withContext(Dispatchers.IO) {
        val queue = fetchQueueFallbackSync()
        if (queue.isEmpty() || index < 0 || index >= queue.size) return@withContext

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
            Log.d(TAG, "REST play track response: $responseBody")
            if (!response.isSuccessful) {
                Log.e(TAG, "REST play track failed: ${response.code}")
                withContext(Main) {
                    Toast.makeText(this@QueueActivity, "REST play track fucked up!", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Main) {
                    Toast.makeText(this@QueueActivity, "Playin’ ${queue[index].title}, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST play track error: $e")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "REST play track broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun removeFromQueue(index: Int, scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for removeFromQueue")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            removeFromQueueFallback(index)
            return@withContext
        }

        val payload = JSONObject().apply {
            put("value", index)
        }
        WebSocketManager.emit("removeFromQueue", payload) { args ->
            Log.d(TAG, "Remove response: ${args.joinToString()}")
            scope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Removed track, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun removeFromQueueFallback(index: Int) = withContext(Dispatchers.IO) {
        val queue = fetchQueueFallbackSync()
        if (index < 0 || index >= queue.size) return@withContext
        queue.removeAt(index)
        updateQueueFallback(queue)
    }

    private suspend fun moveTrack(fromIndex: Int, toIndex: Int, scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for moveTrack")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            moveTrackFallback(fromIndex, toIndex)
            return@withContext
        }

        val payload = JSONObject().apply {
            put("from", fromIndex)
            put("to", toIndex)
        }
        WebSocketManager.emit("moveQueue", payload) { args ->
            Log.d(TAG, "Received pushQueue: ${args.joinToString()}")
            scope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Moved track, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun moveTrackFallback(fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        val queue = fetchQueueFallbackSync()
        if (fromIndex < 0 || fromIndex >= queue.size || toIndex < 0 || toIndex >= queue.size) return@withContext
        val track = queue.removeAt(fromIndex)
        queue.add(toIndex, track)
        updateQueueFallback(queue)
    }

    private suspend fun updateQueueFallback(queue: List<Track>) = withContext(Dispatchers.IO) {
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
            Log.d(TAG, "REST update queue response: $responseBody")
            if (!response.isSuccessful) {
                Log.e(TAG, "REST update queue failed: ${response.code}")
                withContext(Main) {
                    Toast.makeText(this@QueueActivity, "REST queue update fucked up!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST update queue error: $e")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "REST queue update broke! $e", Toast.LENGTH_SHORT).show()
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

    private suspend fun fetchQueueFallbackSync(): MutableList<Track> = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/getQueue"
        val request = Request.Builder().url(url).build()
        val results = mutableListOf<Track>()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "REST queue fetch failed: ${response.code}")
                return@withContext results
            }

            val json = response.body?.string() ?: return@withContext results
            val jsonObject = JSONObject(json)
            val queueArray = jsonObject.optJSONArray("queue") ?: return@withContext results

            for (i in 0 until queueArray.length()) {
                val item = queueArray.getJSONObject(i)
                if (item.has("name") && item.has("artist") && item.has("uri")) {
                    results.add(
                        Track(
                            title = item.getString("name"),
                            artist = item.getString("artist"),
                            uri = item.getString("uri"),
                            service = item.getString("service"),
                            albumArt = item.optString("albumart", null) // Grab albumart
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST queue fetch error: $e")
        }
        results
    }

    data class Track(
        val title: String,
        val artist: String,
        val uri: String,
        val service: String,
        val albumArt: String? // Add albumArt field
    )
}