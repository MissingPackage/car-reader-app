package io.carreader.app.settings

import android.content.Context
import android.content.SharedPreferences

/** User settings. Android implementation: [SharedPreferencesSettings]; tests use [InMemorySettings]. */
interface SettingsRepository {
    /** "Allow operations that modify the car". Off by default. */
    var mutationsEnabled: Boolean
    var wifiHost: String
    var wifiPort: Int

    companion object {
        const val DEFAULT_WIFI_HOST = "192.168.0.10"
        const val DEFAULT_WIFI_PORT = 35000
    }
}

class SharedPreferencesSettings(context: Context) : SettingsRepository {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("car_reader_settings", Context.MODE_PRIVATE)

    override var mutationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUTATIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTATIONS, value).apply()

    override var wifiHost: String
        get() = prefs.getString(KEY_HOST, null) ?: SettingsRepository.DEFAULT_WIFI_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    override var wifiPort: Int
        get() = prefs.getInt(KEY_PORT, SettingsRepository.DEFAULT_WIFI_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    private companion object {
        const val KEY_MUTATIONS = "mutations_enabled"
        const val KEY_HOST = "wifi_host"
        const val KEY_PORT = "wifi_port"
    }
}

class InMemorySettings(
    override var mutationsEnabled: Boolean = false,
    override var wifiHost: String = SettingsRepository.DEFAULT_WIFI_HOST,
    override var wifiPort: Int = SettingsRepository.DEFAULT_WIFI_PORT,
) : SettingsRepository
