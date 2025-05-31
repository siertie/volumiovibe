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
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import coil.request.CachePolicy
import androidx.compose.ui.res.painterResource
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import coil.compose.AsyncImagePainter

@Composable
fun Modifier.shimmerPlaceholder(isLoading: Boolean): Modifier = this.then(
    if (isLoading) {
        val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shimmer_alpha"
        )
        Modifier.drawBehind {
            drawRect(
                color = Color(0xFFB0B0B0).copy(alpha = alpha),
                topLeft = Offset.Zero,
                size = Size(size.width, size.height)
            )
        }
    } else {
        Modifier
    }
)

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
        var statusText by remember { mutableStateOf("Volumio Status: connecting...") }
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
            // Fetch initial state
            Common.fetchStateFallback(context) { status, title, artist ->
                statusText = "Volumio Status: $status\nNow Playin’: $title by $artist"
            }
            // Set up pushState listener
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
            if (WebSocketManager.isConnected()) {
                WebSocketManager.emit("getState")
            }
            // Fetch queue
            if (WebSocketManager.isConnected()) {
                fetchQueue(coroutineScope) { newQueue -> queue = newQueue }
            } else {
                fetchQueueFallback { newQueue -> queue = newQueue }
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
                    itemsIndexed(
                        items = queue,
                        key = { index, track -> "${track.uri}_$index" }
                    ) { index, track ->
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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onPlay() },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            ConstraintLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                val (albumArt, titleText, artistText, buttons) = createRefs()
                val albumArtUrl = when {
                    track.albumArt.isNullOrEmpty() -> "https://via.placeholder.com/64"
                    track.albumArt.startsWith("http") -> track.albumArt
                    else -> "http://volumio.local:3000${track.albumArt}"
                }
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(albumArtUrl)
                        .size(64, 64)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = "Album Art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .constrainAs(albumArt) {
                            start.linkTo(parent.start)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        },
                    placeholder = painterResource(id = R.drawable.placeholder),
                    error = painterResource(id = R.drawable.ic_error)
                )
                Text(
                    text = "${index + 1}. ${track.title}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier.constrainAs(titleText) {
                        start.linkTo(albumArt.end, margin = 12.dp)
                        top.linkTo(parent.top)
                        end.linkTo(buttons.start, margin = 8.dp)
                        width = Dimension.fillToConstraints
                    }
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0B0B0),
                    modifier = Modifier.constrainAs(artistText) {
                        start.linkTo(albumArt.end, margin = 12.dp)
                        top.linkTo(titleText.bottom)
                        end.linkTo(buttons.start, margin = 8.dp)
                        width = Dimension.fillToConstraints
                    }
                )
                Row(
                    modifier = Modifier.constrainAs(buttons) {
                        end.linkTo(parent.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    }
                ) {
                    if (onMoveUp != null) {
                        TextButton(onClick = onMoveUp) {
                            Text("↑", color = Color(0xFF03DAC6))
                        }
                    }
                    if (onMoveDown != null) {
                        TextButton(onClick = onMoveDown) {
                            Text("↓", color = Color(0xFF03DAC6))
                        }
                    }
                    TextButton(onClick = onRemove) {
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
                                albumArt = item.optString("albumart", null)
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
                            albumArt = item.optString("albumart", null)
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
            Log.d(TAG, "REST queue response: $json")
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
                            albumArt = item.optString("albumart", null)
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
        val albumArt: String?
    )
}