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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape



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
        selected,           // Boolean (selected)
        onClick,            // () -> Unit
        { Text(label) },    // @Composable label
        Modifier,           // Modifier
        true,               // Boolean (enabled)
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurface
        ),
        border = FilterChipDefaults.filterChipBorder(
            selected, // required
            true,     // required, always enabled for you
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 2.dp
        )
    )
}


class NanoDigiActivity : BaseActivity() {
    private val TAG = "NanoDigiActivity"
    private val DEBUG_TAG = "NanoDigiDebug"
    private val client = OkHttpClient()

    private fun fetchDevice(
        scope: CoroutineScope,
        context: android.content.Context,
        onDeviceReceived: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://192.168.0.250:8080/get_device")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()?.trim() ?: ""
                    if (response.isSuccessful && responseBody.startsWith("hw:")) {
                        withContext(Dispatchers.Main) {
                            onDeviceReceived(responseBody)
                        }
                    }
                }
            } catch (e: Exception) {
                // Optionally handle errors here
            }
        }
    }

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

            // ---- SYNC ALL STATE FROM BACKEND ----
            LaunchedEffect(Unit) {
                // Fetch *all* state from /devices/0
                fetchState(coroutineScope, context) { newConfig, newSource, newVolume, newMute ->
                    config = newConfig
                    source = newSource
                    volume = newVolume
                    mute = newMute
                }
                // Fetch the current output device
                fetchDevice(coroutineScope, context) { deviceStr ->
                    output = outputMap[deviceStr] ?: "Shield TV"
                }
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
                        output = out  // Optimistic UI update
                        val dev = reverseOutputMap[out] ?: "hw:sndrpihifiberry,0,0"
                        setDevice(dev, context)
                        // Delay fetch to allow backend to update (250ms is usually enough)
                        coroutineScope.launch {
                            delay(250)
                            fetchDevice(coroutineScope, context) { deviceStr ->
                                output = outputMap[deviceStr] ?: "Shield TV"
                            }
                        }
                    },
                    sendConfig = { v -> sendConfig(coroutineScope, context, volume = v) }
                )
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
        sendConfig: (Float) -> Unit, // expects: sendConfig(volume)
    ) {
        // Dialog state for volume confirmation
        var showVolumeDialog by remember { mutableStateOf(false) }
        var pendingVolume by remember { mutableStateOf(volume) }
        var previousVolume by remember { mutableStateOf(volume) }

        Row(
            modifier = Modifier
                .fillMaxSize()
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

                // --- Preset Card ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Preset", style = MaterialTheme.typography.titleMedium)
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
                    elevation = CardDefaults.cardElevation()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Source", style = MaterialTheme.typography.titleMedium)
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
                    elevation = CardDefaults.cardElevation()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Output", style = MaterialTheme.typography.titleMedium)
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

                Spacer(modifier = Modifier.height(16.dp))

                // --- Mute Button ---
                Button(
                    onClick = { onMuteToggle() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(if (mute) "Unmute" else "Mute")
                }
            }

            // --- Vertical Volume Slider, Right Side ---
            Column(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Vol", style = MaterialTheme.typography.labelSmall)

                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.75f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    VerticalVolumeSlider(
                        value = volume,
                        valueRange = -100f..0f,
                        steps = 99,
                        onValueChange = { newVol ->
                            onVolumeChange(newVol)
                            pendingVolume = newVol // Always reflect the slider position
                        },
                        onValueChangeFinished = {
                            if (pendingVolume > -20f) {
                                showVolumeDialog = true
                            } else {
                                sendConfig(pendingVolume)
                                previousVolume = pendingVolume
                            }
                        },
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        thumbColor = MaterialTheme.colorScheme.primary,
                        trackWidth = 8.dp,
                        thumbRadius = 16.dp
                    )
                }
                Text("${dbToPercent(volume)}% (${volume.toInt()} dB)", style = MaterialTheme.typography.labelSmall)
            }
        }

        // --- Confirmation Dialog ---
        if (showVolumeDialog) {
            AlertDialog(
                onDismissRequest = {
                    showVolumeDialog = false
                    onVolumeChange(previousVolume)
                },
                title = { Text("Are you sure?") },
                text = { Text("Volume will be set at ${pendingVolume.toInt()} dB") },
                // Cancel first, styled as filled Button
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
                // Confirm second, styled as plain TextButton
                confirmButton = {
                    TextButton(onClick = {
                        sendConfig(pendingVolume)
                        previousVolume = pendingVolume
                        showVolumeDialog = false
                    }) {
                        Text("Yes, set volume")
                    }
                }
            )
        }
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
        thumbRadius: Dp = 16.dp
    ) {
        val density = LocalDensity.current
        var isDragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableStateOf(value) }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                .width(48.dp),
            contentAlignment = Alignment.Center
        ) {
            val sliderHeightPx = with(density) { maxHeight.toPx() }
            val sliderStart = with(density) { thumbRadius.toPx() }
            val sliderEnd = sliderHeightPx - sliderStart
            val valuePercent = (if (isDragging) dragValue else value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
            val thumbY = sliderEnd - valuePercent * (sliderEnd - sliderStart)

            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(48.dp)
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

            // --- Floating label while dragging ---
            if (isDragging) {
                Box(
                    modifier = Modifier
                        .offset(y = with(density) { thumbY.toDp() - 28.dp }) // adjust -28.dp for label height
                        .align(Alignment.Center)
                ) {
                    Surface(
                        shadowElevation = 4.dp,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text(
                            "${dbToPercent(dragValue)}% (${dragValue.toInt()} dB)",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
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