package com.emir.citymapapp
// SettingsPreferences.kt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Enum for theme settings (remains the same)
enum class ThemeSetting {
    SYSTEM, LIGHT, DARK
}

// Create a DataStore instance named "settings"
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsPreferences(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        // Keys for DataStore
        private val THEME_KEY = stringPreferencesKey("theme_preference")
        private val LOCALE_KEY = stringPreferencesKey("locale_preference")
        private val PIN_SIZE_KEY = floatPreferencesKey("pin_size_preference")
        private val TEXT_SIZE_KEY = floatPreferencesKey("text_size_preference")
        // NEW: Key for startup warning confirmation
        val STARTUP_WARNING_ACCEPTED_KEY = booleanPreferencesKey("startup_warning_accepted")


        // Default Values
        const val DEFAULT_LOCALE = "bs" // CHANGED: Default to Bosnian
        const val DEFAULT_PIN_SIZE = 1.0f // Mapbox default size is usually 1.0
        const val DEFAULT_TEXT_SIZE = 10.0f // Mapbox default text size
    }

    // --- Flows to observe settings ---

    // Flow for theme
    val themeSetting: Flow<ThemeSetting> = appContext.dataStore.data
        .map { preferences ->
            ThemeSetting.valueOf(preferences[THEME_KEY] ?: ThemeSetting.SYSTEM.name)
        }

    // Flow for locale
    val localeSetting: Flow<String> = appContext.dataStore.data
        .map { preferences ->
            preferences[LOCALE_KEY] ?: DEFAULT_LOCALE
        }

    // Flow for pin size
    val pinSizeSetting: Flow<Float> = appContext.dataStore.data
        .map { preferences ->
            preferences[PIN_SIZE_KEY] ?: DEFAULT_PIN_SIZE
        }

    // Flow for text size
    val textSizeSetting: Flow<Float> = appContext.dataStore.data
        .map { preferences ->
            preferences[TEXT_SIZE_KEY] ?: DEFAULT_TEXT_SIZE
        }

    // NEW: Flow for startup warning confirmation
    val startupWarningAccepted: Flow<Boolean> = appContext.dataStore.data
        .map { preferences ->
            preferences[STARTUP_WARNING_ACCEPTED_KEY] ?: false
        }


    // --- Functions to save settings ---

    // Save theme
    suspend fun saveThemeSetting(themeSetting: ThemeSetting) {
        appContext.dataStore.edit { preferences ->
            preferences[THEME_KEY] = themeSetting.name
        }
    }

    // Save locale
    suspend fun saveLocaleSetting(localeCode: String) {
        appContext.dataStore.edit { preferences ->
            preferences[LOCALE_KEY] = localeCode
        }
    }

    // Save pin size
    suspend fun savePinSizeSetting(size: Float) {
        appContext.dataStore.edit { preferences ->
            preferences[PIN_SIZE_KEY] = size
        }
    }

    // Save text size
    suspend fun saveTextSizeSetting(size: Float) {
        appContext.dataStore.edit { preferences ->
            preferences[TEXT_SIZE_KEY] = size
        }
    }

    // NEW: Save startup warning confirmation
    suspend fun saveStartupWarningAccepted(accepted: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[STARTUP_WARNING_ACCEPTED_KEY] = accepted
        }
    }
}