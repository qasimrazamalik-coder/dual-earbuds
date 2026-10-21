package com.dualearbuds.companion.dsp

import java.util.Locale

/** Smart EQ choices (smart-features #2). */
enum class SmartEqMode(val label: String) {
    OFF("Off"),
    BY_APP("By app"),
    BY_SOUND("By sound"),
    BOTH("Both")
}

/** "By app": the host's `source/app` name (or a package name) → preset. Pure. */
object AppEqMapper {
    private val music = listOf(
        "youtube music", "youtube.music", "ytmusic", "spotify", "deezer", "tidal", "soundcloud", "apple music", "itunes",
        "amazon music", "gaana", "jiosaavn", "saavn", "wynk", "anghami", "musicolet", "poweramp", "winamp",
        "foobar", "groove", "music"
    )
    private val movie = listOf(
        "netflix", "youtube", "prime video", "primevideo", "disney", "hotstar", "hulu", "hbo", "max", "plex",
        "vlc", "mx player", "mxplayer", "kodi", "jellyfin", "twitch", "crunchyroll", "zee5", "sonyliv",
        "shahid", "osn", "starz", "paramount", "peacock", "mpc-hc", "potplayer", "movies"
    )
    private val voice = listOf(
        "podcast", "audible", "pocket casts", "pocketcasts", "castbox", "zoom", "teams", "meet", "discord",
        "skype", "whatsapp", "telegram", "slack", "webex", "audiobook", "librivox"
    )

    /** Preset for an app name such as "Spotify", "Netflix.exe" or "com.netflix.mediaclient", or null. */
    fun presetFor(app: String?): EqPreset? {
        val a = app?.trim()?.lowercase(Locale.ROOT)?.removeSuffix(".exe")?.replace('_', ' ')
            ?.takeIf { it.isNotEmpty() } ?: return null
        // Order matters: "youtube music" must win over "youtube".
        if (music.take(3).any { a.contains(it) }) return EqPreset.BASS
        if (voice.any { a.contains(it) }) return EqPreset.VOICE
        if (movie.any { matches(a, it) }) return EqPreset.MOVIE
        if (music.any { matches(a, it) }) return EqPreset.BASS
        return null
    }

    /** Short words ("max", "meet") must match a whole word, longer ones any substring. */
    private fun matches(app: String, word: String): Boolean =
        if (word.length <= 4) Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)").containsMatchIn(app) else app.contains(word)
}

/** Maps a detected sound class to a preset (silence keeps whatever is playing). */
fun SoundClass.preset(): EqPreset? = when (this) {
    SoundClass.SPEECH -> EqPreset.VOICE
    SoundClass.MUSIC -> EqPreset.BASS
    SoundClass.MOVIE -> EqPreset.MOVIE
    SoundClass.SILENCE -> null
}

/**
 * Which preset plays (pure, unit-tested):
 * 1. A manual choice always wins (until the user picks Auto again).
 * 2. Auto + Smart EQ: By app → the app's preset; By sound → the classifier's; Both → app first, then sound.
 * 3. Otherwise the host's suggestion (`control.eq`), else Flat.
 */
object EqResolver {
    fun resolve(
        manual: EqPreset?,
        smartMode: SmartEqMode,
        appPreset: EqPreset?,
        soundPreset: EqPreset?,
        hostSuggested: EqPreset?
    ): EqPreset {
        if (manual != null) return manual
        val smart = when (smartMode) {
            SmartEqMode.OFF -> null
            SmartEqMode.BY_APP -> appPreset
            SmartEqMode.BY_SOUND -> soundPreset
            SmartEqMode.BOTH -> appPreset ?: soundPreset
        }
        return smart ?: hostSuggested ?: EqPreset.FLAT
    }
}
