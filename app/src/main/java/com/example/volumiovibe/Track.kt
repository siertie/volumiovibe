package com.example.volumiovibe

data class Track(
    val title: String,
    val artist: String,
    val uri: String,
    val service: String,
    val albumArt: String? = null,
    val type: String = "song"
)