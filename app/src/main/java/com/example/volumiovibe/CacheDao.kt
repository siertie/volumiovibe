package com.example.volumiovibe

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlaylistCache>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnoreTracks(tracks: List<TrackCache>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackCache>)

    @Query("SELECT * FROM playlist_cache ORDER BY lastUpdated DESC")
    suspend fun getPlaylists(): List<PlaylistCache>

    @Query("SELECT * FROM track_cache WHERE playlistName = :playlistName")
    suspend fun getTracksForPlaylist(playlistName: String): List<TrackCache>

    @Query("SELECT * FROM track_cache ORDER BY lastUpdated DESC")
    suspend fun getRecentTracks(): List<TrackCache>

    @Query("DELETE FROM track_cache WHERE playlistName = :playlistName")
    suspend fun clearTracksForPlaylist(playlistName: String)

    @Query("SELECT contentHash FROM playlist_cache WHERE name = :playlistName")
    suspend fun getPlaylistHash(playlistName: String): Int?
}