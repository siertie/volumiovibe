package com.example.volumiovibe

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun PlayerControls(
    statusText: String,
    seekPosition: Float,
    trackDuration: Float,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Format time like in MainActivity
    fun formatTime(seconds: Float): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        return String.format("%d:%02d", mins, secs)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shadowElevation = 4.dp,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = seekPosition,
                onValueChange = { newValue ->
                    onSeek(newValue)
                },
                valueRange = 0f..trackDuration.coerceAtLeast(1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Text(
                text = "${formatTime(seekPosition)} / ${formatTime(trackDuration)}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_media_previous),
                        contentDescription = "Previous"
                    )
                }
                IconButton(onClick = if (isPlaying) onPause else onPlay) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_media_next),
                        contentDescription = "Next"
                    )
                }
            }
        }
    }
}