package com.spectravision.analyzer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.spectravision.analyzer.SpectraVisionApp
import com.spectravision.analyzer.analysis.SpectralEngine
import com.spectravision.analyzer.camera.RawCameraManager
import com.spectravision.analyzer.data.CalibrationPoint
import com.spectravision.analyzer.data.SpectralPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: RawCameraManager
    private val spectralEngine = SpectralEngine()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraManager = RawCameraManager(this)

        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpectralAnalyzerApp()
                }
            }
        }
    }

    @Composable
    fun SpectralAnalyzerApp() {
        var currentScreen by remember { mutableStateOf(Screen.CAPTURE) }
        var spectrum by remember { mutableStateOf<List<SpectralPoint>>(emptyList()) }
        var intensityProfile by remember { mutableStateOf(DoubleArray(0)) }
        var detectedPeaks by remember { mutableStateOf<List<Int>>(emptyList()) }
        var calibrationModel by remember {
            mutableStateOf<SpectralEngine.CalibrationModel?>(null)
        }
        var isCapturing by remember { mutableStateOf(false) }
        var statusMessage by remember { mutableStateOf("Ready") }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top navigation bar
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen == Screen.CAPTURE,
                    onClick = { currentScreen = Screen.CAPTURE },
                    label = { Text("Capture") },
                    icon = {}
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.CALIBRATE,
                    onClick = { currentScreen = Screen.CALIBRATE },
                    label = { Text("Calibrate") },
                    icon = {}
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.SPECTRUM,
                    onClick = { currentScreen = Screen.SPECTRUM },
                    label = { Text("Spectrum") },
                    icon = {}
                )
            }

            // Status bar
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Screen content
            when (currentScreen) {
                Screen.CAPTURE -> CaptureScreen(
                    isCapturing = isCapturing,
                    onCapture = {
                        lifecycleScope.launch {
                            isCapturing = true
                            statusMessage = "Capturing RAW frame..."
                            try {
                                val cameraId = cameraManager.findRawCameraId()
                                if (cameraId == null) {
                                    statusMessage = "Error: No RAW-capable camera found"
                                    isCapturing = false
                                    return@launch
                                }

                                cameraManager.startBackgroundThread()
                                cameraManager.openCamera(cameraId)
                                val frame = cameraManager.captureRawFrame(cameraId)

                                withContext(Dispatchers.Default) {
                                    val strip = spectralEngine.findSpectralStrip(frame)
                                    intensityProfile = spectralEngine.extractIntensityProfile(
                                        frame, strip
                                    )
                                    detectedPeaks = spectralEngine.findPeaks(intensityProfile)

                                    calibrationModel?.let { cal ->
                                        spectrum = spectralEngine.processSpectrum(
                                            intensityProfile, cal
                                        )
                                    }
                                }

                                statusMessage = "Capture complete. " +
                                        "${detectedPeaks.size} peaks detected."
                                if (calibrationModel != null) {
                                    currentScreen = Screen.SPECTRUM
                                } else {
                                    statusMessage += " Calibrate to view spectrum."
                                    currentScreen = Screen.CALIBRATE
                                }
                            } catch (e: Exception) {
                                statusMessage = "Error: ${e.message}"
                            } finally {
                                cameraManager.release()
                                isCapturing = false
                            }
                        }
                    },
                    hasCalibration = calibrationModel != null
                )

                Screen.CALIBRATE -> CalibrationScreen(
                    intensityProfile = intensityProfile,
                    detectedPeaks = detectedPeaks,
                    onCalibrationComplete = { points ->
                        calibrationModel = spectralEngine.buildCalibration(points)
                        statusMessage = "Calibration saved " +
                                "(${points.size} points, " +
                                "${calibrationModel!!.slope.format(3)} nm/px)"

                        // Reprocess with new calibration if profile exists
                        if (intensityProfile.isNotEmpty()) {
                            spectrum = spectralEngine.processSpectrum(
                                intensityProfile, calibrationModel!!
                            )
                            currentScreen = Screen.SPECTRUM
                        }
                    }
                )

                Screen.SPECTRUM -> {
                    if (spectrum.isNotEmpty()) {
                        SpectrumChart(
                            spectrum = spectrum,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No spectrum data. Capture and calibrate first.")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.release()
    }
}

@Composable
fun CaptureScreen(
    isCapturing: Boolean,
    onCapture: () -> Unit,
    hasCalibration: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "SpectraVision",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (hasCalibration) {
                "Calibrated. Ready to capture spectrum."
            } else {
                "Not calibrated. Capture will start calibration wizard."
            },
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Ensure diffraction grating is attached over the camera lens.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onCapture,
            enabled = !isCapturing,
            modifier = Modifier.size(width = 200.dp, height = 60.dp)
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Capture", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)

enum class Screen { CAPTURE, CALIBRATE, SPECTRUM }
