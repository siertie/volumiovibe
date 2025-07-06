package com.example.volumiovibe

object Utils {
    fun getAlbumArt(track: Track, baseUrl: String = "http://192.168.0.250:3000"): String {
        return when {
            track.albumArt.isNullOrEmpty() -> "https://via.placeholder.com/64"
            track.albumArt.startsWith("http") -> track.albumArt
            else -> "$baseUrl${track.albumArt}"
        }
    }
}