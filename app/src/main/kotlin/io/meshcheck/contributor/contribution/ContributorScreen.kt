package io.meshcheck.contributor.contribution

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import io.meshcheck.data.earnings.Earnings
import io.meshcheck.protocol.ConnectionState

/**
 * The enrolled-device screen. Shows the four things the spec allows — state,
 * jobs, earnings, and the Start/Stop control — plus the dim Unlink control.
 *
 * The screen keeps **state** and **action** as separate elements: the
 * indicator reports what is true now (from the connection), while the button
 * is labelled with what pressing it does (from the user's intent), so the two
 * can never contradict each other.
 */
@Composable
fun ContributorScreen(
    container: AppContainer,
    onUnlinked: () -> Unit,
) {
    val context = LocalContext.current
    val connectionState by container.agentClient.state.collectAsState()
    val stats by container.agentClient.stats.collectAsState()

    var wantsConnected by rememberSaveable {
        mutableStateOf(container.contributionPrefs.userWantsConnected)
    }
    var batteryPrompted by rememberSaveable { mutableStateOf(false) }
    var earnings by remember { mutableStateOf<Earnings?>(null) }
    var showUnlinkConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        earnings = runCatching { container.earningsRepository.lifetimeEarnings() }.getOrNull()
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
        wantsConnected = true
        if (!batteryPrompted && !BatteryOptimization.isExempt(context)) {
            BatteryOptimization.requestExemption(context)
            batteryPrompted = true
        }
    }

    fun stopContributing() {
        ContributionService.stop(context)
        wantsConnected = false
    }

    ContributorContent(
        indicator = connectionState.toIndicator(),
        jobsReceived = stats.received,
        jobsDone = stats.done,
        earnings = earnings,
        wantsConnected = wantsConnected,
        onToggle = { if (wantsConnected) stopContributing() else startContributing() },
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
    jobsReceived: Int,
    jobsDone: Int,
    earnings: Earnings?,
    wantsConnected: Boolean,
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

        StatRow("Jobs this session", "$jobsDone done · $jobsReceived received")
        Spacer(Modifier.height(12.dp))
        StatRow("Earnings", earnings?.let(::formatEarnings) ?: "—")

        Spacer(Modifier.height(32.dp))

        // Action — labelled with the verb for the *other* state.
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (wantsConnected) "Stop contributing" else "Start contributing")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (wantsConnected) {
                "Pauses new jobs. Your earnings and this device stay linked."
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
private enum class StateIndicator(
    val label: String,
    val subtitle: String,
    val dotArgb: Long,
) {
    CONTRIBUTING("Contributing", "Your phone is taking jobs.", 0xFF2E7D32),
    CONNECTING("Connecting…", "Reaching the MeshCheck network.", 0xFFF9A825),
    PAUSED("Paused", "Your phone is not taking jobs.", 0xFF9E9E9E),
}

private fun ConnectionState.toIndicator(): StateIndicator = when (this) {
    is ConnectionState.Connected -> StateIndicator.CONTRIBUTING
    ConnectionState.Connecting, is ConnectionState.Reconnecting -> StateIndicator.CONNECTING
    ConnectionState.Idle, is ConnectionState.Stopped -> StateIndicator.PAUSED
}

private fun formatEarnings(earnings: Earnings): String =
    if (earnings.currency == "USD") {
        "$" + "%.2f".format(earnings.amount)
    } else {
        "%.2f %s".format(earnings.amount, earnings.currency)
    }
