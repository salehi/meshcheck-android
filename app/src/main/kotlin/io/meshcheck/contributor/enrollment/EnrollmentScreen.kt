package io.meshcheck.contributor.enrollment

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.meshcheck.data.enrollment.Enroller
import io.meshcheck.data.enrollment.EnrollmentError
import io.meshcheck.data.enrollment.EnrollmentResult
import kotlinx.coroutines.launch

/** What the enrollment screen is currently showing. */
private sealed interface EnrollmentUiState {
    data object Explainer : EnrollmentUiState
    data object Scanning : EnrollmentUiState
    data object Enrolling : EnrollmentUiState
    data class Error(val message: String) : EnrollmentUiState
}

/**
 * First-launch flow: explain what contributing means, scan the dashboard QR
 * for an enrollment token, then redeem it through [Enroller]. On success
 * [onEnrolled] is called and the host swaps to the contributor screen.
 */
@Composable
fun EnrollmentScreen(
    enroller: Enroller,
    onEnrolled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<EnrollmentUiState>(EnrollmentUiState.Explainer) }

    fun redeem(token: String) {
        uiState = EnrollmentUiState.Enrolling
        scope.launch {
            val result = try {
                enroller.enroll(token)
            } catch (e: Exception) {
                EnrollmentResult.Failure(
                    EnrollmentError.SERVER,
                    e.message ?: "Enrollment could not be completed.",
                )
            }
            when (result) {
                is EnrollmentResult.Success -> onEnrolled()
                is EnrollmentResult.Failure -> uiState = EnrollmentUiState.Error(result.message)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        uiState = if (granted) {
            EnrollmentUiState.Scanning
        } else {
            EnrollmentUiState.Error(
                "MeshCheck needs camera access to scan your enrollment QR code. " +
                    "You can grant it and try again.",
            )
        }
    }

    fun startScan() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            uiState = EnrollmentUiState.Scanning
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    when (val state = uiState) {
        EnrollmentUiState.Explainer -> ExplainerContent(onScan = ::startScan)
        EnrollmentUiState.Scanning -> ScanningContent(
            onToken = ::redeem,
            onCancel = { uiState = EnrollmentUiState.Explainer },
        )
        EnrollmentUiState.Enrolling -> EnrollingContent()
        is EnrollmentUiState.Error -> ErrorContent(
            message = state.message,
            onRetry = { uiState = EnrollmentUiState.Explainer },
        )
    }
}

@Composable
private fun ExplainerContent(onScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Contribute to MeshCheck",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Your phone becomes a MeshCheck node. In the background it " +
                "runs small reachability checks against websites and servers, " +
                "and you earn a share of the revenue for the work it does.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "It keeps a connection open while contributing, which uses " +
                "some battery and mobile data. You can stop at any time.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onScan) {
            Text("Scan QR code to begin")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Open your MeshCheck dashboard and choose “Add an Android device”.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScanningContent(
    onToken: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Guard against the analyzer firing twice before the screen recomposes.
    var consumed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        QrScanner(
            onQrScanned = { token ->
                if (!consumed) {
                    consumed = true
                    onToken(token)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Point the camera at the QR code on your MeshCheck dashboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun EnrollingContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Linking this device…",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Enrollment didn’t complete",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Try again")
        }
    }
}
