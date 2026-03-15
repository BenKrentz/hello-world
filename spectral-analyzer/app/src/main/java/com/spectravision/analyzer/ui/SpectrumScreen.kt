package com.spectravision.analyzer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectravision.analyzer.analysis.ReferenceSpectra
import com.spectravision.analyzer.data.SpectralPoint
import java.text.SimpleDateFormat
import java.util.*

/**
 * Spectrum display screen with chart, analysis tools, and export options.
 */
@Composable
fun SpectrumScreen(
    spectrum: List<SpectralPoint>,
    referenceLines: List<ReferenceSpectra.EmissionLine>,
    applySensorCorrection: Boolean,
    onToggleSensorCorrection: () -> Unit,
    onSave: (String) -> Unit,
    onExport: (String) -> String?,
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember {
        mutableStateOf(
            "Spectrum ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}"
        )
    }
    var exportResult by remember { mutableStateOf<String?>(null) }

    if (spectrum.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No spectrum data", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Capture and calibrate to view spectrum.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Peak info
            val peakPoint = spectrum.maxByOrNull { it.intensity }
            if (peakPoint != null) {
                Text(
                    "Peak: ${"%.1f".format(peakPoint.wavelengthNm)} nm",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Sensor correction toggle
                FilterChip(
                    selected = applySensorCorrection,
                    onClick = onToggleSensorCorrection,
                    label = { Text("QE Correction") }
                )

                // Save button
                IconButton(onClick = { showSaveDialog = true }) {
                    Icon(Icons.Default.Save, "Save")
                }

                // Export CSV button
                IconButton(onClick = {
                    val filename = "spectrum_${
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    }"
                    exportResult = onExport(filename)
                }) {
                    Icon(Icons.Default.FileDownload, "Export CSV")
                }
            }
        }

        // Export result snackbar
        exportResult?.let { result ->
            Snackbar(
                modifier = Modifier.padding(horizontal = 8.dp),
                action = {
                    TextButton(onClick = { exportResult = null }) {
                        Text("OK")
                    }
                }
            ) {
                Text(result)
            }
        }

        // Spectrum chart
        SpectrumChart(
            spectrum = spectrum,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp)
        )

        // Summary stats
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val minWl = spectrum.first().wavelengthNm
                val maxWl = spectrum.last().wavelengthNm
                val peak = spectrum.maxByOrNull { it.intensity }!!
                val fwhm = calculateFWHM(spectrum, peak)

                StatItem("Range", "${"%.0f".format(minWl)}–${"%.0f".format(maxWl)} nm")
                StatItem("Peak λ", "${"%.1f".format(peak.wavelengthNm)} nm")
                StatItem("Points", "${spectrum.size}")
                if (fwhm != null) {
                    StatItem("FWHM", "${"%.1f".format(fwhm)} nm")
                }
            }
        }
    }

    // Save dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Spectrum") },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(saveName)
                    showSaveDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Calculate Full Width at Half Maximum of the dominant peak.
 */
private fun calculateFWHM(spectrum: List<SpectralPoint>, peak: SpectralPoint): Double? {
    val halfMax = peak.intensity / 2.0

    // Find left side crossing
    val peakIdx = spectrum.indexOf(peak)
    if (peakIdx < 1 || peakIdx >= spectrum.size - 1) return null

    var leftWl: Double? = null
    for (i in peakIdx downTo 1) {
        if (spectrum[i - 1].intensity <= halfMax && spectrum[i].intensity > halfMax) {
            // Linear interpolation
            val frac = (halfMax - spectrum[i - 1].intensity) /
                    (spectrum[i].intensity - spectrum[i - 1].intensity)
            leftWl = spectrum[i - 1].wavelengthNm +
                    frac * (spectrum[i].wavelengthNm - spectrum[i - 1].wavelengthNm)
            break
        }
    }

    var rightWl: Double? = null
    for (i in peakIdx until spectrum.size - 1) {
        if (spectrum[i].intensity > halfMax && spectrum[i + 1].intensity <= halfMax) {
            val frac = (halfMax - spectrum[i + 1].intensity) /
                    (spectrum[i].intensity - spectrum[i + 1].intensity)
            rightWl = spectrum[i + 1].wavelengthNm -
                    frac * (spectrum[i + 1].wavelengthNm - spectrum[i].wavelengthNm)
            break
        }
    }

    return if (leftWl != null && rightWl != null) rightWl - leftWl else null
}
