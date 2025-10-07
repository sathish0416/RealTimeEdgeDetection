package com.example.realtimeedgedetection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs

class CameraHandler(
    private val context: Context,
    private val textureView: TextureView,
    private val onImageAvailable: ((ImageReader) -> Unit)? = null
) {
    private val tag = "CameraHandler"

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var externalPreviewSurface: Surface? = null
    private var enableImageReader: Boolean = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun start() {
        startBackgroundThread()
        if (externalPreviewSurface != null) {
            // If GLSurface is provided, use its size (fallback to textureView size if unknown)
            val w = if (textureView.width > 0) textureView.width else 1280
            val h = if (textureView.height > 0) textureView.height else 720
            openCamera(w, h)
        } else {
            if (textureView.isAvailable) {
                openCamera(textureView.width, textureView.height)
            } else {
                textureView.surfaceTextureListener = surfaceTextureListener
            }
        }
    }

    // Start using an external GL preview surface (from GLCameraRenderer)
    fun startWithExternalSurface(surface: Surface, width: Int, height: Int, enableReader: Boolean = false) {
        this.externalPreviewSurface = surface
        this.enableImageReader = enableReader
        startBackgroundThread()
        openCamera(width, height)
    }

    fun stop() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(tag, "SurfaceTexture available: ${width}x${height}")
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { return true }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        try {
            val cameraId = chooseBackCameraId() ?: return
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return

            val previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), width, height)
            val yuvChoices = map.getOutputSizes(ImageFormat.YUV_420_888)
            val frameSize = chooseYuvSize(yuvChoices, previewSize)

            Log.d(tag, "Chosen previewSize=${previewSize.width}x${previewSize.height} frameSize=${frameSize.width}x${frameSize.height}")

            // Configure TextureView buffer size if used
            if (externalPreviewSurface == null) {
                textureView.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            }

            // ImageReader for YUV frames (optional)
            if (onImageAvailable != null && enableImageReader) {
                imageReader = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.YUV_420_888, 1)
                imageReader?.setOnImageAvailableListener({ reader ->
                    onImageAvailable.invoke(reader)
                }, backgroundHandler)
            } else {
                imageReader = null
            }

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(tag, "openCamera error", e)
        }
    }

    private fun chooseBackCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        // fallback to first camera
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        // Choose the size closest to requested width/height
        var best = choices.first()
        var minDiff = Int.MAX_VALUE
        for (opt in choices) {
            val diff = abs(opt.width - width) + abs(opt.height - height)
            if (diff < minDiff) { best = opt; minDiff = diff }
        }
        return best
    }

    private fun chooseYuvSize(choices: Array<Size>, prefer: Size): Size {
        // Cap to <= 1280x720 to avoid device load/backpressure
        val filtered = choices.filter { it.width <= 1280 && it.height <= 720 }
        // If preview size is within cap and available, pick it; otherwise choose closest within cap
        val exactCapped = filtered.firstOrNull { it.width == prefer.width && it.height == prefer.height }
        if (exactCapped != null) return exactCapped
        if (filtered.isNotEmpty()) {
            return filtered.minBy { abs(it.width - prefer.width) + abs(it.height - prefer.height) }
        }
        // Fallback to the closest by area
        return choices.minBy { abs(it.width * it.height - prefer.width * prefer.height) }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
        override fun onError(camera: CameraDevice, error: Int) { Log.e(tag, "Camera error: $error"); camera.close(); cameraDevice = null }
    }

    private fun createPreviewSession() {
        try {
            val surface = externalPreviewSurface ?: run {
                val texture = textureView.surfaceTexture ?: return
                Surface(texture)
            }

            val targets = mutableListOf(surface)
            imageReader?.surface?.let { targets.add(it) }

            cameraDevice?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        requestBuilder.addTarget(surface)
                        imageReader?.surface?.let { requestBuilder.addTarget(it) }
                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        // Optional: try to limit FPS to a moderate range if supported
                        try {
                            val fpsRanges = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
                                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                            val target = fpsRanges?.filter { it.lower >= 15 && it.upper <= 30 }?.minByOrNull { it.upper } ?: fpsRanges?.firstOrNull()
                            if (target != null) {
                                requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, target)
                            }
                        } catch (_: Exception) {}
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                        Log.d(tag, "Preview session started")
                    } catch (e: CameraAccessException) {
                        Log.e(tag, "Failed to start preview", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(tag, "Configure failed for capture session")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(tag, "createPreviewSession error", e)
        }
    }

    // Disable ImageReader and rebuild a preview-only session to avoid device errors
    fun disableImageReader() {
        try {
            Log.d(tag, "Disabling ImageReader and recreating preview session")
            imageReader?.close()
            imageReader = null
            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { captureSession?.abortCaptures() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null
        } catch (_: Exception) {}
        createPreviewSession()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(tag, "stopBackgroundThread", e)
        }
    }
}
