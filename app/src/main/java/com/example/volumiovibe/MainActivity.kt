package com.example.volumiovibe

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val volumioUrl = "http://volumio.local:3000" // Use mDNS
    private val client = OkHttpClient()
    private val TAG = "VolumioMainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VolumioControlScreen() }
        testRestApi()
    }

    private fun sendCommand(command: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val url = "$volumioUrl/api/v1/commands/?cmd=$command"
            val request = Request.Builder().url(url).build()
            try {
                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Sent $command")
                        if (command == "play") {
                            Toast.makeText(this@MainActivity, "Playin’, yo!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "$command failed: ${response.code}")
                        Toast.makeText(this@MainActivity, "$command fucked up!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "$command error: $e")
                    Toast.makeText(this@MainActivity, "$command broke! $e", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun getStatus(): String = withContext(Dispatchers.IO) {
        val url = "$volumioUrl/api/v1/getState"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext "Volumio Status: Unknown"
                val state = JSONObject(json)
                val status = state.getString("status")
                val title = state.optString("title", "Nothin’ playin’")
                val artist = state.optString("artist", "")
                Log.d(TAG, "Updated: $status, $title by $artist")
                "Volumio Status: $status\nNow Playin’: $title by $artist"
            } else {
                Log.e(TAG, "Status failed: ${response.code}")
                "Volumio Status: Error ${response.code}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Status error: $e")
            "Volumio Status: Broke! $e"
        }
    }

    private fun testRestApi() {
        CoroutineScope(Dispatchers.IO).launch {
            val url = "$volumioUrl/api/v1/getState"
            val request = Request.Builder().url(url).build()
            try {
                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "REST test: ${response.body?.string()}")
                        Toast.makeText(this@MainActivity, "Connected to Volumio, yo!", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e(TAG, "REST test failed: ${response.code}")
                        Toast.makeText(this@MainActivity, "Can’t connect!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "REST test error: $e")
                    Toast.makeText(this@MainActivity, "Connection broke!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Composable
    fun VolumioControlScreen() {
        var statusText by remember { mutableStateOf("Volumio Status: Disconnected") }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            while (true) {
                statusText = getStatus()
                delay(5000) // Update every 5 seconds
            }
        }

        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = {
                    sendCommand("play")
                    coroutineScope.launch { statusText = getStatus() }
                }) {
                    Text("Play")
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = {
                    sendCommand("pause")
                    coroutineScope.launch { statusText = getStatus() }
                }) {
                    Text("Pause")
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = {
                    sendCommand("next")
                    coroutineScope.launch { statusText = getStatus() }
                }) {
                    Text("Next")
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = {
                    sendCommand("prev")
                    coroutineScope.launch { statusText = getStatus() }
                }) {
                    Text("Previous")
                }
            }
        }
    }
}