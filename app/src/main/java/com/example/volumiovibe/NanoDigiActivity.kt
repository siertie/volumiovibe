package com.example.volumiovibe

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.example.volumiovibe.ui.theme.AppTheme
import com.example.volumiovibe.ui.theme.ThemeMode
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import androidx.compose.runtime.Composable

private fun setDevice(
    device: String,
    context: android.content.Context
) {
    val client = OkHttpClient()
    val url = "http://192.168.0.250:8080/set_device?name=$device"
    val request = Request.Builder()
        .get()
        .url(url)
        .build()

    client.newCall(request).enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Set device failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            val body = response.body?.string()
            Handler(Looper.getMainLooper()).post {
                if (response.isSuccessful) {
                    Toast.makeText(context, "Device set: $body", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Set failed: $body", Toast.LENGTH_SHORT).show()
                }
            }
        }
    })
}

class NanoDigiActivity : ComponentActivity() {
    private val TAG = "NanoDigiActivity"
    private val DEBUG_TAG = "NanoDigiDebug"
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            AppTheme(themeMode = themeMode) {
                NanoDigiScreen(
                    themeMode = themeMode,
                    onThemeModeChange = { newMode -> themeMode = newMode }
                )
            }
        }
    }

    @Composable
    fun NanoDigiScreen(
        themeMode: ThemeMode,
        onThemeModeChange: (ThemeMode) -> Unit
    ) {
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current
        var config by remember { mutableStateOf(3) } // 0-3 internally, shown as 1-4
        var source by remember { mutableStateOf("Toslink") }
        var volume by remember { mutableStateOf(-37f) }
        var mute by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            fetchState(coroutineScope, context) { newConfig, newSource, newVolume, newMute ->
                config = newConfig
                source = newSource
                volume = newVolume
                mute = newMute
            }
        }

        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "nanoDIGI Control",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (themeMode == ThemeMode.DARK) "Dark" else "Light",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = themeMode == ThemeMode.DARK,
                            onCheckedChange = {
                                onThemeModeChange(if (it) ThemeMode.DARK else ThemeMode.LIGHT)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Config: ${config + 1}", style = MaterialTheme.typography.bodyLarge)
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            config = 0
                            sendConfig(coroutineScope, context, preset = config)
                        },
                        enabled = config != 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Text("1")
                    }
                    Button(
                        onClick = {
                            config = 1
                            sendConfig(coroutineScope, context, preset = config)
                        },
                        enabled = config != 1,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Text("2")
                    }
                    Button(
                        onClick = {
                            config = 2
                            sendConfig(coroutineScope, context, preset = config)
                        },
                        enabled = config != 2,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Text("3")
                    }
                    Button(
                        onClick = {
                            config = 3
                            sendConfig(coroutineScope, context, preset = config)
                        },
                        enabled = config != 3,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Text("4")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Source: $source", style = MaterialTheme.typography.bodyLarge)
                Row {
                    Button(
                        onClick = {
                            source = "Toslink"
                            sendConfig(coroutineScope, context, source = source)
                        },
                        enabled = source != "Toslink",
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Text("Toslink")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            source = "Spdif"
                            sendConfig(coroutineScope, context, source = source)
                        },
                        enabled = source != "Spdif",
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Text("Spdif")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Volume: ${volume.toInt()} dB",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = volume,
                    onValueChange = { newValue ->
                        volume = newValue.toInt().toFloat() // Snap to whole dB
                    },
                    onValueChangeFinished = {
                        sendConfig(coroutineScope, context, volume = volume)
                    },
                    valueRange = -100f..0f,
                    steps = 99, // 100 steps for -100 to 0 dB
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Mute: ${if (mute) "On" else "Off"}", style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = {
                        mute = !mute
                        sendConfig(coroutineScope, context, mute = mute)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text(if (mute) "Unmute" else "Mute")
                }

                // Spacer so the buttons aren’t cramped
                Spacer(modifier = Modifier.height(16.dp))

// Two config buttons for CamillaDSP
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val context = LocalContext.current

                    Button(
                        onClick = {
                            setDevice("hw:sndrpihifiberry,0,0", context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Shield TV")
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Button(
                        onClick = {
                            setDevice("hw:Loopback,1,0", context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Volumio")
                    }
                }
            }
        }
    }

    private fun sendConfig(
        scope: CoroutineScope,
        context: android.content.Context,
        preset: Int? = null,
        source: String? = null,
        volume: Float? = null,
        mute: Boolean? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val masterStatus = JSONObject()
                when {
                    preset != null -> masterStatus.put("preset", preset)
                    source != null -> masterStatus.put("source", source)
                    volume != null -> masterStatus.put("volume", volume.toDouble())
                    mute != null -> masterStatus.put("mute", mute)
                }
                val json = JSONObject().apply {
                    put("master_status", masterStatus)
                }
                Log.d(DEBUG_TAG, "Sendin’ POST payload: $json")
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://192.168.0.250:5380/devices/0/config")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: "No response body"
                    Log.d(DEBUG_TAG, "POST response: code=${response.code}, body=$responseBody")
                    if (response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Config updated, yo!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "HTTP POST failed: ${response.code} ${response.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Config update fucked up: ${response.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP POST error: $e")
                Log.d(DEBUG_TAG, "POST crashed: $e")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Config update crashed: $e", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchState(
        scope: CoroutineScope,
        context: android.content.Context,
        onStateReceived: (Int, String, Float, Boolean) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://192.168.0.250:5380/devices/0")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: "{}"
                    Log.d(DEBUG_TAG, "GET response: code=${response.code}, body=$responseBody")
                    if (response.isSuccessful) {
                        val json = JSONObject(responseBody)
                        val master = json.optJSONObject("master") ?: JSONObject()
                        val newPreset = master.optInt("preset", 3)
                        val newSource = master.optString("source", "Toslink")
                        val newVolume = master.optDouble("volume", -37.0).toFloat()
                        val newMute = master.optBoolean("mute", false)

                        withContext(Dispatchers.Main) {
                            onStateReceived(newPreset, newSource, newVolume, newMute)
                            Toast.makeText(context, "Fetched nanoDIGI state, yo!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "HTTP GET failed: ${response.code} ${response.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "State fetch fucked up: ${response.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP GET error: $e")
                Log.d(DEBUG_TAG, "GET crashed: $e")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "State fetch crashed: $e", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}