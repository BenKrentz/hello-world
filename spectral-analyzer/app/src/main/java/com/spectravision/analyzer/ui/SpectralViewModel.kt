package com.spectravision.analyzer.ui

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Range
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectravision.analyzer.SpectraVisionApp
import com.spectravision.analyzer.analysis.ReferenceSpectra
import com.spectravision.analyzer.analysis.SensorResponseCorrection
import com.spectravision.analyzer.analysis.SpectralEngine
import com.spectravision.analyzer.camera.RawCameraManager
import com.spectravision.analyzer.camera.RawCameraManager.ExposureSettings
import com.spectravision.analyzer.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SpectralViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraManager = RawCameraManager(application)
    private val spectralEngine = SpectralEngine()
    private val sensorCorrection = SensorResponseCorrection()
    private val db = (application as SpectraVisionApp).database

    // UI state
    private val _uiState = MutableStateFlow(SpectralUiState())
    val uiState: StateFlow<SpectralUiState> = _uiState.asStateFlow()

    data class SpectralUiState(
        val currentScreen: Screen = Screen.CAPTURE,
        val spectrum: List<SpectralPoint> = emptyList(),
        val intensityProfile: DoubleArray = DoubleArray(0),
        val detectedPeaks: List<Int> = emptyList(),
        val calibrationModel: SpectralEngine.CalibrationModel? = null,
        val isCapturing: Boolean = false,
        val statusMessage: String = "Ready — attach diffraction grating and point at light source",
        val exposure: ExposureSettings = ExposureSettings(),
        val cameraInfo: RawCameraManager.CameraInfo? = null,
        val savedSpectra: List<SpectrumRecord> = emptyList(),
        val referenceLines: List<ReferenceSpectra.EmissionLine> = emptyList(),
        val applySensorCorrection: Boolean = true
    )

    init {
        loadSavedCalibration()
        _uiState.value = _uiState.value.copy(
            referenceLines = ReferenceSpectra.commonEmissionLines
        )
    }

    fun setScreen(screen: Screen) {
        _uiState.value = _uiState.value.copy(currentScreen = screen)
    }

    fun updateExposure(iso: Int? = null, exposureTimeNs: Long? = null, auto: Boolean? = null) {
        val current = _uiState.value.exposure
        _uiState.value = _uiState.value.copy(
            exposure = current.copy(
                iso = iso ?: current.iso,
                exposureTimeNs = exposureTimeNs ?: current.exposureTimeNs,
                autoExposure = auto ?: current.autoExposure
            )
        )
    }

    fun toggleSensorCorrection() {
        val state = _uiState.value
        _uiState.value = state.copy(applySensorCorrection = !state.applySensorCorrection)
        // Reprocess if we have data
        reprocessSpectrum()
    }

    fun captureSpectrum() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCapturing = true,
                statusMessage = "Capturing RAW frame..."
            )

            try {
                val cameraId = cameraManager.findRawCameraId()
                if (cameraId == null) {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Error: No RAW-capable camera found on this device",
                        isCapturing = false
                    )
                    return@launch
                }

                // Get camera capabilities
                val info = cameraManager.getCameraInfo(cameraId)
                _uiState.value = _uiState.value.copy(cameraInfo = info)

                cameraManager.startBackgroundThread()
                cameraManager.openCamera(cameraId)

                val exposure = _uiState.value.exposure
                val frame = cameraManager.captureRawFrame(cameraId, exposure)

                _uiState.value = _uiState.value.copy(
                    statusMessage = "Processing ${frame.width}x${frame.height} RAW frame..."
                )

                withContext(Dispatchers.Default) {
                    val strip = spectralEngine.findSpectralStrip(frame)
                    val profile = spectralEngine.extractIntensityProfile(frame, strip)
                    val peaks = spectralEngine.findPeaks(profile)

                    val cal = _uiState.value.calibrationModel
                    val spectrum = if (cal != null) {
                        val raw = spectralEngine.processSpectrum(profile, cal)
                        if (_uiState.value.applySensorCorrection) {
                            sensorCorrection.correctSpectrum(raw)
                        } else raw
                    } else emptyList()

                    _uiState.value = _uiState.value.copy(
                        intensityProfile = profile,
                        detectedPeaks = peaks,
                        spectrum = spectrum,
                        statusMessage = "Capture complete. ${peaks.size} peaks detected." +
                                if (cal == null) " Calibrate to view spectrum." else "",
                        currentScreen = if (cal != null && spectrum.isNotEmpty()) {
                            Screen.SPECTRUM
                        } else {
                            Screen.CALIBRATE
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Error: ${e.message}"
                )
            } finally {
                cameraManager.release()
                _uiState.value = _uiState.value.copy(isCapturing = false)
            }
        }
    }

    fun applyCalibration(points: List<CalibrationPoint>) {
        val model = spectralEngine.buildCalibration(points)
        _uiState.value = _uiState.value.copy(
            calibrationModel = model,
            statusMessage = "Calibration saved (${points.size} points, " +
                    "${"%.3f".format(model.slope)} nm/px)"
        )

        // Persist calibration
        viewModelScope.launch {
            val pointsJson = JSONArray().apply {
                points.forEach { p ->
                    put(JSONObject().apply {
                        put("pixelX", p.pixelX)
                        put("wavelengthNm", p.wavelengthNm)
                    })
                }
            }.toString()

            db.calibrationDao().deactivateAll()
            val id = db.calibrationDao().insert(
                CalibrationRecord(
                    name = "Calibration ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}",
                    pointsJson = pointsJson,
                    isActive = true
                )
            )
        }

        reprocessSpectrum()
    }

    private fun reprocessSpectrum() {
        val state = _uiState.value
        val cal = state.calibrationModel ?: return
        if (state.intensityProfile.isEmpty()) return

        val raw = spectralEngine.processSpectrum(state.intensityProfile, cal)
        val spectrum = if (state.applySensorCorrection) {
            sensorCorrection.correctSpectrum(raw)
        } else raw

        _uiState.value = state.copy(
            spectrum = spectrum,
            currentScreen = if (spectrum.isNotEmpty()) Screen.SPECTRUM else state.currentScreen
        )
    }

    fun saveCurrentSpectrum(name: String) {
        val spectrum = _uiState.value.spectrum
        if (spectrum.isEmpty()) return

        viewModelScope.launch {
            val dataJson = JSONArray().apply {
                spectrum.forEach { p ->
                    put(JSONObject().apply {
                        put("wavelengthNm", p.wavelengthNm)
                        put("intensity", p.intensity)
                    })
                }
            }.toString()

            db.spectrumDao().insert(
                SpectrumRecord(name = name, dataJson = dataJson)
            )

            _uiState.value = _uiState.value.copy(
                statusMessage = "Spectrum '$name' saved"
            )
            loadSavedSpectra()
        }
    }

    fun exportSpectrumCsv(name: String): String? {
        val spectrum = _uiState.value.spectrum
        if (spectrum.isEmpty()) return null

        val csv = buildString {
            appendLine("wavelength_nm,intensity")
            spectrum.forEach { p ->
                appendLine("${"%.1f".format(p.wavelengthNm)},${"%.6f".format(p.intensity)}")
            }
        }

        return try {
            val app = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "${name}.csv")
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = app.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    app.contentResolver.openOutputStream(it)?.use { os ->
                        os.write(csv.toByteArray())
                    }
                }
                "Exported to Downloads/${name}.csv"
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "${name}.csv"
                )
                file.writeText(csv)
                "Exported to ${file.absolutePath}"
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Export failed: ${e.message}"
            )
            null
        }
    }

    private fun loadSavedCalibration() {
        viewModelScope.launch {
            val active = db.calibrationDao().getActive()
            if (active != null) {
                try {
                    val points = parseCalibrationPoints(active.pointsJson)
                    if (points.size >= 2) {
                        val model = spectralEngine.buildCalibration(points)
                        _uiState.value = _uiState.value.copy(
                            calibrationModel = model,
                            statusMessage = "Loaded saved calibration: ${active.name}"
                        )
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun loadSavedSpectra() {
        viewModelScope.launch {
            val spectra = db.spectrumDao().getAll()
            _uiState.value = _uiState.value.copy(savedSpectra = spectra)
        }
    }

    fun loadSpectrum(record: SpectrumRecord) {
        try {
            val arr = JSONArray(record.dataJson)
            val spectrum = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SpectralPoint(obj.getDouble("wavelengthNm"), obj.getDouble("intensity"))
            }
            _uiState.value = _uiState.value.copy(
                spectrum = spectrum,
                currentScreen = Screen.SPECTRUM,
                statusMessage = "Loaded: ${record.name}"
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Failed to load spectrum: ${e.message}"
            )
        }
    }

    private fun parseCalibrationPoints(json: String): List<CalibrationPoint> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            CalibrationPoint(obj.getDouble("pixelX"), obj.getDouble("wavelengthNm"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
    }
}
