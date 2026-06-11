package io.meshcheck.contributor.settings

import android.content.Context
import io.meshcheck.contributor.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's theme choice (System / Light / Dark), defaulting to
 * System. Follows the same SharedPreferences pattern as
 * [io.meshcheck.contributor.service.ContributionPrefs], but exposes the value as
 * a [StateFlow] so the Compose tree re-themes the instant the user picks a
 * different mode in the settings sheet.
 */
class ThemePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(read())
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun setMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private fun read(): ThemeMode {
        val stored = prefs.getString(KEY_MODE, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(stored) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private companion object {
        const val PREFS_NAME = "meshcheck.settings"
        const val KEY_MODE = "theme_mode"
    }
}
