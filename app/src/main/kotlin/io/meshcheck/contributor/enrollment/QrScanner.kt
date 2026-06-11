package io.meshcheck.contributor.enrollment

import android.annotation.SuppressLint
import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A live camera QR scanner. Shows a CameraX back-camera preview and runs every
 * frame through ML Kit's bundled barcode scanner; the first QR decoded is
 * delivered to [onQrScanned] on the main thread.
 *
 * The camera is bound to the host Activity's lifecycle and explicitly unbound
 * when this composable leaves composition, so it does not keep running once
 * the enrollment flow moves on.
 */
@Composable
fun QrScanner(
    onQrScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    val cameraProvider = remember { AtomicReference<ProcessCameraProvider?>() }
    val analyzer = remember { AtomicReference<QrCodeAnalyzer?>() }
    val disposed = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            cameraProvider.get()?.unbindAll()
            analyzer.get()?.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val mainExecutor = ContextCompat.getMainExecutor(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)

            providerFuture.addListener({
                if (disposed.get()) return@addListener
                val provider = providerFuture.get()
                cameraProvider.set(provider)

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // 1280×720 gives ML Kit enough pixels to resolve the dense
                // enrollment QR (it carries a full JWT) without framing it
                // perfectly. The default analysis resolution (~640×480) is too
                // coarse for a code this dense.
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolution)
                    .build()
                analysis.setAnalyzer(
                    mainExecutor,
                    QrCodeAnalyzer { text -> onQrScanned(text) }.also(analyzer::set),
                )

                runCatching {
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    enableTapToFocus(previewView, camera)
                }
            }, mainExecutor)

            previewView
        },
    )
}

/** Lets the user tap the preview to refocus on a held-up screen QR. */
@SuppressLint("ClickableViewAccessibility")
private fun enableTapToFocus(previewView: PreviewView, camera: Camera) {
    previewView.setOnTouchListener { view, event ->
        if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener true
        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
        camera.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point).build(),
        )
        view.performClick()
        true
    }
}

/**
 * An [ImageAnalysis.Analyzer] that decodes a QR code from a frame with ML Kit.
 * Fires [onQrCode] at most once — later frames are skipped after the first hit.
 *
 * ML Kit decodes asynchronously, so the frame's [ImageProxy] is held open until
 * the decode task completes; with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] that
 * backpressure simply drops intervening frames until this one is closed.
 */
@ExperimentalGetImage
private class QrCodeAnalyzer(
    private val onQrCode: (String) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )
    private val found = AtomicBoolean(false)

    /** Releases the ML Kit scanner; called when the scanner screen closes. */
    override fun close() = scanner.close()

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (found.get() || media == null) {
            image.close()
            return
        }
        // ML Kit handles rotation natively, so no manual luminance/rotation work.
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val text = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                if (text != null && found.compareAndSet(false, true)) {
                    onQrCode(text)
                }
            }
            .addOnCompleteListener { image.close() }
    }
}
