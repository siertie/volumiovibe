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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.MusicNote
import org.json.JSONArray

private fun setDevice(
    profile: String,
    device: String,
    context: android.content.Context
) {
    val client = OkHttpClient()
    val urlBuilder = HttpUrl.Builder()
        .scheme("http")
        .host("192.168.0.250")
        .port(8080)
        .addPathSegment("set_profile_device")
        .addQueryParameter("profile", profile)
        .addQueryParameter("capture_device", device)
    val request = Request.Builder()
        .get()
        .url(urlBuilder.build())
        .build()

    client.newCall(request).enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Set profile failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            val body = response.body?.string()
            Handler(Looper.getMainLooper()).post {
                if (response.isSuccessful) {
                    Toast.makeText(context, "Profile applied: $body", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Set failed: $body", Toast.LENGTH_SHORT).show()
                }
            }
        }
    })
}

fun dbToPercent(db: Float): Int {
    return ((db + 100) / 100f * 100).toInt().coerceIn(0, 100)
}

fun percentToDb(percent: Int): Float {
    return (percent / 100f) * 100f - 100f
}

@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected,
        onClick,
        { Text(label) },
        Modifier,
        true,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurface
        ),
        border = FilterChipDefaults.filterChipBorder(
            selected,
            true,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 2.dp
        )
    )
}

@Composable
fun VerticalVolumeSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    trackColor: Color,
    thumbColor: Color,
    trackWidth: Dp = 8.dp,
    thumbRadius: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(value) }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val sliderHeightPx = with(density) { maxHeight.toPx() }
        val sliderStart = with(density) { thumbRadius.toPx() }
        val sliderEnd = sliderHeightPx - sliderStart
        val valuePercent = ((if (isDragging) dragValue else value) - valueRange.start) / (valueRange.endInclusive - valueRange.start)
        val thumbY = sliderEnd - valuePercent * (sliderEnd - sliderStart)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            isDragging = false
                            onValueChangeFinished()
                        },
                        onDragStart = {
                            isDragging = true
                        }
                    ) { change, _ ->
                        val newY = change.position.y.coerceIn(sliderStart, sliderEnd)
                        val percent = 1f - ((newY - sliderStart) / (sliderEnd - sliderStart))
                        val newValue = valueRange.start + percent * (valueRange.endInclusive - valueRange.start)
                        val stepped = (
                                ((newValue - valueRange.start) * steps / (valueRange.endInclusive - valueRange.start))
                                    .roundToInt()
                                    .toFloat() * (valueRange.endInclusive - valueRange.start) / steps
                                ) + valueRange.start
                        dragValue = stepped.coerceIn(valueRange.start, valueRange.endInclusive)
                        onValueChange(dragValue)
                    }
                }
        ) {
            drawLine(
                color = trackColor,
                strokeWidth = with(density) { trackWidth.toPx() },
                start = Offset(size.width / 2f, sliderStart),
                end = Offset(size.width / 2f, sliderEnd)
            )
            drawCircle(
                color = thumbColor,
                radius = with(density) { thumbRadius.toPx() },
                center = Offset(size.width / 2f, thumbY)
            )
        }
    }
}

class NanoDigiActivity : BaseActivity() {
    private val TAG = "NanoDigiActivity"
    private val DEBUG_TAG = "NanoDigiDebug"
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            var config by remember { mutableStateOf(3) }
            var source by remember { mutableStateOf("Toslink") }
            var volume by remember { mutableStateOf(-37f) }
            var mute by remember { mutableStateOf(false) }
            var output by remember { mutableStateOf("Shield TV") }
            val outputMap = mapOf(
                "hw:sndrpihifiberry,0,0" to "Shield TV",
                "hw:Loopback,1,0" to "Volumio"
            )
            val reverseOutputMap = outputMap.entries.associate { it.value to it.key }
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current

            // Logic control state
            var tvActive by remember { mutableStateOf(false) }
            var musicActive by remember { mutableStateOf(false) }

            // --- API: Get state from server ---
            fun updateStateFromServer() {
                coroutineScope.launch {
                    try {
                        val body = withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url("http://192.168.0.240:5000/device_states")
                                .get()
                                .build()
                            client.newCall(request).execute().use { it.body?.string() }
                        }
                        val json = JSONObject(body ?: "{}")
                        val devices = json.optJSONArray("devices") ?: return@launch

                        for (i in 0 until devices.length()) {
                            val device = devices.getJSONObject(i)
                            if (device.optString("name") == "Stekkerdoos") {
                                val channels = device.optJSONArray("channels") ?: continue
                                var tv = false
                                var shield = false
                                for (j in 0 until channels.length()) {
                                    val channel = channels.getJSONObject(j)
                                    val name = channel.optString("name")
                                    val isOn = channel.optBoolean("is_on", false)
                                    if (name == "TV") tv = isOn
                                    if (name == "Shield TV") shield = isOn
                                }
                                tvActive = tv && shield
                                musicActive = !tv && shield
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("NanoDigiDebug", "State fetch failed", e)
                    }
                }
            }

            // --- API: Control plug ---
            fun controlPlug(channelName: String, action: String) {
                val channelIndex = when (channelName) {
                    "TV" -> 2
                    "Shield TV" -> 4
                    else -> return
                }
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        val json = JSONObject().apply {
                            put("device", "Stekkerdoos")
                            put("channel", channelIndex)
                            put("action", action)
                        }
                        val body = json.toString().toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url("http://192.168.0.240:5000/plug")
                            .post(body)
                            .build()
                        client.newCall(request).execute().close()
                    }
                    delay(200)
                    updateStateFromServer()
                }
            }

            fun controlPlugs(actions: List<Pair<String, String>>) {
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        val actionsJson = actions.map {
                            val channel = when (it.first) {
                                "TV" -> 2
                                "Shield TV" -> 4
                                else -> 0
                            }
                            JSONObject().apply {
                                put("channel", channel)
                                put("action", it.second)
                            }
                        }
                        val bodyJson = JSONObject().apply {
                            put("device", "Stekkerdoos")
                            put("actions", JSONArray(actionsJson))
                        }
                        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url("http://192.168.0.240:5000/plugs")
                            .post(body)
                            .build()
                        client.newCall(request).execute().close()
                    }
                    delay(200)
                    updateStateFromServer()
                }
            }

            // --- Logic control handlers ---
            val onTvPressed = {
                if (tvActive) {
                    controlPlug("Shield TV", "off")
                } else {
                    controlPlugs(
                        listOf(
                            "TV" to "on",
                            "Shield TV" to "on"
                        )
                    )
                }
                // Always set output, even if already selected:
                output = "Shield TV"
                val dev = reverseOutputMap["Shield TV"] ?: "hw:sndrpihifiberry,0,0"
                setDevice("default", dev, context)
            }

            val onMusicPressed = {
                if (musicActive) {
                    // Turn off Shield TV if Music is active and button pressed (turns music mode off)
                    controlPlug("Shield TV", "off")
                } else {
                    if (tvActive) {
                        // TV mode was ON, so just turn off TV, leave Shield TV ON
                        controlPlug("TV", "off")
                    } else {
                        // Not in TV mode, so ensure Shield TV is ON (music mode ON)
                        controlPlug("Shield TV", "on")
                    }
                }
                // Always set output
                output = "Volumio"
                val dev = reverseOutputMap["Volumio"] ?: "hw:Loopback,1,0"
                setDevice("default", dev, context)
            }

            // ---- SYNC ALL STATE FROM BACKEND ----
            LaunchedEffect(Unit) {
                fetchAllState(
                    coroutineScope,
                    context,
                    outputMap
                ) { newConfig, newSource, newVolume, newMute, newOutput ->
                    config = newConfig
                    source = newSource
                    volume = newVolume
                    mute = newMute
                    output = newOutput
                }
                updateStateFromServer()
            }
            // --------------------------------------

            AppTheme(themeMode = themeMode) {
                NanoDigiRemoteScreen(
                    themeMode = themeMode,
                    onThemeModeChange = { newMode -> themeMode = newMode },
                    config = config,
                    onConfigSelected = {
                        config = it
                        sendConfig(coroutineScope, context, preset = it)
                    },
                    source = source,
                    onSourceSelected = {
                        source = it
                        sendConfig(coroutineScope, context, source = it)
                    },
                    volume = volume,
                    onVolumeChange = { volume = it },
                    mute = mute,
                    onMuteToggle = {
                        mute = !mute
                        sendConfig(coroutineScope, context, mute = mute)
                    },
                    output = output,
                    onOutputSelected = { out ->
                        output = out
                        val dev = reverseOutputMap[out] ?: "hw:sndrpihifiberry,0,0"
                        setDevice("default", dev, context)
                        coroutineScope.launch { delay(250) }
                    },
                    sendConfig = { v -> sendConfig(coroutineScope, context, volume = v) },
                    tvActive = tvActive,
                    musicActive = musicActive,
                    onTvPressed = onTvPressed,
                    onMusicPressed = onMusicPressed
                )
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
                            Toast.makeText(context, "Config updated, yo!", Toast.LENGTH_SHORT)
                                .show()
                        }
                    } else {
                        Log.e(TAG, "HTTP POST failed: ${response.code} ${response.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Config update fucked up: ${response.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP POST error: $e")
                Log.d(DEBUG_TAG, "POST crashed: $e")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Config update crashed: $e", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun fetchAllState(
        scope: CoroutineScope,
        context: android.content.Context,
        outputMap: Map<String, String>,
        onState: (Int, String, Float, Boolean, String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                // Fetch /devices/0
                val configReq = Request.Builder()
                    .url("http://192.168.0.250:5380/devices/0")
                    .get()
                    .build()
                val configRes = client.newCall(configReq).execute()
                val configBody = configRes.body?.string() ?: "{}"
                val master = JSONObject(configBody).optJSONObject("master") ?: JSONObject()

                val preset = master.optInt("preset", 3)
                val source = master.optString("source", "Toslink")
                val volume = master.optDouble("volume", -37.0).toFloat()
                val mute = master.optBoolean("mute", false)

                // Fetch /get_device
                val devReq = Request.Builder()
                    .url("http://192.168.0.250:8080/get_device")
                    .get()
                    .build()
                val devRes = client.newCall(devReq).execute()
                val deviceStr = devRes.body?.string()?.trim() ?: ""
                Log.d("NanoDigiDebug", "Fetched device: $deviceStr")

                withContext(Dispatchers.Main) {
                    val cleanDevice = deviceStr.removeSurrounding("\"")
                    val mappedOutput = outputMap[cleanDevice] ?: cleanDevice
                    onState(preset, source, volume, mute, mappedOutput)
                }
            } catch (e: Exception) {
                Log.e("NanoDigiDebug", "fetchAllState error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Failed to fetch state: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    @Composable
    fun NanoDigiRemoteScreen(
        themeMode: ThemeMode,
        onThemeModeChange: (ThemeMode) -> Unit,
        config: Int,
        onConfigSelected: (Int) -> Unit,
        source: String,
        onSourceSelected: (String) -> Unit,
        volume: Float,
        onVolumeChange: (Float) -> Unit,
        mute: Boolean,
        onMuteToggle: () -> Unit,
        output: String,
        onOutputSelected: (String) -> Unit,
        sendConfig: (Float) -> Unit,
        tvActive: Boolean,
        musicActive: Boolean,
        onTvPressed: () -> Unit,
        onMusicPressed: () -> Unit
    ) {
        // Dialog state for volume confirmation
        var showVolumeDialog by remember { mutableStateOf(false) }
        var pendingVolume by remember { mutableStateOf(volume) }
        var previousVolume by remember { mutableStateOf(volume) }

        // Debounced volume
        var debouncedVolume by remember { mutableStateOf(volume) }

        // Only update volume externally if not in confirmation
        LaunchedEffect(volume) {
            if (!showVolumeDialog) {
                // Debounce: only send config after 500ms of no changes
                delay(500)
                if (debouncedVolume != volume) {
                    if (volume > -20f) {
                        // Prompt confirmation
                        pendingVolume = volume
                        showVolumeDialog = true
                    } else {
                        debouncedVolume = volume
                        sendConfig(volume)
                        previousVolume = volume
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(WindowInsets.systemBars.asPaddingValues())
                .padding(16.dp)
        ) {
            // --- Controls: All Cards on the Left ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- Top Bar ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "nanoDIGI Remote",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Logic Card ---
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Vibe Switcher", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = onTvPressed,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (tvActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(Icons.Default.Tv, contentDescription = "TV")
                                Spacer(Modifier.width(8.dp))
                                Text("TV")
                            }
                            Button(
                                onClick = onMusicPressed,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (musicActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = "Music")
                                Spacer(Modifier.width(8.dp))
                                Text("Music")
                            }
                        }
                    }
                }

                // --- Preset Card ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Sound Profile", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (0..3).forEach { idx ->
                                SelectableChip(
                                    label = "${idx + 1}",
                                    selected = config == idx,
                                    onClick = { onConfigSelected(idx) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Source Card ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Input Source", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Toslink", "Spdif").forEach { src ->
                                SelectableChip(
                                    label = src,
                                    selected = source == src,
                                    onClick = { onSourceSelected(src) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Output Card ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Playback Device", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Shield TV", "Volumio").forEach { out ->
                                SelectableChip(
                                    label = out,
                                    selected = output == out,
                                    onClick = { onOutputSelected(out) }
                                )
                            }
                        }
                    }
                }
            }

            // --- Vertical Volume Slider, Right Side ---
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(80.dp)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val muteIconTopPadding = 16.dp + 8.dp + 16.dp
                    IconButton(
                        onClick = { onMuteToggle() },
                        modifier = Modifier.padding(top = muteIconTopPadding, bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (mute) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = if (mute) "Unmute" else "Mute"
                        )
                    }

                    VerticalVolumeSlider(
                        value = volume,
                        valueRange = -100f..0f,
                        steps = 99,
                        onValueChange = { newVol ->
                            onVolumeChange(newVol)
                            pendingVolume = newVol
                        },
                        onValueChangeFinished = {
                            // No-op: handled by debounce logic
                        },
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        thumbColor = MaterialTheme.colorScheme.primary,
                        trackWidth = 8.dp,
                        thumbRadius = 16.dp,
                        modifier = Modifier
                            .weight(1f)
                            .width(48.dp)
                    )

                    Text(
                        "${dbToPercent(volume)}% (${volume.toInt()} dB)",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            // --- Confirmation Dialog for Loud Volume ---
            if (showVolumeDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showVolumeDialog = false
                        onVolumeChange(previousVolume)
                    },
                    title = { Text("Are you sure?") },
                    text = { Text("Volume will be set at ${pendingVolume.toInt()} dB") },
                    dismissButton = {
                        Button(
                            onClick = {
                                showVolumeDialog = false
                                onVolumeChange(previousVolume)
                            }
                        ) {
                            Text("Cancel")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            sendConfig(pendingVolume)
                            previousVolume = pendingVolume
                            debouncedVolume = pendingVolume
                            showVolumeDialog = false
                        }) {
                            Text("Yes, set volume")
                        }
                    }
                )
            }
        }
    }
}