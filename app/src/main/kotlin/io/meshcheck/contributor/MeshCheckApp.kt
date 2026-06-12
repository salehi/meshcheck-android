package io.meshcheck.contributor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.meshcheck.contributor.contribution.ContributorScreen
import io.meshcheck.core.diagnostics.AppLog
import io.meshcheck.contributor.diagnostics.LogScreen
import io.meshcheck.contributor.enrollment.EnrollmentScreen
import io.meshcheck.contributor.settings.SettingsButton
import io.meshcheck.contributor.settings.SettingsSheet

/**
 * Top-level routing: an unenrolled device sees the enrollment flow; an
 * enrolled one sees the contributor screen. Enrolling flips the flag forward;
 * unlinking flips it back.
 *
 * Debug builds also overlay a top-right "Logs" button that opens the in-app
 * log viewer (see [LogScreen]); release builds never show it.
 */
@Composable
fun MeshCheckApp(container: AppContainer) {
    var enrolled by rememberSaveable {
        mutableStateOf(container.credentialStore.isEnrolled())
    }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // A pairing payload from the meshcheck://enroll deep link. On an unenrolled
    // device it flows into EnrollmentScreen to auto-redeem; on an already-linked
    // device we ignore it (re-binding would silently overwrite the credential).
    val pendingEnrollment by container.pendingEnrollment.collectAsState()
    LaunchedEffect(pendingEnrollment, enrolled) {
        if (pendingEnrollment != null && enrolled) {
            AppLog.i("DeepLink", "Ignoring enrollment payload; device already linked")
            container.clearPendingEnrollment()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showLogs) {
            LogScreen(onClose = { showLogs = false })
        } else {
            if (enrolled) {
                ContributorScreen(
                    container = container,
                    onUnlinked = { enrolled = false },
                )
            } else {
                EnrollmentScreen(
                    enroller = container.enroller,
                    onEnrolled = { enrolled = true },
                    incomingPayload = pendingEnrollment,
                    onPayloadConsumed = { container.clearPendingEnrollment() },
                )
            }

            // Settings gear — bottom-center, a little above the nav bar. Shown
            // on both the enrollment and contributor screens, like the Logs chip.
            SettingsButton(
                onClick = { showSettings = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )

            if (BuildConfig.DEBUG) {
                LogsButton(
                    onClick = { showLogs = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp),
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            themePrefs = container.themePrefs,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun LogsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A small tonal chip so it stays legible over the fullscreen camera preview.
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Text(
            text = "Logs",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
