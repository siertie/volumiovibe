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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
                        results = searchTracks(query)
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
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Added ${track.title}, yo!", Toast.LENGTH_SHORT).show()
                            }
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

    private suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "Type somethin’, fam!", Toast.LENGTH_SHORT).show()
            }
            return@withContext emptyList()
        }

        val url = "$volumioUrl/api/v1/search?query=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Search failed: ${response.code}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SearchActivity, "Search fucked up!", Toast.LENGTH_SHORT).show()
                }
                return@withContext emptyList()
            }

            val json = response.body?.string() ?: return@withContext emptyList()
            Log.d(TAG, "Search response: $json")
            val results = mutableListOf<Track>()
            val jsonObject = JSONObject(json)
            val lists = jsonObject.optJSONObject("navigation")?.optJSONArray("lists") ?: return@withContext emptyList()

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
            results
        } catch (e: Exception) {
            Log.e(TAG, "Search error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "Search broke! $e", Toast.LENGTH_SHORT).show()
            }
            emptyList()
        }
    }

    private suspend fun addToQueue(track: Track) = withContext(Dispatchers.IO) {
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
            Log.d(TAG, "Queue add response for ${track.title} (URI: ${track.uri}, Service: ${track.service}): $responseBody")
            if (!response.isSuccessful) {
                Log.e(TAG, "Queue add failed: ${response.code}, response: $responseBody")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SearchActivity, "Couldn’t add ${track.title}!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d(TAG, "Added ${track.title} to queue")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Queue add error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SearchActivity, "Queue add broke! $e", Toast.LENGTH_SHORT).show()
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