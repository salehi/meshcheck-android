package io.meshcheck.contributor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The user's theme preference. [SYSTEM] follows the device's dark-mode setting;
 * [LIGHT] and [DARK] force one regardless of it. Stored by name in
 * [io.meshcheck.contributor.settings.ThemePrefs], so do not rename the entries
 * without a migration.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The app-wide theme. Resolves [mode] to a concrete light/dark scheme and wraps
 * [content] in a Material3 theme.
 *
 * The schemes are Material3's stock [lightColorScheme]/[darkColorScheme]: the
 * light one matches the app's previous bare-`MaterialTheme` appearance, and the
 * dark one is the matching dark palette. No dynamic (Material You) color — it
 * would diverge per device and minSdk 21 can't use it anyway.
 */
@Composable
fun MeshCheckTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
