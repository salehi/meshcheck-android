package io.meshcheck.contributor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.meshcheck.contributor.enrollment.EnrollmentScreen

/**
 * Top-level routing: an unenrolled device sees the [EnrollmentScreen]; an
 * enrolled one sees the contributor screen (a placeholder until that step).
 */
@Composable
fun MeshCheckApp(container: AppContainer) {
    // Seeded from persisted state; flips to true when enrollment completes.
    var enrolled by rememberSaveable {
        mutableStateOf(container.credentialStore.isEnrolled())
    }

    if (enrolled) {
        EnrolledPlaceholderScreen()
    } else {
        EnrollmentScreen(
            enroller = container.enroller,
            onEnrolled = { enrolled = true },
        )
    }
}

@Composable
private fun EnrolledPlaceholderScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "This device is enrolled",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "The contributor screen — jobs, earnings, and the " +
                "Start/Stop control — arrives in a later step.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
