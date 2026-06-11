package io.meshcheck.contributor.contribution

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.meshcheck.contributor.AppContainer
import io.meshcheck.contributor.service.ContributionService
import io.meshcheck.protocol.AvailableUpdate
import io.meshcheck.protocol.ConnectionState
import io.meshcheck.protocol.StopReason

/**
 * The enrolled-device screen. Shows the things the spec allows — state,
 * jobs, and the Start/Stop control — plus the dim Unlink control.
 *
 * The screen keeps **state** and **action** as separate elements, but both
 * derive from the one live [ConnectionState]: the indicator reports what is
 * true now, and the button is labelled with what pressing it does. Because
 * they share a source, the two can never contradict each other — a stale
 * "Stop" can't sit over a "Paused" indicator.
 */
@Composable
fun ContributorScreen(
    container: AppContainer,
    onUnlinked: () -> Unit,
) {
    val context = LocalContext.current
    val connectionState by container.agentClient.state.collectAsState()
    val stats by container.agentClient.stats.collectAsState()
    val update by container.agentClient.updateAvailable.collectAsState()

    // The button's verb is derived from the live connection state — the same
    // source the indicator uses — so the action and the state can't disagree.
    // [pending] holds the just-tapped intent so the button responds instantly,
    // then yields to the real state the moment it moves.
    val contributing = connectionState.isContributing()
    var pending by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(connectionState) { pending = null }
    val showStop = pending ?: contributing

    var batteryPrompted by rememberSaveable { mutableStateOf(false) }
    var showUnlinkConfirm by remember { mutableStateOf(false) }

    // If the user left contribution on, make sure the service is actually
    // running: the process may have been killed since, leaving the node idle
    // despite the saved intent. Restarting it here converges the live state to
    // that intent, so opening the app shows the truth instead of a stale state.
    LaunchedEffect(Unit) {
        if (container.contributionPrefs.userWantsConnected) {
            ContributionService.start(context)
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The service runs regardless; without it the notification is hidden. */ }

    fun startContributing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ContributionService.start(context)
        pending = true
        if (!batteryPrompted && !BatteryOptimization.isExempt(context)) {
            BatteryOptimization.requestExemption(context)
            batteryPrompted = true
        }
    }

    fun stopContributing() {
        ContributionService.stop(context)
        pending = false
    }

    // The app can't self-update; send the user to the store listing. Falls back
    // to the web listing when the Play Store app isn't installed (direct-APK
    // installs).
    fun openAppStore() {
        val uri = "details?id=${context.packageName}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://$uri")))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/$uri")),
            )
        }
    }

    ContributorContent(
        indicator = connectionState.toIndicator(),
        jobsConfirmed = stats.confirmed,
        contributing = showStop,
        update = update,
        onUpdate = ::openAppStore,
        onToggle = { if (showStop) stopContributing() else startContributing() },
        onUnlink = { showUnlinkConfirm = true },
    )

    if (showUnlinkConfirm) {
        UnlinkConfirmDialog(
            onConfirm = {
                showUnlinkConfirm = false
                ContributionService.stop(context)
                container.credentialStore.clear()
                onUnlinked()
            },
            onDismiss = { showUnlinkConfirm = false },
        )
    }
}

@Composable
private fun ContributorContent(
    indicator: StateIndicator,
    jobsConfirmed: Int,
    contributing: Boolean,
    update: AvailableUpdate?,
    onUpdate: () -> Unit,
    onToggle: () -> Unit,
    onUnlink: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Update nudge — non-blocking; contribution continues regardless.
        if (update != null) {
            UpdateBanner(update, onUpdate)
            Spacer(Modifier.height(24.dp))
        }

        // State indicator — read-only.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(indicator.dotArgb)),
            )
            Spacer(Modifier.width(8.dp))
            Text(indicator.label, style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = indicator.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        StatRow("Jobs since you started", "$jobsConfirmed")

        Spacer(Modifier.height(32.dp))

        // Action — labelled with the verb for the *other* state.
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (contributing) "Stop contributing" else "Start contributing")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (contributing) {
                "Pauses new jobs. This device stays linked."
            } else {
                "Your phone starts taking jobs, even while the app is closed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        // Unlink — secondary, dim, destructive (confirmed).
        TextButton(onClick = onUnlink) {
            Text(
                text = "Unlink this device",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateBanner(update: AvailableUpdate, onUpdate: () -> Unit) {
    Surface(
        onClick = onUpdate,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (update.mandatory) "Update required" else "Update available",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (update.mandatory) {
                    "Version ${update.targetVersion} is required to keep contributing. Tap to update."
                } else {
                    "Version ${update.targetVersion} is available. Tap to update."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun UnlinkConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlink this device?") },
        text = {
            Text(
                "This wipes the signing key and API key from this phone. To " +
                    "contribute again you will scan a new QR code. Remember to " +
                    "revoke the node on your dashboard too.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Unlink") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The read-only state shown by the indicator, derived from [ConnectionState]. */
private data class StateIndicator(
    val label: String,
    val subtitle: String,
    val dotArgb: Long,
)

private const val DOT_GREEN = 0xFF2E7D32
private const val DOT_AMBER = 0xFFF9A825
private const val DOT_GREY = 0xFF9E9E9E
private const val DOT_RED = 0xFFC62828

/**
 * Whether contribution is on — connecting, connected, or retrying. The button
 * verb derives from this, the same [ConnectionState] the indicator reads, so
 * the action and the state stay in lock-step.
 */
private fun ConnectionState.isContributing(): Boolean = when (this) {
    ConnectionState.Connecting,
    is ConnectionState.Connected,
    is ConnectionState.Reconnecting -> true
    ConnectionState.Idle,
    is ConnectionState.Stopped -> false
}

private fun ConnectionState.toIndicator(): StateIndicator = when (this) {
    is ConnectionState.Connected ->
        StateIndicator("Contributing", "Your phone is taking jobs.", DOT_GREEN)
    ConnectionState.Connecting ->
        StateIndicator("Connecting…", "Reaching the MeshCheck network.", DOT_AMBER)
    is ConnectionState.Reconnecting ->
        StateIndicator(
            "Reconnecting…",
            "Lost the connection — retrying (attempt $attempt).",
            DOT_AMBER,
        )
    ConnectionState.Idle ->
        StateIndicator("Paused", "Your phone is not taking jobs.", DOT_GREY)
    is ConnectionState.Stopped -> when (reason) {
        StopReason.REQUESTED ->
            StateIndicator("Paused", "Your phone is not taking jobs.", DOT_GREY)
        StopReason.UNAUTHORIZED ->
            StateIndicator(
                "Not linked",
                "This device's key was rejected. Unlink and scan a new QR code.",
                DOT_RED,
            )
        StopReason.OUTDATED ->
            StateIndicator(
                "Update required",
                "This app version can no longer connect. Update to keep contributing.",
                DOT_RED,
            )
        StopReason.SHUTDOWN ->
            StateIndicator(
                "Stopped by MeshCheck",
                "This node was suspended or revoked. Check your dashboard.",
                DOT_RED,
            )
    }
}
