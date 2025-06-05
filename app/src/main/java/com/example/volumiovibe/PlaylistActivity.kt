package com.example.volumiovibe

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import com.example.volumiovibe.ui.theme.AppTheme
import com.example.volumiovibe.ui.theme.ThemeMode
import androidx.lifecycle.ViewModelProvider

// Playlist data class
data class Playlist(val name: String, val tracks: List<Track>)

// PlaylistStateHolder
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
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            AppTheme(themeMode = themeMode) {
                PlaylistScreen(
                    viewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return PlaylistViewModel(application) as T
                        }
                    }),
                    themeMode = themeMode,
                    onThemeModeChange = { newMode -> themeMode = newMode }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val TAG = "PlaylistScreen"
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf(viewModel.vibeInput) }
    var optionsExpanded by remember { mutableStateOf(false) }
    var expandedPlaylist by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    Log.d(TAG, "PlaylistScreen initialized with vibeOptions: ${GrokConfig.VIBE_OPTIONS.joinToString()}")

    LaunchedEffect(Unit) {
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
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VolumioVibe", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = {
                        onThemeModeChange(
                            if (themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
                        )
                    }) {
                        Icon(
                            painter = painterResource(
                                id = if (themeMode == ThemeMode.DARK) R.drawable.ic_sun else R.drawable.ic_moon
                            ),
                            contentDescription = "Toggle theme"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!viewModel.isLoading) viewModel.generateAiPlaylist(context) },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add_playlist),
                    contentDescription = "Create playlist"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            VibeSection(
                viewModel = viewModel,
                searchQuery = searchQuery,
                onSearchChange = { newQuery -> searchQuery = newQuery },
                onToggleOptions = { optionsExpanded = !optionsExpanded },
                optionsExpanded = optionsExpanded
            )
            if (optionsExpanded) {
                AdvancedOptionsSection(viewModel)
            }
            PlaylistsSection(
                viewModel = viewModel,
                expandedPlaylist = expandedPlaylist,
                onPlaylistToggle = { playlistName -> expandedPlaylist = playlistName },
                onDelete = { playlistName -> showDeleteDialog = playlistName },
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Playlist?") },
            text = { Text("You sure you wanna delete '${showDeleteDialog}'? This shit’s permanent, fam!") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(showDeleteDialog!!)
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun VibeSection(
    viewModel: PlaylistViewModel,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggleOptions: () -> Unit,
    optionsExpanded: Boolean
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Set Your Vibe",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    onSearchChange(it)
                    viewModel.vibeInput = it
                    viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                },
                label = { Text("Enter playlist vibe") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onToggleOptions) {
                        Icon(
                            painter = painterResource(
                                id = if (optionsExpanded) R.drawable.ic_collapse else R.drawable.ic_expand
                            ),
                            contentDescription = if (optionsExpanded) "Collapse Options" else "Expand Options"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (PlaylistStateHolder.recentVibes.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PlaylistStateHolder.recentVibes.take(5).forEach { vibe ->
                        SuggestionChip(
                            onClick = {
                                onSearchChange(vibe)
                                viewModel.vibeInput = ""
                                viewModel.selectedVibe = GrokConfig.VIBE_OPTIONS.first()
                            },
                            label = { Text(vibe) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedOptionsSection(viewModel: PlaylistViewModel) {
    var vibeExpanded by remember { mutableStateOf(false) }
    var eraExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var instrumentExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Fine-Tune Your Vibe",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            DropdownSelector(
                label = "Vibe",
                options = GrokConfig.VIBE_OPTIONS,
                selectedOption = viewModel.selectedVibe,
                onOptionSelected = { option ->
                    viewModel.selectedVibe = option
                    viewModel.vibeInput = if (option != GrokConfig.VIBE_OPTIONS.first()) option else ""
                },
                expanded = vibeExpanded,
                onExpandedChange = { vibeExpanded = it }
            )

            DropdownSelector(
                label = "Era",
                options = GrokConfig.ERA_OPTIONS,
                selectedOption = viewModel.era,
                onOptionSelected = { option -> viewModel.era = option },
                expanded = eraExpanded,
                onExpandedChange = { eraExpanded = it }
            )

            DropdownSelector(
                label = "Language",
                options = GrokConfig.LANGUAGE_OPTIONS,
                selectedOption = viewModel.language,
                onOptionSelected = { option -> viewModel.language = option },
                expanded = languageExpanded,
                onExpandedChange = { languageExpanded = it }
            )

            DropdownSelector(
                label = "Instrument",
                options = GrokConfig.INSTRUMENT_OPTIONS,
                selectedOption = viewModel.instrument,
                onOptionSelected = { option -> viewModel.instrument = option },
                expanded = instrumentExpanded,
                onExpandedChange = { instrumentExpanded = it }
            )

            OutlinedTextField(
                value = viewModel.playlistName,
                onValueChange = { viewModel.playlistName = it },
                label = { Text("Playlist Name (blank for Grok’s pick)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = viewModel.artists,
                onValueChange = { viewModel.artists = it },
                label = { Text("Artists (comma separated)") },
                modifier = Modifier.fillMaxWidth()
            )

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
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
fun PlaylistsSection(
    viewModel: PlaylistViewModel,
    expandedPlaylist: String?,
    onPlaylistToggle: (String?) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
    ) {
        Column {
            ListItem(
                headlineContent = { Text("Your Playlists") },
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_playlist),
                        contentDescription = null
                    )
                }
            )
            Divider()
            when {
                viewModel.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                viewModel.playlists.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_music_note),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("No playlists yet!")
                            Text("Create your first playlist", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(viewModel.playlists) { _, playlist ->
                            PlaylistItem(
                                playlist = playlist,
                                isExpanded = expandedPlaylist == playlist.name,
                                onToggle = {
                                    onPlaylistToggle(if (expandedPlaylist == playlist.name) null else playlist.name)
                                    if (expandedPlaylist != playlist.name) {
                                        viewModel.browsePlaylistTracks(playlist.name)
                                    }
                                },
                                onPlay = { viewModel.playPlaylist(playlist.name) },
                                onDelete = { onDelete(playlist.name) },
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistItem(
    playlist: Playlist,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    viewModel: PlaylistViewModel
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .padding(8.dp)
            .clickable { onToggle() }
    ) {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_album),
                    contentDescription = "Playlist",
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playlist.tracks.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPlay) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play),
                        contentDescription = "Play"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (isExpanded && playlist.tracks.isNotEmpty()) {
                TrackList(playlist.tracks, playlist.name, viewModel)
            }
        }
    }
}

@Composable
fun TrackList(tracks: List<Track>, playlistName: String, viewModel: PlaylistViewModel) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
    ) {
        itemsIndexed(tracks) { _, track ->
            TrackItem(
                track = track,
                actionButtons = {
                    IconButton(onClick = {
                        viewModel.removeFromPlaylist(playlistName, track.uri)
                        Toast.makeText(context, "${track.title} bounced from $playlistName!", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = "Remove"
                        )
                    }
                }
            )
        }
    }
}