package com.example.volumiovibe

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class XAiApi(private val apiKey: String) {
    private val client = OkHttpClient()
    private val mediaType = "application/json".toMediaType()

    init { Log.d("XAiApi", "API Key: $apiKey") }

    suspend fun generatePlaylistName(
        vibe: String,
        artists: String? = null,
        era: String? = null,
        instrument: String? = null,
        language: String? = null
    ): String = withContext(Dispatchers.IO) {
        val artistText = GrokConfig.getArtistText(artists)
        val eraText = GrokConfig.getEraText(era)
        val instrumentText = GrokConfig.getInstrumentText(instrument)
        val languageText = GrokConfig.getLanguageText(language)
        val prompt = String.format(
            GrokConfig.PLAYLIST_NAME_PROMPT,
            vibe, artistText, eraText, instrumentText, languageText, GrokConfig.MAX_PLAYLIST_NAME_LENGTH
        )
        val payload = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You a dope AI namer, droppin’ short, fire playlist names.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("model", "grok-3-latest")
            put("stream", false)
            put("temperature", 0.7)
        }
        val request = Request.Builder()
            .url("https://api.x.ai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        try {
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            Log.e("XAiApi", "Failed to get playlist name: ${e.message}")
            "Grok’s Fire Mix"
        }
    }

    suspend fun generateSongList(
        vibe: String,
        numSongs: Int,
        artists: String? = null,
        era: String? = null,
        maxSongsPerArtist: Int,
        instrument: String? = null,
        language: String? = null,
        excludedSongs: List<Track> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        val artistText = GrokConfig.getArtistText(artists)
        val eraText = GrokConfig.getEraText(era)
        val maxArtistText = GrokConfig.getMaxArtistText(maxSongsPerArtist)
        val instrumentText = GrokConfig.getInstrumentText(instrument)
        val languageText = GrokConfig.getLanguageText(language)
        val excludeText = if (excludedSongs.isNotEmpty()) {
            // Clean title by removing anything in parentheses and format as "Artist - Title"
            val songDetails = excludedSongs.map { track ->
                val cleanTitle = track.title.replace("\\s*\\([^)]+\\)".toRegex(), "").trim()
                "${track.artist} - $cleanTitle"
            }.joinToString(", ")
            "DO NOT INCLUDE these tracks: $songDetails."
        } else ""
        val prompt = String.format(
            GrokConfig.SONG_LIST_PROMPT,
            numSongs, vibe, artistText, eraText, maxArtistText, instrumentText, languageText, excludeText
        )
        Log.d("EXCLUDED_SONGS_DEBUG", "Final Prompt: $prompt")
        val payload = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You a dope AI DJ, spittin’ song lists in 'Artist - Title' format.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("model", "grok-3-latest")
            put("stream", false)
            put("temperature", 0)
        }
        val request = Request.Builder()
            .url("https://api.x.ai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        try {
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            Log.e("XAiApi", "Failed to get song list: ${e.message}")
            null
        }
    }
}