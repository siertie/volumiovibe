package com.example.volumiovibe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy

@Composable
fun TrackItem(
    track: Track,
    index: Int? = null,
    onClick: (() -> Unit)? = null,
    actionButtons: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
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
                else -> "http://192.168.0.250:3000${track.albumArt}"
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
                    .clip(MaterialTheme.shapes.small)
                    .constrainAs(albumArt) {
                        start.linkTo(parent.start)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                placeholder = painterResource(id = R.drawable.placeholder),
                error = painterResource(id = R.drawable.ic_error)
            )
            Text(
                text = if (index != null) "${index + 1}. ${track.title}" else track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                },
                content = actionButtons
            )
        }
    }
}