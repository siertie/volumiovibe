package com.example.volumiovibe

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PlaylistCache::class, TrackCache::class], version = 7)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "volumio-cache"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playlist_cache ADD COLUMN contentHash INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE track_cache ADD COLUMN albumArt TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE track_cache_new (
                        uri TEXT NOT NULL,
                        playlistName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        service TEXT NOT NULL,
                        albumArt TEXT,
                        type TEXT NOT NULL,
                        lastUpdated INTEGER NOT NULL,
                        PRIMARY KEY (uri, playlistName)
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX index_track_cache_playlistName ON track_cache_new (playlistName)")
                database.execSQL("CREATE INDEX index_track_cache_uri ON track_cache_new (uri)")
                database.execSQL("""
                    INSERT INTO track_cache_new (uri, playlistName, title, artist, service, albumArt, type, lastUpdated)
                    SELECT uri, playlistName, title, artist, service, albumArt, type, lastUpdated
                    FROM track_cache
                """.trimIndent())
                database.execSQL("DROP TABLE track_cache")
                database.execSQL("ALTER TABLE track_cache_new RENAME TO track_cache")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playlist_cache ADD COLUMN isEmpty INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}