package com.spectravision.analyzer.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ShortBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages Camera2 API access for capturing RAW (DNG/RAW_SENSOR) frames.
 *
 * RAW capture is essential because JPEG/YUV processing applies gamma correction,
 * white balance, and tone mapping that destroy the linear relationship between
 * photon count and pixel value needed for spectral analysis.
 */
class RawCameraManager(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    data class RawFrame(
        val width: Int,
        val height: Int,
        val data: ShortArray,       // 16-bit Bayer mosaic pixel values
        val bayerPattern: Int,      // CFA pattern (RGGB, BGGR, GRBG, GBRG)
        val blackLevel: IntArray,   // per-channel black level
        val whiteLevel: Int         // sensor saturation level
    )

    /** Find camera that supports RAW_SENSOR output. */
    fun findRawCameraId(): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val capabilities = chars.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: continue

            val supportsRaw = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            )
            if (supportsRaw) return id
        }
        return null
    }

    /** Get available RAW output sizes for the given camera. */
    fun getRawOutputSizes(cameraId: String): Array<Size> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyArray()
        return map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
    }

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    /**
     * Open the camera and prepare for RAW capture.
     */
    suspend fun openCamera(cameraId: String): Unit = suspendCancellableCoroutine { cont ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cont.resumeWithException(SecurityException("Camera permission not granted"))
            return@suspendCancellableCoroutine
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                if (cont.isActive) {
                    cont.resumeWithException(
                        RuntimeException("Camera open error: $error")
                    )
                }
            }
        }, backgroundHandler)
    }

    /**
     * Capture a single RAW frame. Returns the raw 16-bit Bayer data along
     * with metadata needed for spectral processing.
     */
    suspend fun captureRawFrame(cameraId: String): RawFrame = suspendCancellableCoroutine { cont ->
        val camera = cameraDevice
            ?: throw IllegalStateException("Camera not opened")

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)

        // Get RAW size (use largest available)
        val sizes = getRawOutputSizes(cameraId)
        val size = sizes.maxByOrNull { it.width * it.height }
            ?: throw IllegalStateException("No RAW output sizes available")

        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.RAW_SENSOR, 2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val frame = extractRawFrame(image, chars)
                if (cont.isActive) cont.resume(frame)
            } finally {
                image.close()
            }
        }, backgroundHandler)

        // Create capture session
        val surfaces = listOf(imageReader!!.surface)
        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session

                    // Build a RAW capture request with manual exposure
                    val request = camera.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE
                    ).apply {
                        addTarget(imageReader!!.surface)
                        // Use manual mode for consistent, repeatable captures
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                        // Sensible defaults — user can adjust via UI
                        set(CaptureRequest.SENSOR_SENSITIVITY, 100)        // ISO 100
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, 33_333_333L) // 1/30s
                    }.build()

                    session.capture(request, null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            RuntimeException("Capture session configuration failed")
                        )
                    }
                }
            },
            backgroundHandler
        )
    }

    /**
     * Extract raw pixel data and sensor metadata from a Camera2 RAW_SENSOR image.
     */
    private fun extractRawFrame(
        image: Image,
        characteristics: CameraCharacteristics
    ): RawFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer.asShortBuffer()
        val data = ShortArray(buffer.remaining())
        buffer.get(data)

        val bayerPattern = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        ) ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

        val blackLevelPattern = characteristics.get(
            CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN
        )
        val blackLevel = IntArray(4) { blackLevelPattern?.getOffsetForIndex(0, it) ?: 0 }

        val whiteLevel = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL
        ) ?: 1023

        return RawFrame(
            width = image.width,
            height = image.height,
            data = data,
            bayerPattern = bayerPattern,
            blackLevel = blackLevel,
            whiteLevel = whiteLevel
        )
    }

    fun release() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        stopBackgroundThread()
    }
}
