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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import org.json.JSONArray
import org.json.JSONObject
import com.example.volumiovibe.ui.theme.AppTheme
import com.example.volumiovibe.ui.theme.ThemeMode
@OptIn(ExperimentalMaterial3Api::class)

class QueueActivity : BaseActivity() {
    private val TAG = "VolumioQueueActivity"
    private var refreshQueueCallback: (() -> Unit)? = null
    private lateinit var playerViewModel: PlayerViewModel
    private var queueState = mutableStateOf<List<Track>>(emptyList())
    private var keepSplash = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen().setKeepOnScreenCondition { keepSplash }

        CoroutineScope(Dispatchers.IO).launch {
            val connected = WebSocketManager.waitForConnection(5000)
            withContext(Dispatchers.Main) {
                keepSplash = false
                if (connected) {
                    Log.d(TAG, "WebSocket connected, yo!")
                    WebSocketManager.emit("getState")

                    playerViewModel = ViewModelProvider(
                        this@QueueActivity,
                        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                    ).get(PlayerViewModel::class.java)

                    setContent {
                        AppTheme(themeMode = ThemeMode.SYSTEM) {
                            QueueScreen(
                                onRefreshCallback = { refreshQueueCallback = it },
                                playerViewModel = playerViewModel
                            )
                        }
                    }
                } else {
                    Log.e(TAG, "WebSocket failed, fam")
                    showNoConnectionUI()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Checkin’ WebSocket")
        if (::playerViewModel.isInitialized) {
            playerViewModel.checkForStaleState()
        } else {
            Log.w(TAG, "onResume: playerViewModel not initialized yet")
        }
        refreshQueueCallback?.invoke()
    }

    @Composable
    fun QueueScreen(
        onRefreshCallback: (() -> Unit) -> Unit,
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
                        withContext(Main) {
                            Toast.makeText(context, "WebSocket dead, fam! Tryna reconnect.", Toast.LENGTH_SHORT).show()
                        }
                        WebSocketManager.reconnect()
                    }
                }
            }
            if (WebSocketManager.waitForConnection()) {
                WebSocketManager.emit("getState")
                fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
            } else {
                withContext(Main) {
                    Toast.makeText(context, "WebSocket dead, fam! Reconnectin’...", Toast.LENGTH_SHORT).show()
                }
                WebSocketManager.reconnect()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Queue", style = MaterialTheme.typography.headlineSmall) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.shadow(4.dp),
                    actions = {
                        IconButton(onClick = {
                            context.startActivity(Intent(context, SearchActivity::class.java))
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = {
                            context.startActivity(Intent(context, PlaylistActivity::class.java))
                        }) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Playlist")
                        }
                        IconButton(onClick = {
                            context.startActivity(Intent(context, NanoDigiActivity::class.java))
                        }) {
                            Icon(Icons.Default.SettingsRemote, contentDescription = "nanoDIGI")
                        }
                        var expanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("Clear Queue") }, onClick = {
                                expanded = false
                                coroutineScope.launch {
                                    clearQueue(coroutineScope)
                                    fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
                                }
                            })
                        }
                    }
                )
            },
            bottomBar = { NowPlayingBar(playerViewModel = playerViewModel) },
            contentWindowInsets = WindowInsets.systemBars
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                itemsIndexed(queue, key = { index, track -> "${track.uri}_$index" }) { index, track ->
                    TrackItem(
                        track = track,
                        index = index,
                        onClick = {
                            coroutineScope.launch {
                                playTrack(index, coroutineScope)
                            }
                        },
                        isActive = track.uri == playerViewModel.currentTrackUri,
                        actionButtons = {
                            if (index > 0) {
                                IconButton(onClick = {
                                    coroutineScope.launch {
                                        moveTrack(index, index - 1, coroutineScope)
                                        fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
                                    }
                                }) {
                                    Icon(painter = painterResource(id = android.R.drawable.arrow_up_float), contentDescription = "Move Up")
                                }
                            }
                            if (index < queue.size - 1) {
                                IconButton(onClick = {
                                    coroutineScope.launch {
                                        moveTrack(index, index + 1, coroutineScope)
                                        fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
                                    }
                                }) {
                                    Icon(painter = painterResource(id = android.R.drawable.arrow_down_float), contentDescription = "Move Down")
                                }
                            }
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    removeFromQueue(index, coroutineScope)
                                    fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
                                }
                            }) {
                                Icon(painter = painterResource(id = android.R.drawable.ic_delete), contentDescription = "Remove")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun showNoConnectionUI() {
        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No connection, fam!")
                Button(onClick = { WebSocketManager.reconnectNow() }) {
                    Text("Retry")
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
                        withContext(Main) {
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
                delay(500)
                WebSocketManager.emit("getState")
                withContext(Main) {
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
                delay(500)
                WebSocketManager.emit("getState")
                withContext(Main) {
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
                delay(500)
                WebSocketManager.emit("getState")
                withContext(Main) {
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
                delay(500)
                WebSocketManager.emit("getState")
                withContext(Main) {
                    Toast.makeText(this@QueueActivity, "Moved track, yo!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
