package io.meshcheck.contributor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.meshcheck.contributor.contribution.ContributorScreen
import io.meshcheck.contributor.enrollment.EnrollmentScreen

/**
 * Top-level routing: an unenrolled device sees the enrollment flow; an
 * enrolled one sees the contributor screen. Enrolling flips the flag forward;
 * unlinking flips it back.
 */
@Composable
fun MeshCheckApp(container: AppContainer) {
    var enrolled by rememberSaveable {
        mutableStateOf(container.credentialStore.isEnrolled())
    }

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
}
