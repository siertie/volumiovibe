package com.example.volumiovibe

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.volumiovibe.ui.theme.AppTheme
import com.example.volumiovibe.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import org.json.JSONArray
import org.json.JSONObject

class QueueActivity : ComponentActivity() {
    private val TAG = "VolumioQueueActivity"
    private var refreshQueueCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var keepSplash = true
        installSplashScreen().setKeepOnScreenCondition { keepSplash }
        CoroutineScope(Dispatchers.Main).launch {
            WebSocketManager.initialize()
            if (WebSocketManager.waitForConnection()) {
                Log.d(TAG, "WebSocket connected, yo!")
            } else {
                Log.e(TAG, "WebSocket failed, fam")
            }
            keepSplash = false
        }
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            AppTheme(themeMode = themeMode) {
                QueueScreen(
                    onRefreshCallback = { refreshQueueCallback = it },
                    themeMode = themeMode,
                    onThemeModeChange = { newMode -> themeMode = newMode }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Checkin’ WebSocket")
        WebSocketManager.reconnect()
        refreshQueueCallback?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Activity dead, isFinishing=$isFinishing")
    }

    @Composable
    fun QueueScreen(
        onRefreshCallback: (() -> Unit) -> Unit,
        themeMode: ThemeMode,
        onThemeModeChange: (ThemeMode) -> Unit
    ) {
        var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
        var statusText by remember { mutableStateOf("Volumio Status: connectin’...") }
        var seekPosition by remember { mutableStateOf(0f) }
        var trackDuration by remember { mutableStateOf(1f) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentTrackUri by remember { mutableStateOf("") }
        var tickJob by remember { mutableStateOf<Job?>(null) }
        var ignoreSeekUpdates by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            onRefreshCallback {
                coroutineScope.launch {
                    if (WebSocketManager.waitForConnection()) {
                        fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "WebSocket dead, fam! Tryna reconnect.", Toast.LENGTH_SHORT).show()
                        }
                        WebSocketManager.reconnect()
                    }
                }
            }
            WebSocketManager.onConnectionChange { isConnected ->
                coroutineScope.launch {
                    withContext(Dispatchers.Main) {
                        if (!isConnected) {
                            Toast.makeText(context, "WebSocket dropped, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
                            WebSocketManager.reconnect()
                        } else {
                            Toast.makeText(context, "WebSocket connected, yo!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            WebSocketManager.on("pushState") { args ->
                try {
                    val state = args[0] as? JSONObject ?: return@on
                    val status = state.getString("status")
                    val title = state.optString("title", "Nothin’ playin’")
                    val artist = state.optString("artist", "")
                    val seek = state.optLong("seek", -1).toFloat() / 1000f
                    val duration = state.optLong("duration", 1).toFloat().coerceAtLeast(1f)
                    val uri = state.optString("uri", "")
                    tickJob?.cancel()
                    val isTrackChange = uri != currentTrackUri && uri.isNotEmpty()
                    if (isTrackChange) {
                        currentTrackUri = uri
                        seekPosition = 0f
                    }
                    isPlaying = (status == "play")
                    if (!ignoreSeekUpdates && (seek >= 0f || isTrackChange)) {
                        seekPosition = if (seek >= 0f) seek else 0f
                    }
                    trackDuration = duration
                    statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
                    if (isPlaying) {
                        tickJob = coroutineScope.launch {
                            while (isActive && isPlaying && seekPosition < trackDuration) {
                                delay(1000)
                                if (isPlaying && seekPosition < trackDuration) {
                                    seekPosition += 1f
                                }
                            }
                            if (seekPosition >= trackDuration) {
                                isPlaying = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Push state error: $e")
                    statusText = "Volumio Status: error - $e"
                }
            }
            // Wait for connection and fetch queue/state
            if (WebSocketManager.waitForConnection()) {
                WebSocketManager.emit("getState")
                fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
                }
                WebSocketManager.reconnect()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                tickJob?.cancel()
            }
        }

        Scaffold(
            bottomBar = {
                PlayerControls(
                    statusText = statusText,
                    seekPosition = seekPosition,
                    trackDuration = trackDuration,
                    isPlaying = isPlaying,
                    onPlay = {
                        coroutineScope.launch { Common.sendCommand(context, "play") }
                    },
                    onPause = {
                        coroutineScope.launch { Common.sendCommand(context, "pause") }
                    },
                    onNext = {
                        coroutineScope.launch { Common.sendCommand(context, "next") }
                    },
                    onPrevious = {
                        coroutineScope.launch { Common.sendCommand(context, "prev") }
                    },
                    onSeek = { newValue ->
                        seekPosition = newValue
                        coroutineScope.launch {
                            val seekSeconds = newValue.toInt()
                            ignoreSeekUpdates = true
                            WebSocketManager.emit("pause")
                            delay(500)
                            WebSocketManager.emit("seek", seekSeconds)
                            delay(500)
                            WebSocketManager.emit("play")
                            delay(4000)
                            ignoreSeekUpdates = false
                            delay(1000)
                            WebSocketManager.emit("getState")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Queue",
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
                Button(
                    onClick = {
                        coroutineScope.launch {
                            clearQueue(coroutineScope)
                            fetchQueue(coroutineScope) { newQueue ->
                                queue = newQueue
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text("Clear Queue")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        Log.d(TAG, "Navigatin’ to SearchActivity with CLEAR_TOP | SINGLE_TOP")
                        context.startActivity(Intent(context, SearchActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text("Go to Search")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        context.startActivity(Intent(context, PlaylistActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text("Go to Playlist")
                }
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn {
                    itemsIndexed(
                        items = queue,
                        key = { index, track -> "${track.uri}_$index" }
                    ) { index, track ->
                        TrackItem(
                            track = track,
                            index = index,
                            onClick = {
                                coroutineScope.launch {
                                    playTrack(index, coroutineScope)
                                }
                            },
                            actionButtons = {
                                if (index > 0) {
                                    IconButton(onClick = {
                                        coroutineScope.launch {
                                            moveTrack(index, index - 1, coroutineScope)
                                            fetchQueue(coroutineScope) { newQueue ->
                                                queue = newQueue
                                            }
                                        }
                                    }) {
                                        Icon(
                                            painter = painterResource(id = android.R.drawable.arrow_up_float),
                                            contentDescription = "Move Up",
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                if (index < queue.size - 1) {
                                    IconButton(onClick = {
                                        coroutineScope.launch {
                                            moveTrack(index, index + 1, coroutineScope)
                                            fetchQueue(coroutineScope) { newQueue ->
                                                queue = newQueue
                                            }
                                        }
                                    }) {
                                        Icon(
                                            painter = painterResource(id = android.R.drawable.arrow_down_float),
                                            contentDescription = "Move Down",
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    coroutineScope.launch {
                                        removeFromQueue(index, coroutineScope)
                                        fetchQueue(coroutineScope) { newQueue ->
                                            queue = newQueue
                                        }
                                    }
                                }) {
                                    Icon(
                                        painter = painterResource(id = android.R.drawable.ic_delete),
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchQueue(scope: CoroutineScope, onQueueReceived: (List<Track>) -> Unit) = withContext(Dispatchers.IO) {
        var retries = 0
        while (retries < 3) {
            if (!WebSocketManager.waitForConnection()) {
                Log.e(TAG, "WebSocket ain’t connected for fetchQueue (retry $retries)")
                withContext(Main) {
                    Toast.makeText(this@QueueActivity, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
                }
                WebSocketManager.reconnect()
                retries++
                delay(2000)
                continue
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
                                    albumArt = item.optString("albumart", null),
                                    type = item.optString("type", "song")
                                )
                            )
                        }
                    }
                    Log.d(TAG, "Got queue: $queueArray")
                    onQueueReceived(results)
                } catch (e: Exception) {
                    Log.e(TAG, "Queue parse error: $e")
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@QueueActivity, "Queue fetch fucked up! $e", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Log.d(TAG, "Sent getQueue (retry $retries)")
            return@withContext
        }
        Log.e(TAG, "Gave up fetchin’ queue after $retries tries")
        withContext(Main) {
            Toast.makeText(this@QueueActivity, "Queue fetch failed after retries, fam!", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun clearQueue(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.waitForConnection()) {
            Log.e(TAG, "WebSocket ain’t connected for clearQueue")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
            }
            WebSocketManager.reconnect()
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

    private suspend fun playTrack(index: Int, scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.waitForConnection()) {
            Log.e(TAG, "WebSocket ain’t connected for playTrack")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
            }
            WebSocketManager.reconnect()
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

    private suspend fun removeFromQueue(index: Int, scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.waitForConnection()) {
            Log.e(TAG, "WebSocket ain’t connected for removeFromQueue")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
            }
            WebSocketManager.reconnect()
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

    private suspend fun moveTrack(fromIndex: Int, toIndex: Int, scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!WebSocketManager.waitForConnection()) {
            Log.e(TAG, "WebSocket ain’t connected for moveTrack")
            withContext(Main) {
                Toast.makeText(this@QueueActivity, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
            }
            WebSocketManager.reconnect()
            return@withContext
        }

        val payload = JSONObject().apply {
            put("from", fromIndex)
            put("to", toIndex)
        }
        WebSocketManager.emit("moveQueue", payload) { args ->
            Log.d(TAG, "Got pushQueue: ${args.joinToString()}")
            scope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QueueActivity, "Moved track, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}