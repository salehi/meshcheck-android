package io.meshcheck.contributor.enrollment

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.meshcheck.data.enrollment.Enroller
import io.meshcheck.data.enrollment.EnrollmentError
import io.meshcheck.data.enrollment.EnrollmentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * Besides the camera, two other transports feed the same redeem path: an
 * [incomingPayload] arriving from the `meshcheck://enroll` deep link (consumed
 * once via [onPayloadConsumed]), and the "Paste a pairing code" fallback for
 * when the deep link doesn't fire. All three carry the same envelope.
 */
@Composable
fun EnrollmentScreen(
    enroller: Enroller,
    onEnrolled: () -> Unit,
    incomingPayload: String? = null,
    onPayloadConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<EnrollmentUiState>(EnrollmentUiState.Explainer) }
    var showPaste by remember { mutableStateOf(false) }

    fun redeem(token: String) {
        uiState = EnrollmentUiState.Enrolling
        scope.launch {
            // Enrollment generates the keypair and writes the Keystore-wrapped
            // credential; keep it off the main thread so the UI never stalls.
            // The state updates below stay on the main dispatcher.
            val result = withContext(Dispatchers.Default) {
                try {
                    enroller.enroll(token)
                } catch (e: Exception) {
                    EnrollmentResult.Failure(
                        EnrollmentError.SERVER,
                        e.message ?: "Enrollment could not be completed.",
                    )
                }
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

    // A deep-link payload bypasses the camera and redeems straight away. Consume
    // it first so clearing the source flow (which re-keys this effect to null)
    // can't replay it; redeem runs on `scope`, not this effect, so it survives.
    LaunchedEffect(incomingPayload) {
        incomingPayload?.let {
            onPayloadConsumed()
            redeem(it)
        }
    }

    when (val state = uiState) {
        EnrollmentUiState.Explainer -> ExplainerContent(
            onScan = ::startScan,
            onPaste = { showPaste = true },
        )
        EnrollmentUiState.Scanning -> ScanningContent(
            onToken = ::redeem,
            onCancel = { uiState = EnrollmentUiState.Explainer },
            onPrewarm = { scope.launch(Dispatchers.Default) { enroller.prewarm() } },
        )
        EnrollmentUiState.Enrolling -> EnrollingContent()
        is EnrollmentUiState.Error -> ErrorContent(
            message = state.message,
            onRetry = { uiState = EnrollmentUiState.Explainer },
        )
    }

    if (showPaste) {
        PasteCodeDialog(
            onDismiss = { showPaste = false },
            onSubmit = { code ->
                showPaste = false
                redeem(code)
            },
        )
    }
}

@Composable
private fun ExplainerContent(onScan: () -> Unit, onPaste: () -> Unit) {
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
        Spacer(Modifier.height(16.dp))
        // Fallback for when the dashboard's "pair this phone" deep link didn't
        // open the app — the user can copy the pairing code and paste it here.
        TextButton(onClick = onPaste) {
            Text("Paste a pairing code instead")
        }
    }
}

/**
 * The "Copy pairing code" fallback: the user pastes the envelope the dashboard
 * copied. The field accepts the bare code, the raw JSON, or the full
 * `meshcheck://enroll#…` link — [EnrollmentQr.parse] normalizes all three.
 */
@Composable
private fun PasteCodeDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a pairing code") },
        text = {
            Column {
                Text(
                    text = "On your MeshCheck dashboard, choose “Add an Android " +
                        "device” and tap “Copy pairing code”, then paste it here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Pairing code") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(code.trim()) },
                enabled = code.isNotBlank(),
            ) {
                Text("Link device")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ScanningContent(
    onToken: (String) -> Unit,
    onCancel: () -> Unit,
    onPrewarm: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Generate the signing keypair and Keystore key while the camera is live,
    // so the post-scan enroll is just parse + persist (no perceptible stall).
    LaunchedEffect(Unit) { onPrewarm() }
    // Guard against the analyzer firing twice before the screen recomposes.
    var consumed by remember { mutableStateOf(false) }
    // Window-space bounds of the viewfinder square and of the scrim canvas,
    // so the scrim can cut its hole exactly where the square is laid out.
    var frameBounds by remember { mutableStateOf<Rect?>(null) }
    var scrimOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        QrScanner(
            onQrScanned = { token ->
                if (!consumed) {
                    consumed = true
                    // A tick the instant a code is detected, before enrolling.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToken(token)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { scrimOrigin = it.positionInRoot() },
        ) {
            val scrim = Color.Black.copy(alpha = 0.6f)
            val hole = frameBounds?.translate(-scrimOrigin)
            if (hole == null) {
                drawRect(scrim)
            } else {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(hole, CornerRadius(16.dp.toPx())))
                }
                drawPath(path, scrim)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QrViewfinderFrame(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(1f)
                    .onGloballyPositioned { frameBounds = it.boundsInRoot() },
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Point the camera at the QR code on your MeshCheck " +
                    "dashboard — a square of small black-and-white dots, " +
                    "like the pattern shown in the box.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

/**
 * The scan viewfinder: corner brackets marking the square, and a faint
 * pulsing QR-code silhouette inside it — three corner "eyes" plus scattered
 * dots — so someone who has never seen a QR code knows what kind of image
 * the camera is looking for.
 */
@Composable
private fun QrViewfinderFrame(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "qr-hint").animateFloat(
        initialValue = 0.18f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000), RepeatMode.Reverse),
        label = "qr-hint-alpha",
    )

    Canvas(modifier = modifier) {
        val white = Color.White

        // Corner brackets.
        val stroke = 4.dp.toPx()
        val arm = size.minDimension * 0.12f
        val c = stroke / 2f
        val w = size.width
        val h = size.height
        fun bracket(corner: Offset, dx: Float, dy: Float) {
            drawLine(white, corner, Offset(corner.x + dx * arm, corner.y), stroke, StrokeCap.Round)
            drawLine(white, corner, Offset(corner.x, corner.y + dy * arm), stroke, StrokeCap.Round)
        }
        bracket(Offset(c, c), 1f, 1f)
        bracket(Offset(w - c, c), -1f, 1f)
        bracket(Offset(c, h - c), 1f, -1f)
        bracket(Offset(w - c, h - c), -1f, -1f)

        // Ghost QR pattern on a 21×21 grid (the smallest real QR version).
        val grid = 21
        val inset = size.minDimension * 0.18f
        val cell = (size.minDimension - 2f * inset) / grid
        fun module(x: Int, y: Int, alpha: Float) {
            drawRect(
                color = white.copy(alpha = alpha),
                topLeft = Offset(inset + x * cell, inset + y * cell),
                size = Size(cell * 0.8f, cell * 0.8f),
            )
        }

        // The three finder "eyes": a 7×7 ring with a 3×3 core.
        val finderAlpha = (pulse + 0.15f).coerceAtMost(1f)
        fun finder(ox: Int, oy: Int) {
            for (x in 0..6) {
                for (y in 0..6) {
                    val ring = x == 0 || x == 6 || y == 0 || y == 6
                    val core = x in 2..4 && y in 2..4
                    if (ring || core) module(ox + x, oy + y, finderAlpha)
                }
            }
        }
        finder(0, 0)
        finder(grid - 7, 0)
        finder(0, grid - 7)

        // Scattered data dots, skipping the finder zones (plus separator).
        for (x in 0 until grid) {
            for (y in 0 until grid) {
                val inFinder = (x < 8 && y < 8) || (x >= grid - 8 && y < 8) || (x < 8 && y >= grid - 8)
                if (inFinder) continue
                // Deterministic pseudo-random scatter, ~40% fill like a real code.
                if ((x * 7 + y * 11 + x * y) % 5 < 2) module(x, y, pulse)
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
