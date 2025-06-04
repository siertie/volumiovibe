package com.example.volumiovibe

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "track_cache",
    primaryKeys = ["uri", "playlistName"],
    indices = [Index("playlistName"), Index("uri")]
)
data class TrackCache(
    val uri: String,
    val playlistName: String,
    val title: String,
    val artist: String,
    val service: String,
    val albumArt: String? = null,
    val type: String,
    val lastUpdated: Long
)