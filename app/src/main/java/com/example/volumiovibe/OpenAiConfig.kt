package com.example.volumiovibe

object OpenAiConfig {
    // ===== PROMPT TEMPLATES =====

    // --- SONG LIST PROMPT ---
    // maxSongsPerArtist must be inserted for rule #4
    val SONG_LIST_PROMPT = """
You are an AI DJ.
Generate a list of %d songs for this vibe: %s.
%s%s%s%s%s

Rules:
1. DO NOT INCLUDE these tracks: %s
2. If too few songs match, fill with tracks of a similar vibe or genre.
3. Format: 'Artist - Title' — one per line, no numbering, no intro, no commentary.
4. Each artist may appear a maximum of %d times.
5. Do not include duplicate tracks.
6. Return ONLY the list of songs. No explanations or extra text.
""".trimIndent()

    // --- PLAYLIST NAME PROMPT ---
    val PLAYLIST_NAME_PROMPT = """
You are an AI playlist namer.
Create a unique, creative, and catchy playlist name for this vibe: %s.
%s%s%s%s

Rules:
1. Tie the name to the vibe, era, or a key instrument if provided.
2. Maximum length: %d characters.
3. Make it modern and memorable.
4. Return ONLY the name, no commentary or explanation.
""".trimIndent()

    // ===== PROMPT HELPERS =====

    fun getArtistText(artists: String?): String =
        artists?.takeIf { it.isNotBlank() }?.let { "Inspired by artists like $it.\n" } ?: ""

    fun getEraText(era: String?): String =
        era?.takeIf { it.isNotBlank() && it != ERA_OPTIONS.first() }?.let { "From the $it era.\n" } ?: ""

    fun getMaxArtistText(maxSongsPerArtist: Int): String =
        "" // Handled directly in rule #4

    fun getInstrumentText(instrument: String?): String =
        instrument?.takeIf { it.isNotBlank() && it != INSTRUMENT_OPTIONS.first() }?.let {
            """
            Songs MUST prominently feature $it as a PRIMARY, AUDIBLE instrument in each track.
            If too few songs exist with $it, fill the remaining spots with tracks where $it is clearly present and easy to hear.
            Do NOT include songs where $it is minor, barely noticeable, or absent.
            """.trimIndent() + "\n"
        } ?: ""

    fun getLanguageText(language: String?): String =
        language?.takeIf { it.isNotBlank() && it != LANGUAGE_OPTIONS.first() }?.let {
            "Songs MUST have lyrics in $it.\n"
        } ?: ""

    // ===== PLAYLIST SETTINGS =====

    const val MAX_EXCLUDED_URIS = 50  // <= Reduce for better GPT performance
    const val MAX_PLAYLIST_NAME_LENGTH = 50

    // Defaults
    const val DEFAULT_NUM_SONGS = "20"
    const val DEFAULT_MAX_SONGS_PER_ARTIST = "2"

    // UI dropdowns (MUST be val, not const val!)
    val VIBE_OPTIONS = listOf(
        "Choose a vibe...",
        "Sad Boi Tears", "Happy as Fuck", "Slow but Smilin’", "Fast Emo Cry Shit", "Punk Rock Rager",
        "Trap Bangerz", "Stoner Chill", "Beach Party Jams", "Midnight Drive", "Rasta Road Trip",
        "Goth Club Bangers", "Retro Wave Cruisin’", "Festival Fire", "Cozy Coffee Shop",
        "Street Punk Riot", "Skate Park Shred", "Mystic Moonlight", "Cosmic Dawn Glow",
        "Dusty Boots Honky-Tonk", "Backroad Sunset Cruise", "Swingin’ Safari Groove",
        "Twilight Bossa Glow", "Custom"
    )

    val ERA_OPTIONS = listOf(
        "Any Era", "1960s", "1970s", "1980s", "1990s", "2000s", "2010s", "2020s"
    )

    val LANGUAGE_OPTIONS = listOf(
        "Any Language", "English", "Dutch", "German", "French", "Spanish"
    )

    val INSTRUMENT_OPTIONS = listOf(
        "None", "Violin", "Steel Drums", "Contrabass", "Theremin", "Sitar", "Banjo", "Custom"
    )
}
