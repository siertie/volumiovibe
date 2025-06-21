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

class OpenAiApi(private val apiKey: String) {
    private val client = OkHttpClient()
    private val mediaType = "application/json".toMediaType()

    suspend fun generatePlaylistName(
        vibe: String,
        artists: String? = null,
        era: String? = null,
        instrument: String? = null,
        language: String? = null
    ): String = withContext(Dispatchers.IO) {
        val artistText = OpenAiConfig.getArtistText(artists)
        val eraText = OpenAiConfig.getEraText(era)
        val instrumentText = OpenAiConfig.getInstrumentText(instrument)
        val languageText = OpenAiConfig.getLanguageText(language)
        val prompt = String.format(
            OpenAiConfig.PLAYLIST_NAME_PROMPT,
            vibe, artistText, eraText, instrumentText, languageText, OpenAiConfig.MAX_PLAYLIST_NAME_LENGTH
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
            put("model", "gpt-4.1") // Set to your requested model
            put("stream", false)
            put("temperature", 0.7)
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        try {
            val response = client.newCall(request).execute()
            val bodyString = response.body?.string() ?: "{}"
            Log.d("OpenAiApi", "OpenAI RAW RESPONSE: $bodyString")
            val json = JSONObject(bodyString)
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            Log.e("OpenAiApi", "Failed to get playlist name: ${e.message}")
            "AI Fire Mix"
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
        val artistText = OpenAiConfig.getArtistText(artists)
        val eraText = OpenAiConfig.getEraText(era)
        val maxArtistText = OpenAiConfig.getMaxArtistText(maxSongsPerArtist)
        val instrumentText = OpenAiConfig.getInstrumentText(instrument)
        val languageText = OpenAiConfig.getLanguageText(language)
        val excludeText = if (excludedSongs.isNotEmpty()) {
            val songDetails = excludedSongs.map { track ->
                val cleanTitle = track.title.replace("\\s*\\([^)]+\\)".toRegex(), "").trim()
                "${track.artist} - $cleanTitle"
            }.joinToString(", ")
            "DO NOT INCLUDE these tracks: $songDetails."
        } else ""
        val prompt = String.format(
            OpenAiConfig.SONG_LIST_PROMPT,
            numSongs, vibe, artistText, eraText, maxArtistText, instrumentText, languageText, excludeText,  maxSongsPerArtist
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
            put("model", "gpt-4.1") // Set to your requested model
            put("stream", false)
            put("temperature", 0)
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        try {
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            Log.e("OpenAiApi", "Failed to get song list: ${e.message}")
            null
        }
    }
}
