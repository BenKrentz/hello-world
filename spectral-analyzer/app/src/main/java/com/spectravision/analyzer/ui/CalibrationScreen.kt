package com.spectravision.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.spectravision.analyzer.data.CalibrationPoint

/**
 * Calibration wizard screen. Shows the live intensity profile and allows
 * the user to tap on known spectral lines to set calibration points.
 *
 * Workflow:
 * 1. Point camera at a known light source (e.g., fluorescent lamp)
 * 2. The intensity profile shows peaks corresponding to emission lines
 * 3. User taps a peak and enters the known wavelength
 * 4. Repeat for at least 2 peaks
 * 5. Save calibration
 */
@Composable
fun CalibrationScreen(
    intensityProfile: DoubleArray,
    detectedPeaks: List<Int>,
    onCalibrationComplete: (List<CalibrationPoint>) -> Unit,
    modifier: Modifier = Modifier
) {
    var calibrationPoints by remember { mutableStateOf(listOf<CalibrationPoint>()) }
    var showWavelengthDialog by remember { mutableStateOf(false) }
    var selectedPixelX by remember { mutableStateOf(0.0) }
    var wavelengthInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Calibration",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tap on a peak to assign a known wavelength. " +
                    "Need at least 2 points. " +
                    "(${calibrationPoints.size} set so far)",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Intensity profile canvas with tap detection
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val pixelX = (offset.x / size.width) * intensityProfile.size
                            selectedPixelX = pixelX.toDouble()
                            showWavelengthDialog = true
                        }
                    }
            ) {
                val w = size.width
                val h = size.height

                // Draw intensity profile
                if (intensityProfile.isNotEmpty()) {
                    for (i in 0 until intensityProfile.size - 1) {
                        val x1 = (i.toFloat() / intensityProfile.size) * w
                        val x2 = ((i + 1).toFloat() / intensityProfile.size) * w
                        val y1 = h - (intensityProfile[i].toFloat() * h)
                        val y2 = h - (intensityProfile[i + 1].toFloat() * h)

                        drawLine(
                            color = Color.Green,
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 2f
                        )
                    }
                }

                // Highlight detected peaks
                for (peak in detectedPeaks) {
                    val x = (peak.toFloat() / intensityProfile.size) * w
                    drawCircle(
                        color = Color.Yellow,
                        radius = 6f,
                        center = Offset(x, h - (intensityProfile[peak].toFloat() * h)),
                        style = Stroke(width = 2f)
                    )
                }

                // Mark calibration points
                for (point in calibrationPoints) {
                    val x = (point.pixelX.toFloat() / intensityProfile.size) * w
                    drawLine(
                        color = Color.Red,
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 2f
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Calibration points list
        calibrationPoints.forEach { point ->
            Text(
                text = "Pixel ${point.pixelX.toInt()} → ${point.wavelengthNm} nm",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Save button
        Button(
            onClick = { onCalibrationComplete(calibrationPoints) },
            enabled = calibrationPoints.size >= 2,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Save Calibration")
        }
    }

    // Wavelength input dialog
    if (showWavelengthDialog) {
        AlertDialog(
            onDismissRequest = { showWavelengthDialog = false },
            title = { Text("Enter Known Wavelength") },
            text = {
                Column {
                    Text("Pixel position: ${selectedPixelX.toInt()}")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = wavelengthInput,
                        onValueChange = { wavelengthInput = it },
                        label = { Text("Wavelength (nm)") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Common lines: 436nm (Hg), 546nm (Hg), " +
                                "578nm (Hg), 589nm (Na), 656nm (H-alpha)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    wavelengthInput.toDoubleOrNull()?.let { wl ->
                        calibrationPoints = calibrationPoints +
                                CalibrationPoint(selectedPixelX, wl)
                    }
                    wavelengthInput = ""
                    showWavelengthDialog = false
                }) {
                    Text("Set")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    wavelengthInput = ""
                    showWavelengthDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
