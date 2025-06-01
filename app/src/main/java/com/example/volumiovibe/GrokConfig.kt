package com.example.volumiovibe

object GrokConfig {
    // Prompts for xAI API
    const val SONG_LIST_PROMPT = "Yo Grok, you my DJ! Gimme a list of %d songs for %s. %s%s%s%s%sIf too few tracks match, fill with similar vibe/genre matches. Format: 'Artist - Title', one per line. No intros, no fluff, just the list."
    const val PLAYLIST_NAME_PROMPT = "Yo Grok, gimme a dope playlist name for %s. %s%s%s%sTie it to the vibe or instrument. Keep it short, max %d chars, and hella fire. Just the name!"

    // Prompt components
    fun getArtistText(artists: String?): String = artists?.let { "Inspired by artists like $it, " } ?: ""
    fun getEraText(era: String?): String = era?.let { "From the $it, " } ?: ""
    fun getMaxArtistText(maxSongsPerArtist: Int): String = "Max $maxSongsPerArtist songs per artist, "
    fun getInstrumentText(instrument: String?): String = instrument?.let {
        val genreHint = when (instrument) {
            "Steel Drums" -> "often in calypso, soca, reggae, or tropical vibes"
            "Theremin" -> "often in sci-fi, experimental, or eerie electronic"
            "Sitar" -> "often in Indian classical, psychedelic, or world music"
            "Banjo" -> "often in bluegrass, folk, or country"
            else -> "in any genre where it’s prominent"
        }
        "Songs MUST feature $it as a PRIMARY, AUDIBLE instrument, central to the track’s sound, $genreHint, "
    } ?: ""
    fun getLanguageText(language: String?): String = language?.let { "Songs MUST be sung with lyrics in $it, regardless of artist’s origin, " } ?: ""
    fun getFallbackText(instrument: String?): String = instrument?.let {
        when (instrument) {
            "Steel Drums" -> "If too few steeldrum tracks, include tropical, reggae, or percussion-heavy tracks, "
            "Theremin" -> "If too few theremin tracks, include electronic, experimental, or sci-fi vibes, "
            "Sitar" -> "If too few sitar tracks, include world music, psychedelic, or Indian-inspired tracks, "
            "Banjo" -> "If too few banjo tracks, include folk, country, or acoustic vibes, "
            else -> "If too few tracks, include genres where $it is common, "
        }
    } ?: ""

    // Playlist name max length
    const val MAX_PLAYLIST_NAME_LENGTH = 100

    // Default values
    const val DEFAULT_NUM_SONGS = "20"
    const val DEFAULT_MAX_SONGS_PER_ARTIST = "2"

    // UI Options
    val VIBE_OPTIONS = listOf(
        "Choose a vibe...",
        "Sad Boi Tears",
        "Happy as Fuck",
        "Slow but Smilin’",
        "Fast Emo Cry Shit",
        "Punk Rock Rager",
        "Trap Bangerz",
        "Stoner Chill",
        "Beach Party Jams",
        "Midnight Drive",
        "Rasta Road Trip",
        "Goth Club Bangers",
        "Retro Wave Cruisin’",
        "Festival Fire",
        "Cozy Coffee Shop",
        "Street Punk Riot",
        "Skate Park Shred",
        "Mystic Moonlight",
        "Cosmic Dawn Glow",
        "Dusty Boots Honky-Tonk",
        "Backroad Sunset Cruise",
        "Swingin’ Safari Groove",
        "Twilight Bossa Glow",
        "Custom"
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