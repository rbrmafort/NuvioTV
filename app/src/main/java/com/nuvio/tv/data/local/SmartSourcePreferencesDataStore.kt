package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.SmartSourcePreferences
import com.nuvio.tv.core.streams.SmartSourceQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartSourcePreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "smart_source_preferences"
    }

    private val enabledKey = booleanPreferencesKey("enabled")
    private val qualityKey = stringPreferencesKey("target_quality")
    private val audioLanguageKey = stringPreferencesKey("audio_language")
    private val subtitleLanguageKey = stringPreferencesKey("subtitle_language")
    private val technologiesKey = stringSetPreferencesKey("technologies")

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    val preferences: Flow<SmartSourcePreferences> = profileManager.activeProfileId
        .flatMapLatest { profileId -> factory.get(profileId, FEATURE).data }
        .map { values ->
            SmartSourcePreferences(
                enabled = values[enabledKey] ?: false,
                targetQuality = values[qualityKey] ?: SmartSourceQuality.FULL_HD.label,
                targetAudioLanguage = values[audioLanguageKey],
                targetSubtitleLanguage = values[subtitleLanguageKey] ?: "none",
                technologies = values[technologiesKey].orEmpty()
            )
        }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setTargetQuality(quality: String) {
        store().edit { it[qualityKey] = quality }
    }

    suspend fun setTargetAudioLanguage(language: String?) {
        store().edit { values ->
            if (language.isNullOrBlank()) values.remove(audioLanguageKey) else values[audioLanguageKey] = language
        }
    }

    suspend fun setTargetSubtitleLanguage(language: String?) {
        store().edit { values ->
            if (language.isNullOrBlank()) values.remove(subtitleLanguageKey) else values[subtitleLanguageKey] = language
        }
    }

    suspend fun setTechnologies(technologies: Set<String>) {
        store().edit { values ->
            if (technologies.isEmpty()) values.remove(technologiesKey) else values[technologiesKey] = technologies
        }
    }
}
