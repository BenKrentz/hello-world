package com.spectravision.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectravision.analyzer.analysis.ReferenceSpectra
import com.spectravision.analyzer.data.CalibrationPoint

/**
 * Calibration wizard screen. Shows the intensity profile and allows
 * the user to tap on known spectral lines to set calibration points.
 *
 * Workflow:
 * 1. Point camera at a known light source (e.g., fluorescent lamp)
 * 2. The intensity profile shows peaks corresponding to emission lines
 * 3. User taps a peak and enters the known wavelength (or selects from reference list)
 * 4. Repeat for at least 2 peaks
 * 5. Save calibration
 */
@Composable
fun CalibrationScreen(
    intensityProfile: DoubleArray,
    detectedPeaks: List<Int>,
    referenceLines: List<ReferenceSpectra.EmissionLine>,
    onCalibrationComplete: (List<CalibrationPoint>) -> Unit,
    modifier: Modifier = Modifier
) {
    var calibrationPoints by remember { mutableStateOf(listOf<CalibrationPoint>()) }
    var showWavelengthDialog by remember { mutableStateOf(false) }
    var selectedPixelX by remember { mutableStateOf(0.0) }
    var wavelengthInput by remember { mutableStateOf("") }
    var selectedElement by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Wavelength Calibration",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (intensityProfile.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No data captured yet.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Go to Capture tab, point camera at a fluorescent lamp\n" +
                                "(CFL or tube light), and capture a frame first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        Text(
            text = "Tap on a peak to assign its known wavelength. " +
                    "Need at least 2 points (${calibrationPoints.size} set).",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Intensity profile canvas with tap detection
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            // Snap to nearest detected peak if close enough
                            val pixelX = (offset.x / size.width) * intensityProfile.size
                            val nearestPeak = detectedPeaks.minByOrNull {
                                Math.abs(it - pixelX)
                            }
                            selectedPixelX = if (nearestPeak != null &&
                                Math.abs(nearestPeak - pixelX) < intensityProfile.size * 0.02
                            ) {
                                nearestPeak.toDouble()
                            } else {
                                pixelX.toDouble()
                            }
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
                        val y1 = h - (intensityProfile[i].toFloat() * h * 0.9f) - h * 0.05f
                        val y2 = h - (intensityProfile[i + 1].toFloat() * h * 0.9f) - h * 0.05f

                        drawLine(
                            color = Color.Green,
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 1.5f
                        )
                    }
                }

                // Highlight detected peaks with yellow circles
                for (peak in detectedPeaks) {
                    val x = (peak.toFloat() / intensityProfile.size) * w
                    val y = h - (intensityProfile[peak].toFloat() * h * 0.9f) - h * 0.05f
                    drawCircle(
                        color = Color.Yellow,
                        radius = 8f,
                        center = Offset(x, y),
                        style = Stroke(width = 2f)
                    )
                }

                // Mark calibration points with red vertical lines
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

        Spacer(modifier = Modifier.height(8.dp))

        // Calibration points summary
        if (calibrationPoints.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Calibration Points:", style = MaterialTheme.typography.labelMedium)
                    calibrationPoints.forEachIndexed { index, point ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${index + 1}. px ${point.pixelX.toInt()} → ${"%.1f".format(point.wavelengthNm)} nm",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = {
                                    calibrationPoints = calibrationPoints.toMutableList().also {
                                        it.removeAt(index)
                                    }
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Remove", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(
                onClick = { calibrationPoints = emptyList() },
                enabled = calibrationPoints.isNotEmpty()
            ) {
                Text("Clear All")
            }
            Button(
                onClick = { onCalibrationComplete(calibrationPoints) },
                enabled = calibrationPoints.size >= 2
            ) {
                Text("Apply Calibration (${calibrationPoints.size} pts)")
            }
        }
    }

    // Wavelength input dialog
    if (showWavelengthDialog) {
        AlertDialog(
            onDismissRequest = { showWavelengthDialog = false },
            title = { Text("Assign Wavelength") },
            text = {
                Column {
                    Text("Pixel position: ${selectedPixelX.toInt()}")
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = wavelengthInput,
                        onValueChange = { wavelengthInput = it },
                        label = { Text("Wavelength (nm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Quick select — common lines:",
                        style = MaterialTheme.typography.labelMedium
                    )

                    // Element filter chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedElement == null,
                            onClick = { selectedElement = null },
                            label = { Text("All") }
                        )
                        ReferenceSpectra.elements.forEach { element ->
                            FilterChip(
                                selected = selectedElement == element,
                                onClick = {
                                    selectedElement = if (selectedElement == element) null else element
                                },
                                label = { Text(element) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Quick-select reference lines
                    val filteredLines = if (selectedElement != null) {
                        referenceLines.filter { it.element == selectedElement }
                    } else {
                        // Show most useful calibration lines
                        referenceLines.filter { it.relativeIntensity >= 0.5 }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filteredLines.forEach { line ->
                            AssistChip(
                                onClick = {
                                    wavelengthInput = "%.1f".format(line.wavelengthNm)
                                },
                                label = {
                                    Text(
                                        "${line.element} ${"%.1f".format(line.wavelengthNm)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    wavelengthInput.toDoubleOrNull()?.let { wl ->
                        if (wl in 350.0..800.0) {
                            calibrationPoints = calibrationPoints +
                                    CalibrationPoint(selectedPixelX, wl)
                        }
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
