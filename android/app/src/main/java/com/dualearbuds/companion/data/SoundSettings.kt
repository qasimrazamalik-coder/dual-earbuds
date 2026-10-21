package com.dualearbuds.companion.data

import com.dualearbuds.companion.dsp.DialogueMode
import com.dualearbuds.companion.dsp.EqPreset
import com.dualearbuds.companion.dsp.SmartEqMode
import com.dualearbuds.companion.hearing.HearingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Listener sound settings (EQ, smart features, calibration, hearing profiles). Observable, persisted in [Prefs].
 * Every choice here is optional; manual choices always win over automatic ones.
 */
object SoundSettings {
    private const val K_EQ = "eq_manual" // preset wire name, or absent = Auto
    private const val K_SMART_EQ = "smart_eq"
    private const val K_DIALOGUE = "dialogue"
    private const val K_LATENCY = "output_latency" // per Bluetooth device name
    private const val K_PROFILES = "hearing_profiles"
    private const val K_PROFILE_FOR = "hearing_profile_for" // earbuds name -> profile id

    /** The user's own EQ pick, or null = Auto (host suggestion / Smart EQ). */
    private val _eqManual = MutableStateFlow<EqPreset?>(null)
    val eqManual: StateFlow<EqPreset?> = _eqManual.asStateFlow()

    private val _smartEq = MutableStateFlow(SmartEqMode.OFF)
    val smartEq: StateFlow<SmartEqMode> = _smartEq.asStateFlow()

    private val _dialogue = MutableStateFlow(DialogueMode.OFF)
    val dialogue: StateFlow<DialogueMode> = _dialogue.asStateFlow()

    private val _latency = MutableStateFlow<Map<String, Double>>(emptyMap())
    val outputLatency: StateFlow<Map<String, Double>> = _latency.asStateFlow()

    private val _profiles = MutableStateFlow<List<HearingProfile>>(emptyList())
    val profiles: StateFlow<List<HearingProfile>> = _profiles.asStateFlow()

    private val _profileFor = MutableStateFlow<Map<String, String>>(emptyMap())
    val profileForEarbuds: StateFlow<Map<String, String>> = _profileFor.asStateFlow()

    fun init() {
        _eqManual.value = EqPreset.fromWire(Prefs.getString(K_EQ))
        _smartEq.value = Prefs.enumOr(Prefs.getString(K_SMART_EQ), SmartEqMode.OFF)
        _dialogue.value = Prefs.enumOr(Prefs.getString(K_DIALOGUE), DialogueMode.OFF)
        _latency.value = Codecs.decodeDoubles(Prefs.getString(K_LATENCY))
        _profiles.value = Codecs.decodeProfiles(Prefs.getString(K_PROFILES))
        _profileFor.value = Codecs.decodeStrings(Prefs.getString(K_PROFILE_FOR))
    }

    /** null = Auto. */
    fun setEqManual(preset: EqPreset?) {
        _eqManual.value = preset
        Prefs.putString(K_EQ, preset?.wire)
    }

    fun setSmartEq(mode: SmartEqMode) {
        _smartEq.value = mode
        Prefs.putString(K_SMART_EQ, mode.name)
    }

    fun setDialogue(mode: DialogueMode) {
        _dialogue.value = mode
        Prefs.putString(K_DIALOGUE, mode.name)
    }

    /** Calibrated output latency for [deviceName] (Bluetooth name, or "Phone speaker"). */
    fun latencyFor(deviceName: String): Double? = _latency.value[deviceName]

    @Synchronized
    fun setLatency(deviceName: String, ms: Double?) {
        val m = LinkedHashMap(_latency.value)
        if (ms == null) m.remove(deviceName) else m[deviceName] = ms
        _latency.value = m
        Prefs.putString(K_LATENCY, Codecs.encodeDoubles(m))
    }

    @Synchronized
    fun saveProfile(profile: HearingProfile) {
        val list = _profiles.value.filter { it.id != profile.id } + profile
        _profiles.value = list
        Prefs.putString(K_PROFILES, Codecs.encodeProfiles(list))
    }

    @Synchronized
    fun deleteProfile(id: String) {
        val list = _profiles.value.filter { it.id != id }
        _profiles.value = list
        Prefs.putString(K_PROFILES, Codecs.encodeProfiles(list))
        val map = _profileFor.value.filterValues { it != id }
        _profileFor.value = map
        Prefs.putString(K_PROFILE_FOR, Codecs.encodeStrings(map))
    }

    /** Uses [profileId] (null = none) whenever [earbudsName] is the output. */
    @Synchronized
    fun assignProfile(earbudsName: String, profileId: String?) {
        val m = LinkedHashMap(_profileFor.value)
        if (profileId == null) m.remove(earbudsName) else m[earbudsName] = profileId
        _profileFor.value = m
        Prefs.putString(K_PROFILE_FOR, Codecs.encodeStrings(m))
    }

    fun profileFor(earbudsName: String): HearingProfile? {
        val id = _profileFor.value[earbudsName] ?: return null
        return _profiles.value.firstOrNull { it.id == id }
    }

    /** Name used for "the current output" when no Bluetooth earbuds are connected. */
    const val PHONE_OUTPUT = "This phone"
}
