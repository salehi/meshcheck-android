package io.meshcheck.contributor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.meshcheck.contributor.contribution.ContributorScreen
import io.meshcheck.contributor.diagnostics.LogScreen
import io.meshcheck.contributor.enrollment.EnrollmentScreen

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
                )
            }

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
