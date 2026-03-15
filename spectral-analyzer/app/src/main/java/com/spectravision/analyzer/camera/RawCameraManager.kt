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
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private var previewImageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    /**
     * Exposure settings for manual capture control.
     */
    data class ExposureSettings(
        val iso: Int = 100,
        val exposureTimeNs: Long = 33_333_333L, // 1/30s
        val autoExposure: Boolean = false
    )

    /**
     * Information about a camera's capabilities.
     */
    data class CameraInfo(
        val id: String,
        val isoRange: Range<Int>,
        val exposureRange: Range<Long>,
        val rawSizes: Array<Size>
    )

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

    /** Get detailed info about a RAW-capable camera. */
    fun getCameraInfo(cameraId: String): CameraInfo {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(100, 800)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(1_000_000L, 1_000_000_000L)
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()

        return CameraInfo(cameraId, isoRange, exposureRange, rawSizes)
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
     * Start a preview capture session that delivers low-res YUV frames for the
     * live viewfinder. The callback receives each preview frame as a simple
     * intensity line profile (averaged rows) for real-time spectral strip display.
     */
    suspend fun startPreview(
        cameraId: String,
        previewSurface: Surface,
        exposure: ExposureSettings = ExposureSettings()
    ): Unit = suspendCancellableCoroutine { cont ->
        val camera = cameraDevice
            ?: throw IllegalStateException("Camera not opened")

        val surfaces = listOf(previewSurface)

        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session

                    val request = camera.createCaptureRequest(
                        CameraDevice.TEMPLATE_PREVIEW
                    ).apply {
                        addTarget(previewSurface)
                        if (!exposure.autoExposure) {
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                            set(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exposureTimeNs)
                        }
                    }.build()

                    session.setRepeatingRequest(request, null, backgroundHandler)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            RuntimeException("Preview session configuration failed")
                        )
                    }
                }
            },
            backgroundHandler
        )
    }

    /**
     * Capture a single RAW frame. Returns the raw 16-bit Bayer data along
     * with metadata needed for spectral processing.
     */
    suspend fun captureRawFrame(
        cameraId: String,
        exposure: ExposureSettings = ExposureSettings()
    ): RawFrame = suspendCancellableCoroutine { cont ->
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
                        if (exposure.autoExposure) {
                            set(CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON)
                        } else {
                            set(CaptureRequest.CONTROL_MODE,
                                CaptureRequest.CONTROL_MODE_OFF)
                            set(CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.CONTROL_AWB_MODE,
                                CaptureRequest.CONTROL_AWB_MODE_OFF)
                            set(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exposureTimeNs)
                        }
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
        // BlackLevelPattern stores 4 values in a 2x2 pattern (row, col):
        // (0,0) (0,1)
        // (1,0) (1,1)
        val blackLevel = if (blackLevelPattern != null) {
            intArrayOf(
                blackLevelPattern.getOffsetForIndex(0, 0),
                blackLevelPattern.getOffsetForIndex(0, 1),
                blackLevelPattern.getOffsetForIndex(1, 0),
                blackLevelPattern.getOffsetForIndex(1, 1)
            )
        } else {
            intArrayOf(0, 0, 0, 0)
        }

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
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewImageReader?.close()
        previewImageReader = null
        stopBackgroundThread()
    }
}
