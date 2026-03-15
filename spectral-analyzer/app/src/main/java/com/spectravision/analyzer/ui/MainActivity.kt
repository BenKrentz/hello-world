package com.spectravision.analyzer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
}

@Composable
fun SpectralAnalyzerApp(viewModel: SpectralViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top navigation bar
        NavigationBar {
            NavigationBarItem(
                selected = state.currentScreen == Screen.CAPTURE,
                onClick = { viewModel.setScreen(Screen.CAPTURE) },
                label = { Text("Capture") },
                icon = { Icon(Icons.Default.Camera, "Capture") }
            )
            NavigationBarItem(
                selected = state.currentScreen == Screen.CALIBRATE,
                onClick = { viewModel.setScreen(Screen.CALIBRATE) },
                label = { Text("Calibrate") },
                icon = { Icon(Icons.Default.Tune, "Calibrate") }
            )
            NavigationBarItem(
                selected = state.currentScreen == Screen.SPECTRUM,
                onClick = { viewModel.setScreen(Screen.SPECTRUM) },
                label = { Text("Spectrum") },
                icon = { Icon(Icons.Default.ShowChart, "Spectrum") }
            )
            NavigationBarItem(
                selected = state.currentScreen == Screen.HISTORY,
                onClick = {
                    viewModel.loadSavedSpectra()
                    viewModel.setScreen(Screen.HISTORY)
                },
                label = { Text("History") },
                icon = { Icon(Icons.Default.History, "History") }
            )
        }

        // Status bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Screen content
        when (state.currentScreen) {
            Screen.CAPTURE -> CaptureScreen(
                isCapturing = state.isCapturing,
                hasCalibration = state.calibrationModel != null,
                exposure = state.exposure,
                cameraInfo = state.cameraInfo,
                onCapture = { viewModel.captureSpectrum() },
                onUpdateExposure = { iso, time, auto ->
                    viewModel.updateExposure(iso, time, auto)
                }
            )

            Screen.CALIBRATE -> CalibrationScreen(
                intensityProfile = state.intensityProfile,
                detectedPeaks = state.detectedPeaks,
                referenceLines = state.referenceLines,
                onCalibrationComplete = { points -> viewModel.applyCalibration(points) }
            )

            Screen.SPECTRUM -> SpectrumScreen(
                spectrum = state.spectrum,
                referenceLines = state.referenceLines,
                applySensorCorrection = state.applySensorCorrection,
                onToggleSensorCorrection = { viewModel.toggleSensorCorrection() },
                onSave = { name -> viewModel.saveCurrentSpectrum(name) },
                onExport = { name -> viewModel.exportSpectrumCsv(name) }
            )

            Screen.HISTORY -> HistoryScreen(
                savedSpectra = state.savedSpectra,
                onLoad = { record -> viewModel.loadSpectrum(record) },
                onDelete = { /* TODO */ }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    isCapturing: Boolean,
    hasCalibration: Boolean,
    exposure: com.spectravision.analyzer.camera.RawCameraManager.ExposureSettings,
    cameraInfo: com.spectravision.analyzer.camera.RawCameraManager.CameraInfo?,
    onCapture: () -> Unit,
    onUpdateExposure: (Int?, Long?, Boolean?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SpectraVision",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (hasCalibration)
                "Calibrated. Ready to capture spectrum."
            else
                "Not calibrated. Capture will start calibration wizard.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Ensure diffraction grating is attached over the camera lens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Exposure controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Exposure Settings", style = MaterialTheme.typography.titleSmall)

                Spacer(modifier = Modifier.height(8.dp))

                // Auto exposure toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Auto Exposure", modifier = Modifier.weight(1f))
                    Switch(
                        checked = exposure.autoExposure,
                        onCheckedChange = { onUpdateExposure(null, null, it) }
                    )
                }

                if (!exposure.autoExposure) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // ISO slider
                    Text("ISO: ${exposure.iso}", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = exposure.iso.toFloat(),
                        onValueChange = { onUpdateExposure(it.toInt(), null, null) },
                        valueRange = 50f..3200f,
                        steps = 0
                    )

                    // Shutter speed slider (displayed as fraction)
                    val shutterMs = exposure.exposureTimeNs / 1_000_000.0
                    val shutterLabel = if (shutterMs < 1.0) {
                        "1/${(1000.0 / shutterMs).toInt()}s"
                    } else {
                        "${"%.0f".format(shutterMs)}ms"
                    }
                    Text(
                        "Shutter: $shutterLabel",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = Math.log10(exposure.exposureTimeNs.toDouble()).toFloat(),
                        onValueChange = { logVal ->
                            val ns = Math.pow(10.0, logVal.toDouble()).toLong()
                            onUpdateExposure(null, ns, null)
                        },
                        valueRange = 6f..9f, // 1ms to 1000ms in log scale
                        steps = 0
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Capture button
        Button(
            onClick = onCapture,
            enabled = !isCapturing,
            modifier = Modifier.size(width = 220.dp, height = 64.dp)
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Capturing...")
            } else {
                Icon(Icons.Default.Camera, "Capture", modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Capture Spectrum", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

enum class Screen { CAPTURE, CALIBRATE, SPECTRUM, HISTORY }
