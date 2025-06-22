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
import androidx.lifecycle.ViewModelProvider

class QueueActivity : ComponentActivity() {
    private val TAG = "VolumioQueueActivity"
    private var refreshQueueCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val TAG = "VolumioQueueActivity"
        var keepSplash = true
        installSplashScreen().setKeepOnScreenCondition { keepSplash }

        // First, initialize WebSocketManager and wait for connection BEFORE creating ViewModel or Compose UI
        CoroutineScope(Dispatchers.IO).launch {
            WebSocketManager.initialize()
            val connected = WebSocketManager.waitForConnection()
            withContext(Dispatchers.Main) {
                keepSplash = false

                if (connected) {
                    Log.d(TAG, "WebSocket connected, yo!")
                } else {
                    Log.e(TAG, "WebSocket failed, fam")
                }
                WebSocketManager.emit("getState")

                // Now it's SAFE to construct ViewModel
                val playerViewModel = ViewModelProvider(
                    this@QueueActivity,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                ).get(PlayerViewModel::class.java)

                // Set up the Compose UI tree
                setContent {
                    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
                    AppTheme(themeMode = themeMode) {
                        QueueScreen(
                            onRefreshCallback = { refreshQueueCallback = it },
                            themeMode = themeMode,
                            onThemeModeChange = { newMode -> themeMode = newMode },
                            playerViewModel = playerViewModel
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Checkin’ WebSocket")
        if (WebSocketManager.isConnected()) {
            WebSocketManager.emit("getState")
        } else {
            WebSocketManager.reconnect()
            // Wait briefly, then force getState. This helps after a reconnect delay.
            CoroutineScope(Dispatchers.Main).launch {
                repeat(3) { // Try up to 3 times
                    delay(800)
                    if (WebSocketManager.isConnected()) {
                        WebSocketManager.emit("getState")
                        return@launch
                    }
                }
            }
        }
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
        onThemeModeChange: (ThemeMode) -> Unit,
        playerViewModel: PlayerViewModel
    ) {
        var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            onRefreshCallback {
                coroutineScope.launch {
                    val connected = withContext(Dispatchers.IO) {
                        WebSocketManager.waitForConnection()
                    }
                    if (connected) {
                        fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "WebSocket dead, fam! Tryna reconnect.", Toast.LENGTH_SHORT).show()
                        }
                        WebSocketManager.reconnect()
                    }
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
        Scaffold(
            bottomBar = {
                NowPlayingBar(playerViewModel = playerViewModel)
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
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        Log.d(TAG, "Navigatin’ to NanoDigiActivity")
                        context.startActivity(Intent(context, NanoDigiActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text("Go to nanoDIGI")
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