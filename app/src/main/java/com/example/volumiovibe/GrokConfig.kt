package com.example.volumiovibe

object GrokConfig {
    // Prompts for xAI API
    const val SONG_LIST_PROMPT = "Yo Grok, you my DJ! Gimme a list of %d songs for %s. %s%s%s%sFormat it like 'Artist - Title' with each song on a new line. No extra bullshit, just the list."
    const val PLAYLIST_NAME_PROMPT = "Yo Grok, gimme a dope playlist name for %s. %s%s%s%sKeep it short, max %d chars, and hella fire. No extra fluff, just the name!"

    // Prompt components
    fun getArtistText(artists: String?): String = artists?.let { "Inspired by artists like $it, " } ?: ""
    fun getEraText(era: String?): String = era?.let { "From the $it, " } ?: ""
    fun getMaxArtistText(maxSongsPerArtist: Int): String = "Max $maxSongsPerArtist songs per artist, "
    fun getInstrumentText(instrument: String?): String = instrument?.let { "Songs MUST have the $it as a PRIMARY, AUDIBLE instrument, central to the track’s sound, " } ?: ""
    fun getLanguageText(language: String?): String = language?.let { "In $it, " } ?: ""

    // Playlist name max length
    const val MAX_PLAYLIST_NAME_LENGTH = 20

    // Default values
    const val DEFAULT_NUM_SONGS = "20"
    const val DEFAULT_MAX_SONGS_PER_ARTIST = "2"

    // UI Options
    val VIBE_OPTIONS = listOf(
        "Choose a vibe...", "Sad Boi Tears", "Happy as Fuck", "Slow but Smilin’",
        "Fast Emo Cry Shit", "Punk Rock Rager", "Trap Bangerz", "Stoner Chill"
    )
    val ERA_OPTIONS = listOf(
        "Any Era", "1960s", "1970s", "1980s", "1990s", "2000s", "2010s", "2020s"
    )
    val LANGUAGE_OPTIONS = listOf(
        "Any Language", "English", "Dutch", "German", "French", "Spanish"
    )
    val INSTRUMENT_OPTIONS = listOf(
        "None", "Violin", "Steel Drums", "Contrabass", "Theremin", "Sitar", "Banjo"
    )
}