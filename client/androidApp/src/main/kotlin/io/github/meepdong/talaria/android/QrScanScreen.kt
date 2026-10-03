package io.github.meepdong.talaria.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.github.meepdong.talaria.protocol.PairingPayload
import io.github.meepdong.talaria.ui.TalariaTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** UI.md §1: point the camera at the QR code `talaria pair` prints. */
@Composable
fun QrScanScreen(owner: LifecycleOwner, onLink: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onCancel)

    TalariaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Scan the pairing code", style = MaterialTheme.typography.headlineSmall)
                Text("Point the camera at the QR code that  talaria pair  shows in your terminal.")
                if (granted) {
                    CameraPreview(owner, onLink, Modifier.fillMaxWidth().weight(1f))
                } else {
                    Text("Talaria needs the camera only to read this code.", Modifier.weight(1f))
                    Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") }
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun CameraPreview(owner: LifecycleOwner, onLink: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val text = image.use { it.readQr() }
                    if (text != null && text.startsWith(PairingPayload.LINK_PREFIX) && delivered.compareAndSet(false, true)) {
                        view.post { onLink(text) }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ runCatching { future.get().unbindAll() } }, ContextCompat.getMainExecutor(context))
            executor.shutdown()
        }
    }
}

/** The Y plane of a YUV_420_888 frame is the brightness image ZXing needs. */
private fun ImageProxy.readQr(): String? {
    val plane = planes[0]
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val data = ByteArray(plane.rowStride * height)
    buffer.get(data, 0, minOf(buffer.remaining(), data.size))
    return QrDecoder.decode(data, plane.rowStride, width, height)
}
