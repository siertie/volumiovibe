package com.example.volumiovibe

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_cache", indices = [Index("lastFetched")])
data class PlaylistCache(
    @PrimaryKey val name: String,
    val lastUpdated: Long,
    val lastFetched: Long,
    val contentHash: Int? = null,
    val isEmpty: Boolean = false // Tracks empty playlists
)