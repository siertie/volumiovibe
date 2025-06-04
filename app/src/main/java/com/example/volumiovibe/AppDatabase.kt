package com.example.volumiovibe

import android.app.Application
import androidx.room.*

@Entity(tableName = "playlist_cache")
data class PlaylistCache(
    @PrimaryKey val name: String,
    val lastUpdated: Long
)

@Entity(tableName = "track_cache")
data class TrackCache(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val playlistName: String,
    val service: String,
    val type: String,
    val lastUpdated: Long
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM playlist_cache ORDER BY lastUpdated DESC")
    suspend fun getPlaylists(): List<PlaylistCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlaylistCache>)

    @Query("SELECT * FROM track_cache ORDER BY lastUpdated DESC LIMIT 200")
    suspend fun getRecentTracks(): List<TrackCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackCache>)

    @Query("DELETE FROM track_cache WHERE playlistName = :playlistName")
    suspend fun clearTracksForPlaylist(playlistName: String)

    @Query("DELETE FROM track_cache")
    suspend fun clearAllTracks()
}

@Database(entities = [PlaylistCache::class, TrackCache::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(application: Application): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    application,
                    AppDatabase::class.java,
                    "volumio-cache"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}