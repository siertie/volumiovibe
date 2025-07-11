package com.example.volumiovibe

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive


// In PlayerControls.kt
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
    modifier: Modifier = Modifier,
    controlsEnabled: Boolean = true,
    disabledReason: String? = null,
    onToggleInfinity: () -> Unit,
    infinityMode: Boolean,
    ) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    fun formatTime(seconds: Float): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        return String.format("%d:%02d", mins, secs)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, // match what you used in TopAppBar
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ){
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = statusText, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = seekPosition,
                onValueChange = { newValue ->
                    if (controlsEnabled) onSeek(newValue)
                    else disabledReason?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                },
                valueRange = 0f..trackDuration.coerceAtLeast(1f),
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Text(text = "${formatTime(seekPosition)} / ${formatTime(trackDuration)}", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(
                    onClick = {
                        if (controlsEnabled) {
                            onPrevious()
                            coroutineScope.launch {
                                delay(500)
                                WebSocketManager.emit("getState")
                            }
                        } else disabledReason?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    },
                    enabled = controlsEnabled
                ) {
                    Icon(painter = painterResource(id = android.R.drawable.ic_media_previous), contentDescription = "Previous")
                }
                IconButton(
                    onClick = {
                        if (controlsEnabled) {
                            if (isPlaying) onPause() else onPlay()
                            coroutineScope.launch {
                                delay(500)
                                WebSocketManager.emit("getState")
                            }
                        } else disabledReason?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    },
                    enabled = controlsEnabled
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(
                    onClick = {
                        if (controlsEnabled) {
                            onNext()
                            coroutineScope.launch {
                                delay(500)
                                WebSocketManager.emit("getState")
                            }
                        } else disabledReason?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    },
                    enabled = controlsEnabled
                ) {
                    Icon(painter = painterResource(id = android.R.drawable.ic_media_next), contentDescription = "Next")
                }
                IconButton(
                    onClick = {
                        if (controlsEnabled) {
                            onToggleInfinity()
                            coroutineScope.launch {
                                delay(500)
                                WebSocketManager.emit("getState")
                            }
                        } else disabledReason?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    },
                    enabled = controlsEnabled
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AllInclusive,
                        contentDescription = "Infinity Playback",
                        tint = if (infinityMode)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
