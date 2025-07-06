package com.example.volumiovibe

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.volumiovibe.ui.theme.AppTheme
import com.example.volumiovibe.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.example.volumiovibe.TrackItem
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.CheckCircle


// ──────────────────── DOMAIN MODELS ────────────────────

data class Playlist(val name: String, val tracks: List<Track>)

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

// ──────────────────── ACTIVITY ────────────────────

class PlaylistActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            AppTheme(themeMode = themeMode) {
                PlaylistScreen(
                    viewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return PlaylistViewModel(application) as T
                        }
                    }),
                    themeMode = themeMode,
                    onThemeModeChange = { themeMode = it }
                )
            }
        }
    }
}

// ──────────────────── ROOT SCREEN ────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedPlaylistNameForSheet by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf<String?>(null) }

    // WebSocket connection feedback
    LaunchedEffect(Unit) {
        WebSocketManager.onConnectionChange { connected ->
            coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (connected) "WebSocket up 🚀" else "WebSocket down ❌", Toast.LENGTH_SHORT).show()
                    if (connected) {
                        // 🟢 Reload playlists when socket comes back!
                        viewModel.fetchPlaylists()
                    }
                    if (!connected) WebSocketManager.reconnect()
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showSheet = true }) {
                Icon(painter = painterResource(id = R.drawable.ic_add_playlist), contentDescription = "New playlist")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            PlaylistsSection(
                viewModel = viewModel,
                onPlaylistTap = { playlist ->
                    selectedPlaylistNameForSheet = playlist.name
                    if (playlist.tracks.isEmpty()) {
                        viewModel.browsePlaylistTracks(playlist.name)
                    }
                },
                onDelete = { showDelete = it },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // Bottom sheet for playlist details
    if (selectedPlaylistNameForSheet != null) {
        val playlist = viewModel.playlists.firstOrNull { it.name == selectedPlaylistNameForSheet }
        if (playlist != null) {
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { selectedPlaylistNameForSheet = null }
            ) {
                PlaylistDetailSheet(
                    playlist = playlist,
                    viewModel = viewModel,
                    onClose = { selectedPlaylistNameForSheet = null }
                )
            }
        }
    }


    // Bottom sheet for vibe setup
    if (showSheet) {
        ModalBottomSheet(sheetState = sheetState, onDismissRequest = { showSheet = false }) {
            VibeSheetContent(viewModel = viewModel, onClose = { showSheet = false })
        }
    }

    // Delete dialog
    if (showDelete != null) {
        AlertDialog(
            onDismissRequest = { showDelete = null },
            title = { Text("Delete playlist?") },
            text = { Text("Delete '${showDelete}' permanently?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(showDelete!!)
                    showDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = null }) { Text("Cancel") } }
        )
    }
    if (viewModel.playlistDialogVisible) {
        AlertDialog(
            onDismissRequest = {}, // don't dismiss by tapping outside
            title = {
                Text(if (viewModel.playlistGenerationFinished) "Playlist Finished!" else "Generating Playlist")
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!viewModel.playlistGenerationFinished) {
                        LinearProgressIndicator(
                            progress = viewModel.generationProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(viewModel.generationStatus)
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Done",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(24.dp))
                        Text("Your playlist is ready! All found tracks have been added.")
                    }
                }
            },
            confirmButton = {
                if (viewModel.playlistGenerationFinished) {
                    TextButton(onClick = {
                        viewModel.dismissPlaylistDialog()
                    }) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {
                if (!viewModel.playlistGenerationFinished) {
                    TextButton(onClick = {
                        viewModel.cancelPlaylistGeneration()
                    }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

// ──────────────────── PLAYLIST SECTION ────────────────────

@Composable
fun PlaylistsSection(
    viewModel: PlaylistViewModel,
    onPlaylistTap: (Playlist) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column {
            ListItem(headlineContent = { Text("Your Playlists") })
            Divider()
            when {
                viewModel.isLoading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                viewModel.playlists.isEmpty() -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("No playlists yet! Tap the + button to start.") }
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(viewModel.playlists) { playlist ->
                        PlaylistItem(
                            playlist = playlist,
                            onTap = { onPlaylistTap(playlist) },
                            onPlay = { viewModel.playPlaylist(playlist.name) },
                            onDelete = { onDelete(playlist.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistItem(
    playlist: Playlist,
    onTap: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier
            .padding(8.dp)
            .clickable { onTap() }
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(id = R.drawable.ic_album), contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                Text("${playlist.tracks.size} tracks", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onPlay) { Icon(painter = painterResource(id = R.drawable.ic_play), contentDescription = "Play") }
            IconButton(onClick = onDelete) { Icon(painter = painterResource(id = R.drawable.ic_delete), contentDescription = "Delete") }
        }
    }
}

// ──────────────────── PLAYLIST DETAIL SHEET ────────────────────

@Composable
fun PlaylistDetailSheet(
    playlist: Playlist,
    viewModel: PlaylistViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
        }
        Text("${playlist.tracks.size} tracks", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            itemsIndexed(playlist.tracks) { index, track ->
                TrackItem(
                    track = track,
                    index = index,
                    onClick = null, // Or provide a callback if you want!
                    actionButtons = {
                        IconButton(onClick = {
                            viewModel.removeFromPlaylist(playlist.name, track.uri)
                            Toast.makeText(context, "${track.title} removed", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(painter = painterResource(id = R.drawable.ic_delete), contentDescription = "Remove")
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { viewModel.playPlaylist(playlist.name) }) {
                Icon(painter = painterResource(id = R.drawable.ic_play), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Play")
            }
            // Add more buttons here if you want (like Shuffle)
        }
    }
}

// ──────────────────── VIBE BOTTOM‑SHEET ────────────────────

@Composable
fun VibeSheetContent(viewModel: PlaylistViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Customize your vibe", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
        }
        Spacer(Modifier.height(16.dp))
        VibeSection(
            viewModel = viewModel,
            searchQuery = viewModel.vibeInput,
            onSearchChange = { viewModel.vibeInput = it },
            onToggleOptions = {},
            optionsExpanded = true
        )
        Spacer(Modifier.height(12.dp))
        AdvancedOptionsSection(viewModel)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            onClose(); viewModel.generateAiPlaylist(context)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Generate playlist")
        }
    }
}

// ──────────────────── VIBE SECTION ────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VibeSection(
    viewModel: PlaylistViewModel,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggleOptions: () -> Unit,
    optionsExpanded: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    onSearchChange(it)
                    viewModel.vibeInput = it
                    viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                },
                label = { Text("Enter playlist vibe") },
                leadingIcon = { Icon(painterResource(id = R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = onToggleOptions) {
                        Icon(
                            painter = painterResource(id = if (optionsExpanded) R.drawable.ic_collapse else R.drawable.ic_expand),
                            contentDescription = if (optionsExpanded) "Collapse options" else "Expand options"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (PlaylistStateHolder.recentVibes.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaylistStateHolder.recentVibes.forEach { vibe ->
                        SuggestionChip(onClick = {
                            onSearchChange(vibe)
                            viewModel.vibeInput = ""
                            viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                        }, label = { Text(vibe) })
                    }
                }
            }
        }
    }
}

// ──────────────────── ADVANCED OPTIONS ────────────────────

@Composable
fun AdvancedOptionsSection(viewModel: PlaylistViewModel) {
    var vibeExpanded by remember { mutableStateOf(false) }
    var eraExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var instrumentExpanded by remember { mutableStateOf(false) }

    Column( verticalArrangement = Arrangement.spacedBy(16.dp) ) {
        DropdownSelector("Vibe", GrokConfig.VIBE_OPTIONS, viewModel.selectedVibe, { option ->
            viewModel.selectedVibe = option
            viewModel.vibeInput = if (option != GrokConfig.VIBE_OPTIONS.first()) option else ""
        }, vibeExpanded, { vibeExpanded = it })
        DropdownSelector("Era", GrokConfig.ERA_OPTIONS, viewModel.era, { viewModel.era = it }, eraExpanded, { eraExpanded = it })
        DropdownSelector("Language", GrokConfig.LANGUAGE_OPTIONS, viewModel.language, { viewModel.language = it }, languageExpanded, { languageExpanded = it })
        DropdownSelector("Instrument", GrokConfig.INSTRUMENT_OPTIONS, viewModel.instrument, { viewModel.instrument = it }, instrumentExpanded, { instrumentExpanded = it })
        OutlinedTextField(value = viewModel.playlistName, onValueChange = { viewModel.playlistName = it }, label = { Text("Playlist name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = viewModel.artists, onValueChange = { viewModel.artists = it }, label = { Text("Artists (comma separated)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = viewModel.numSongs,
                onValueChange = { viewModel.numSongs = it },
                label = { Text("Tracks") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = viewModel.maxSongsPerArtist,
                onValueChange = { viewModel.maxSongsPerArtist = it },
                label = { Text("Max/Artist") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelector(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    onOptionSelected(option)
                    onExpandedChange(false)
                })
            }
        }
    }
}
