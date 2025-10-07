package com.example.realtimeedgedetection

import android.os.Bundle
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.tooling.preview.Preview
import com.example.realtimeedgedetection.ui.theme.RealTimeEdgeDetectionTheme
import android.view.TextureView
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.compose.material3.Button
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import android.media.ImageReader
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
        private const val TAG = "EdgeDetection"
    }

    // Simple JNI test method (implemented in native-lib.cpp)
    external fun nativeAdd(a: Int, b: Int): Int

    private var cameraHandler: CameraHandler? = null
    private var textureViewRef: TextureView? = null
    private var glRenderer: GLCameraRenderer? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RealTimeEdgeDetectionTheme {
                val context = LocalContext.current
                val hasPermission = remember { mutableStateOf(isCameraGranted()) }

                LaunchedEffect(Unit) {
                    // Quick JNI verification
                    val sum = nativeAdd(2, 3)
                    Log.d(TAG, "nativeAdd(2,3) = $sum")
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)) {
                        // Keep a TextureView reference for CameraHandler but do not use it for preview
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).also { tv ->
                                    textureViewRef = tv
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // GLSurfaceView for live GPU edge rendering
                        AndroidView(
                            factory = { ctx ->
                                GLSurfaceView(ctx).apply {
                                    setEGLContextClientVersion(2)
                                    val renderer = GLCameraRenderer(onSurfaceReady = { surface: Surface, w: Int, h: Int ->
                                        // Start camera with external GL surface
                                        runOnUiThread {
                                            val tv = textureViewRef ?: return@runOnUiThread
                                            if (cameraHandler == null) {
                                                cameraHandler = CameraHandler(this@MainActivity, tv) { /* no ImageReader in GPU path */ }
                                            }
                                            if (isCameraGranted()) {
                                                cameraHandler?.startWithExternalSurface(surface, w, h, enableReader = false)
                                            } else {
                                                Log.d(TAG, "Camera permission not granted yet when GL surface ready")
                                            }
                                        }
                                    }, onPngReady = { pngBytes ->
                                        try {
                                            val out = File(getExternalFilesDir(null), "edge_live_${System.currentTimeMillis()}.png")
                                            out.writeBytes(pngBytes)
                                            Log.d(TAG, "Saved GL capture: ${out.absolutePath}")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to save GL capture", e)
                                        }
                                    })
                                    glRenderer = renderer
                                    setRenderer(renderer)
                                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        // Capture button bottom-center overlay
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Button(
                                onClick = { glRenderer?.requestCapture() },
                                modifier = Modifier.padding(bottom = 24.dp)
                            ) { Text("Capture") }
                        }
                        if (!hasPermission.value) {
                            // If no permission, request immediately
                            LaunchedEffect("perm") {
                                requestCameraPermission()
                                hasPermission.value = isCameraGranted()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // GLSurfaceView handles its own rendering lifecycle; camera starts when GL surface is ready
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    private fun isCameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (!isCameraGranted()) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private var savedSampleOnce = false

    private fun startCamera() {
        val tv = textureViewRef ?: run {
            Log.d(TAG, "startCamera called but TextureView is null")
            return
        }
        if (cameraHandler == null) {
            cameraHandler = CameraHandler(this, tv) { /* not used in GPU path */ }
        }
        // Camera will be started by GLSurfaceView renderer via startWithExternalSurface()
    }

    private fun stopCamera() {
        Log.d(TAG, "Stopping CameraHandler")
        cameraHandler?.stop()
    }

    private fun handleImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val width = image.width
            val height = image.height
            val nv21 = YuvUtils.toNV21(image)
            val pngBytes = NativeBridge.processFrameNV21ToPNG(nv21, width, height)
            val outFile = File(getExternalFilesDir(null), "edge_sample.png")
            outFile.writeBytes(pngBytes)
            Log.d(TAG, "Saved processed PNG: ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed processing frame", e)
        } finally {
            try { image.close() } catch (_: Exception) {}
            // Avoid repeated attempts; we only need one sample
            savedSampleOnce = true
            // After first sample, drop ImageReader from the session to stop device errors
            try { cameraHandler?.disableImageReader() } catch (_: Exception) {}
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RealTimeEdgeDetectionTheme {
        Greeting("Android")
    }
}