package com.example.volumiovibe

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class SearchActivity : ComponentActivity() {
    private val volumioUrl = "http://volumio.local:3000"
    private val client = OkHttpClient()
    private val TAG = "VolumioSearchActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SearchScreen() }
    }

    @Composable
    fun SearchScreen() {
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<Track>>(emptyList()) }
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search Tracks, Yo!") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    coroutineScope.launch {
                        searchTracks(query) { newResults ->
                            results = newResults
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Search")
            }
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn {
                items(results) { track ->
                    TrackItem(track = track, onAddToQueue = {
                        coroutineScope.launch {
                            addToQueue(track)
                        }
                    })
                }
            }
        }
    }

    @Composable
    fun TrackItem(track: Track, onAddToQueue: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(onClick = onAddToQueue) {
                    Text("Add")
                }
            }
        }
    }

    private suspend fun searchTracks(query: String, onResultsReceived: (List<Track>) -> Unit) = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "Type somethin’, fam!", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for search")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            searchTracksFallback(query, onResultsReceived)
            return@withContext
        }

        val payload = JSONObject().apply {
            put("value", query)
        }
        WebSocketManager.emit("search", payload) { args ->
            try {
                val jsonObject = args[0] as? JSONObject ?: return@emit
                val lists = jsonObject.optJSONObject("navigation")?.optJSONArray("lists") ?: return@emit
                val results = mutableListOf<Track>()

                for (i in 0 until lists.length()) {
                    val list = lists.getJSONObject(i)
                    if (list.optString("title").contains("Tracks")) {
                        val items = list.optJSONArray("items") ?: continue
                        for (j in 0 until items.length()) {
                            val item = items.getJSONObject(j)
                            if (item.optString("type") == "song" && item.has("title") && item.has("artist") && item.has("uri")) {
                                results.add(
                                    Track(
                                        title = item.getString("title"),
                                        artist = item.getString("artist"),
                                        uri = item.getString("uri"),
                                        service = item.getString("service")
                                    )
                                )
                            }
                        }
                    }
                }
                Log.d(TAG, "Search response: $jsonObject")
                onResultsReceived(results)
            } catch (e: Exception) {
                Log.e(TAG, "Search parse error: $e")
                Toast.makeText(this@SearchActivity, "Search broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun searchTracksFallback(query: String, onResultsReceived: (List<Track>) -> Unit) = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/search?query=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "REST search failed: ${response.code}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SearchActivity, "REST search fucked up!", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val json = response.body?.string() ?: return@withContext
            Log.d(TAG, "REST search response: $json")
            val results = mutableListOf<Track>()
            val jsonObject = JSONObject(json)
            val lists = jsonObject.optJSONObject("navigation")?.optJSONArray("lists") ?: return@withContext

            for (i in 0 until lists.length()) {
                val list = lists.getJSONObject(i)
                if (list.optString("title").contains("Tracks")) {
                    val items = list.optJSONArray("items") ?: continue
                    for (j in 0 until items.length()) {
                        val item = items.getJSONObject(j)
                        if (item.optString("type") == "song" && item.has("title") && item.has("artist") && item.has("uri")) {
                            results.add(
                                Track(
                                    title = item.getString("title"),
                                    artist = item.getString("artist"),
                                    uri = item.getString("uri"),
                                    service = item.getString("service")
                                )
                            )
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                onResultsReceived(results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST search error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "REST search broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun addToQueue(track: Track) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.isConnected()) {
            Log.e(TAG, "WebSocket not connected for addToQueue")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "WebSocket ain’t connected!", Toast.LENGTH_SHORT).show()
            }
            addToQueueFallback(track)
            return@withContext
        }

        val payload = JSONObject().apply {
            put("uri", track.uri)
            put("service", track.service)
            put("title", track.title)
            put("artist", track.artist)
            put("type", "song")
        }
        WebSocketManager.emit("addToQueue", payload) { args ->
            Log.d(TAG, "Add to queue response: ${args.joinToString()}")
            Toast.makeText(this@SearchActivity, "Added ${track.title}, yo!", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun addToQueueFallback(track: Track) = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/addToQueue"
        val payload = JSONArray().put(
            JSONObject().apply {
                put("uri", track.uri)
                put("service", track.service)
                put("title", track.title)
                put("artist", track.artist)
                put("type", "song")
            }
        )
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            Log.d(TAG, "REST add to queue response: $responseBody")
            if (!response.isSuccessful) {
                Log.e(TAG, "REST queue add failed: ${response.code}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SearchActivity, "Couldn’t add ${track.title}!", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SearchActivity, "Added ${track.title}, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST queue add error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "REST queue add broke! $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class Track(
        val title: String,
        val artist: String,
        val uri: String,
        val service: String
    )
}