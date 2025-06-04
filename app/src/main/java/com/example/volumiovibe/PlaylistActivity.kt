package com.example.volumiovibe

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch // Fix for Unresolved reference 'launch'
import kotlinx.coroutines.withContext
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

// Playlist data class (kept in case it’s not defined elsewhere)
data class Playlist(val name: String, val tracks: List<Track>)

// PlaylistStateHolder from original code
object PlaylistStateHolder {
    var selectedVibe: String = GrokConfig.VIBE_OPTIONS.first()
    var vibeInput: String = ""
    var era: String = GrokConfig.ERA_OPTIONS.first()
    var language: String = GrokConfig.LANGUAGE_OPTIONS.first()
    var instrument: String = GrokConfig.INSTRUMENT_OPTIONS.first()
    var playlistName: String = ""
    var artists: String = ""
    var numSongs: String = GrokConfig.DEFAULT_NUM_SONGS
    var maxSongsPerArtist: String = GrokConfig.DEFAULT_MAX_SONGS_PER_ARTIST
    val recentVibes: MutableList<String> = mutableListOf()
}

@OptIn(ExperimentalMaterial3Api::class)
class PlaylistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dynamicColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                MaterialTheme.colorScheme
            }
            MaterialTheme(colorScheme = dynamicColor) {
                PlaylistScreen(viewModel = viewModel { PlaylistViewModel(application) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel) {
    val TAG = "PlaylistScreen"
    val context = LocalContext.current
    val vibeOptions = GrokConfig.VIBE_OPTIONS
    val eraOptions = GrokConfig.ERA_OPTIONS
    val languageOptions = GrokConfig.LANGUAGE_OPTIONS
    val instrumentOptions = GrokConfig.INSTRUMENT_OPTIONS
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var optionsExpanded by remember { mutableStateOf(false) }
    var vibeExpanded by remember { mutableStateOf(false) }
    var eraExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var instrumentExpanded by remember { mutableStateOf(false) }
    var expandedPlaylist by remember { mutableStateOf<String?>(null) }

    Log.d(TAG, "PlaylistScreen initialized with vibeOptions: ${vibeOptions.joinToString()}")

    LaunchedEffect(Unit) {
        WebSocketManager.onConnectionChange { isConnected ->
            coroutineScope.launch { // Fixed with import
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
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlist Vibe", textAlign = TextAlign.Center) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        viewModel.vibeInput = it
                        viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                    },
                    onSearch = { viewModel.generateAiPlaylist(context) },
                    active = false,
                    onActiveChange = {},
                    placeholder = { Text("Enter Vibe for Playlist") },
                    trailingIcon = {
                        IconButton(onClick = {
                            optionsExpanded = !optionsExpanded
                        }) {
                            Icon(
                                painter = painterResource(
                                    id = if (optionsExpanded) android.R.drawable.ic_menu_close_clear_cancel
                                    else android.R.drawable.ic_menu_add
                                ),
                                contentDescription = if (optionsExpanded) "Collapse Options" else "Expand Options"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {}

                if (PlaylistStateHolder.recentVibes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlaylistStateHolder.recentVibes.take(3).forEach { vibe: String ->
                            FilterChip(
                                selected = searchQuery == vibe,
                                onClick = {
                                    searchQuery = vibe
                                    viewModel.vibeInput = vibe
                                    viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                                },
                                label = { Text(vibe) }
                            )
                        }
                    }
                }

                if (optionsExpanded) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ExposedDropdownMenuBox(
                                expanded = vibeExpanded,
                                onExpandedChange = { vibeExpanded = !vibeExpanded }
                            ) {
                                TextField(
                                    value = viewModel.selectedVibe,
                                    onValueChange = { viewModel.selectedVibe = it },
                                    label = { Text("Pick a Vibe or Type Your Own") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true
                                )
                                ExposedDropdownMenu(
                                    expanded = vibeExpanded,
                                    onDismissRequest = { vibeExpanded = false }
                                ) {
                                    vibeOptions.forEach { vibe ->
                                        DropdownMenuItem(
                                            text = { Text(vibe) },
                                            onClick = {
                                                viewModel.selectedVibe = vibe
                                                viewModel.vibeInput = if (vibe != GrokConfig.VIBE_OPTIONS.first()) vibe else ""
                                                vibeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = viewModel.vibeInput,
                                onValueChange = {
                                    viewModel.vibeInput = it
                                    viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                                },
                                label = { Text("Custom Vibe") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            ExposedDropdownMenuBox(
                                expanded = eraExpanded,
                                onExpandedChange = { eraExpanded = !eraExpanded }
                            ) {
                                TextField(
                                    value = viewModel.era,
                                    onValueChange = { viewModel.era = it },
                                    label = { Text("Pick an Era") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true
                                )
                                ExposedDropdownMenu(
                                    expanded = eraExpanded,
                                    onDismissRequest = { eraExpanded = false }
                                ) {
                                    eraOptions.forEach { era ->
                                        DropdownMenuItem(
                                            text = { Text(era) },
                                            onClick = {
                                                viewModel.era = era
                                                eraExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            ExposedDropdownMenuBox(
                                expanded = languageExpanded,
                                onExpandedChange = { languageExpanded = !languageExpanded }
                            ) {
                                TextField(
                                    value = viewModel.language,
                                    onValueChange = { viewModel.language = it },
                                    label = { Text("Music Language") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true
                                )
                                ExposedDropdownMenu(
                                    expanded = languageExpanded,
                                    onDismissRequest = { languageExpanded = false }
                                ) {
                                    languageOptions.forEach { lang ->
                                        DropdownMenuItem(
                                            text = { Text(lang) },
                                            onClick = {
                                                viewModel.language = lang
                                                languageExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            ExposedDropdownMenuBox(
                                expanded = instrumentExpanded,
                                onExpandedChange = { instrumentExpanded = !instrumentExpanded }
                            ) {
                                TextField(
                                    value = viewModel.instrument,
                                    onValueChange = { viewModel.instrument = it },
                                    label = { Text("Featured Instrument") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    readOnly = true
                                )
                                ExposedDropdownMenu(
                                    expanded = instrumentExpanded,
                                    onDismissRequest = { instrumentExpanded = false }
                                ) {
                                    instrumentOptions.forEach { inst ->
                                        DropdownMenuItem(
                                            text = { Text(inst) },
                                            onClick = {
                                                viewModel.instrument = inst
                                                instrumentExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = viewModel.playlistName,
                                onValueChange = { viewModel.playlistName = it },
                                label = { Text("Playlist Name (blank for Grok’s pick)") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = viewModel.artists,
                                onValueChange = { viewModel.artists = it },
                                label = { Text("Example Artists (e.g., Green Day)") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = viewModel.numSongs,
                                onValueChange = { viewModel.numSongs = it },
                                label = { Text("How Many Tracks?") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = viewModel.maxSongsPerArtist,
                                onValueChange = { viewModel.maxSongsPerArtist = it },
                                label = { Text("Max Songs per Artist") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (optionsExpanded) {
                    FilledTonalButton(
                        onClick = { viewModel.generateAiPlaylist(context) },
                        enabled = !viewModel.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Text("Make That Playlist, Fam!")
                    }
                }

                if (viewModel.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                }

                if (!optionsExpanded) {
                    FilledTonalButton(
                        onClick = { viewModel.generateAiPlaylist(context) },
                        enabled = !viewModel.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Text("Make That Playlist, Fam!")
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(viewModel.playlists) { playlist: Playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            isExpanded = expandedPlaylist == playlist.name,
                            onToggle = {
                                Log.d(TAG, "Toggling playlist: ${playlist.name}, current expanded: $expandedPlaylist")
                                expandedPlaylist = if (expandedPlaylist == playlist.name) null else playlist.name
                                if (expandedPlaylist == playlist.name) {
                                    viewModel.browsePlaylistTracks(playlist.name)
                                }
                                Log.d(TAG, "New expandedPlaylist: $expandedPlaylist")
                            },
                            onPlay = { viewModel.playPlaylist(playlist.name) },
                            onDelete = { viewModel.deletePlaylist(playlist.name) },
                            onRemoveTrack = { trackUri -> viewModel.removeFromPlaylist(playlist.name, trackUri) }
                        )
                    }
                }

                if (viewModel.aiSuggestions.isNotEmpty()) {
                    Text(
                        text = "AI Suggested Tracks",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        items(viewModel.aiSuggestions) { track ->
                            PlaylistTrackItem(
                                track = track,
                                actionButtons = {
                                    IconButton(onClick = {
                                        viewModel.playlists.firstOrNull { it.name == expandedPlaylist }?.let { playlist ->
                                            viewModel.addToPlaylist(playlist.name, track.uri, track.type)
                                        }
                                    }) {
                                        Icon(
                                            painter = painterResource(id = android.R.drawable.ic_menu_add),
                                            contentDescription = "Add"
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun PlaylistCard(
    playlist: Playlist,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onRemoveTrack: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onToggle() }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onPlay) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_media_play),
                        contentDescription = "Play"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_delete),
                        contentDescription = "Delete"
                    )
                }
            }
            Log.d("PlaylistCard", "Playlist: ${playlist.name}, isExpanded: $isExpanded, tracks: ${playlist.tracks}")
            if (isExpanded && playlist.tracks.isNotEmpty()) {
                Text(
                    text = "${playlist.name} Tracks",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(playlist.tracks) { track ->
                        PlaylistTrackItem(
                            track = track,
                            actionButtons = {
                                IconButton(onClick = { onRemoveTrack(track.uri) }) {
                                    Icon(
                                        painter = painterResource(id = android.R.drawable.ic_delete),
                                        contentDescription = "Remove"
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistTrackItem(track: Track, actionButtons: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall
            )
        }
        actionButtons()
    }
}
