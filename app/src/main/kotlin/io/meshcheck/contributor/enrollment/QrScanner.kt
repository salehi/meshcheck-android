package io.meshcheck.contributor.enrollment

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A live camera QR scanner. Shows a CameraX back-camera preview and runs every
 * frame through ZXing; the first QR decoded is delivered to [onQrScanned] on
 * the main thread.
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

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProvider = remember { AtomicReference<ProcessCameraProvider?>() }
    val disposed = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            cameraProvider.get()?.unbindAll()
            analysisExecutor.shutdown()
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

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(
                    analysisExecutor,
                    QrCodeAnalyzer { text -> mainExecutor.execute { onQrScanned(text) } },
                )

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, mainExecutor)

            previewView
        },
    )
}

/**
 * An [ImageAnalysis.Analyzer] that decodes a QR code from a frame's luminance
 * plane. Fires [onQrCode] at most once — later frames are skipped after the
 * first hit.
 */
private class QrCodeAnalyzer(
    private val onQrCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var found = false

    override fun analyze(image: ImageProxy) {
        if (found) {
            image.close()
            return
        }
        try {
            // Plane 0 of YUV_420_888 is the luminance (Y) plane — all ZXing needs.
            val plane = image.planes[0]
            val data = ByteArray(plane.buffer.remaining())
            plane.buffer.get(data)

            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride, // dataWidth — may exceed image.width by row padding
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            found = true
            onQrCode(result.text)
        } catch (_: NotFoundException) {
            // No QR in this frame — keep scanning.
        } catch (_: Exception) {
            // Transient decode hiccup — keep scanning.
        } finally {
            reader.reset()
            image.close()
        }
    }
}
