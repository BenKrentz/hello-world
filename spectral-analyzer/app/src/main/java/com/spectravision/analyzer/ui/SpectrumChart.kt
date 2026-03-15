package com.spectravision.analyzer.ui

import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.spectravision.analyzer.data.SpectralPoint

/**
 * Compose wrapper around MPAndroidChart for rendering spectra.
 * The spectrum line is colored using the actual visible light color
 * at each wavelength for an intuitive display.
 */
@Composable
fun SpectrumChart(
    spectrum: List<SpectralPoint>,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(true)
                setBackgroundColor(Color.BLACK)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    textColor = Color.WHITE
                    gridColor = Color.DKGRAY
                    axisMinimum = 400f
                    axisMaximum = 750f
                    labelCount = 8
                }

                axisLeft.apply {
                    textColor = Color.WHITE
                    gridColor = Color.DKGRAY
                    axisMinimum = 0f
                    axisMaximum = 1f
                }

                axisRight.isEnabled = false
                legend.textColor = Color.WHITE
            }
        },
        update = { chart ->
            if (spectrum.isNotEmpty()) {
                val entries = spectrum.map { point ->
                    Entry(point.wavelengthNm.toFloat(), point.intensity.toFloat())
                }

                val dataSet = LineDataSet(entries, "Spectrum").apply {
                    setDrawCircles(false)
                    lineWidth = 2f
                    color = Color.WHITE
                    setDrawFilled(true)
                    fillAlpha = 50

                    // Color each segment by its visible light wavelength
                    val colors = spectrum.map { wavelengthToColor(it.wavelengthNm) }
                    setColors(colors)
                }

                chart.data = LineData(dataSet)
                chart.invalidate()
            }
        }
    )
}

/**
 * Convert a wavelength in nm to an approximate visible light RGB color.
 * Based on Dan Bruton's algorithm for mapping wavelength to RGB.
 */
fun wavelengthToColor(wavelengthNm: Double): Int {
    val w = wavelengthNm
    val (r, g, b) = when {
        w < 380 -> Triple(0.0, 0.0, 0.0)
        w < 440 -> Triple(-(w - 440) / (440 - 380), 0.0, 1.0)
        w < 490 -> Triple(0.0, (w - 440) / (490 - 440), 1.0)
        w < 510 -> Triple(0.0, 1.0, -(w - 510) / (510 - 490))
        w < 580 -> Triple((w - 510) / (580 - 510), 1.0, 0.0)
        w < 645 -> Triple(1.0, -(w - 645) / (645 - 580), 0.0)
        w < 781 -> Triple(1.0, 0.0, 0.0)
        else -> Triple(0.0, 0.0, 0.0)
    }

    // Intensity falloff at edges of visible range
    val factor = when {
        w < 380 -> 0.0
        w < 420 -> 0.3 + 0.7 * (w - 380) / (420 - 380)
        w < 700 -> 1.0
        w < 781 -> 0.3 + 0.7 * (780 - w) / (780 - 700)
        else -> 0.0
    }

    return Color.rgb(
        (r * factor * 255).toInt().coerceIn(0, 255),
        (g * factor * 255).toInt().coerceIn(0, 255),
        (b * factor * 255).toInt().coerceIn(0, 255)
    )
}
