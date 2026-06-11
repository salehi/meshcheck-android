package io.meshcheck.contributor.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.meshcheck.contributor.ui.theme.ThemeMode

/**
 * The bottom-center gear that opens [SettingsSheet]. A filled-tonal button so it
 * stays legible over the fullscreen camera preview on the enrollment screen —
 * the same intent as the debug "Logs" chip.
 */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings")
    }
}

/** Top-level settings sections, each opening its own inner menu. */
private enum class SettingsSection { THEME }

/**
 * The settings menu, shown as a bottom sheet. The root is a list of sections;
 * tapping one drills into its own inner menu (rather than a flat dump of every
 * control). In v1 the only section is Theme — System / Light / Dark, defaulting
 * to System — but the structure leaves room for more without flattening it.
 * Selecting a theme persists it via [ThemePrefs] and re-themes the app instantly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(themePrefs: ThemePrefs, onDismiss: () -> Unit) {
    val mode by themePrefs.mode.collectAsState()
    // null = the root list; otherwise the open inner menu.
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    // While inside an inner menu, system back returns to the root rather than
    // dismissing the whole sheet.
    BackHandler(enabled = section != null) { section = null }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            when (section) {
                null -> SettingsRoot(
                    currentTheme = mode.label(),
                    onOpenSection = { section = it },
                )

                SettingsSection.THEME -> ThemeMenu(
                    selected = mode,
                    onSelect = { themePrefs.setMode(it) },
                    onBack = { section = null },
                )
            }
        }
    }
}

@Composable
private fun SettingsRoot(currentTheme: String, onOpenSection: (SettingsSection) -> Unit) {
    Text("Settings", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    SectionRow(
        label = "Theme",
        value = currentTheme,
        onClick = { onOpenSection(SettingsSection.THEME) },
    )
}

/** A drill-in row: a section label, its current value, and a chevron. */
@Composable
private fun SectionRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeMenu(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, onBack: () -> Unit) {
    SubMenuHeader(title = "Theme", onBack = onBack)
    Spacer(Modifier.height(8.dp))

    ThemeMode.entries.forEach { option ->
        ThemeOptionRow(
            label = option.label(),
            selected = option == selected,
            onSelect = { onSelect(option) },
        )
    }
}

/** A back arrow plus the inner menu's title. */
@Composable
private fun SubMenuHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ThemeOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // null onClick: the whole row is the click target (selectable above).
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
