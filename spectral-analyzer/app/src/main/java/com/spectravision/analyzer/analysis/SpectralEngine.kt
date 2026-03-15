package com.spectravision.analyzer.analysis

import com.spectravision.analyzer.camera.RawCameraManager.RawFrame
import com.spectravision.analyzer.data.CalibrationPoint
import com.spectravision.analyzer.data.SpectralPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Core spectral analysis engine.
 *
 * Processing pipeline:
 * 1. Locate the spectral strip in the RAW image (bright horizontal band from grating)
 * 2. Extract intensity profile along the dispersion axis
 * 3. Apply wavelength calibration (pixel → nm)
 * 4. Apply sensor response correction
 * 5. Output calibrated spectrum as List<SpectralPoint>
 */
class SpectralEngine {

    /**
     * Parameters for the wavelength calibration model.
     * Linear model: λ(x) = slope * x + intercept
     */
    data class CalibrationModel(
        val slope: Double,      // nm per pixel
        val intercept: Double   // wavelength at pixel 0
    ) {
        fun wavelengthAt(pixelX: Double): Double = slope * pixelX + intercept
        fun pixelAt(wavelengthNm: Double): Double = (wavelengthNm - intercept) / slope
    }

    /**
     * Build a linear calibration model from two or more reference points.
     * Uses least-squares fit for >2 points.
     */
    fun buildCalibration(points: List<CalibrationPoint>): CalibrationModel {
        require(points.size >= 2) { "At least 2 calibration points required" }

        val n = points.size
        val sumX = points.sumOf { it.pixelX }
        val sumY = points.sumOf { it.wavelengthNm }
        val sumXY = points.sumOf { it.pixelX * it.wavelengthNm }
        val sumX2 = points.sumOf { it.pixelX * it.pixelX }

        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val intercept = (sumY - slope * sumX) / n

        return CalibrationModel(slope, intercept)
    }

    /**
     * Locate the spectral strip in the image. The diffraction grating produces
     * a bright horizontal band. We find the row range with maximum total intensity.
     *
     * @param frame RAW sensor frame
     * @param stripHeight number of rows to average (default 50)
     * @return pair of (startRow, endRow) defining the spectral strip
     */
    fun findSpectralStrip(frame: RawFrame, stripHeight: Int = 50): Pair<Int, Int> {
        // Sum each row's pixel values
        val rowSums = DoubleArray(frame.height)
        for (y in 0 until frame.height) {
            var sum = 0.0
            for (x in 0 until frame.width) {
                sum += frame.data[y * frame.width + x].toInt() and 0xFFFF
            }
            rowSums[y] = sum
        }

        // Find the center of the brightest strip of height `stripHeight`
        var bestSum = 0.0
        var bestCenter = frame.height / 2
        val halfStrip = stripHeight / 2

        for (center in halfStrip until frame.height - halfStrip) {
            var windowSum = 0.0
            for (y in (center - halfStrip) until (center + halfStrip)) {
                windowSum += rowSums[y]
            }
            if (windowSum > bestSum) {
                bestSum = windowSum
                bestCenter = center
            }
        }

        return Pair(
            max(0, bestCenter - halfStrip),
            min(frame.height - 1, bestCenter + halfStrip)
        )
    }

    /**
     * Extract the intensity profile along the dispersion (horizontal) axis
     * by averaging pixel values within the spectral strip.
     *
     * Returns an array of intensity values, one per column (pixel X position).
     * Black level is subtracted and values are normalized to [0, 1].
     */
    fun extractIntensityProfile(
        frame: RawFrame,
        stripRange: Pair<Int, Int>
    ): DoubleArray {
        val (startRow, endRow) = stripRange
        val numRows = endRow - startRow + 1
        val profile = DoubleArray(frame.width)
        val avgBlackLevel = frame.blackLevel.average()

        for (x in 0 until frame.width) {
            var sum = 0.0
            for (y in startRow..endRow) {
                val rawVal = (frame.data[y * frame.width + x].toInt() and 0xFFFF).toDouble()
                sum += max(0.0, rawVal - avgBlackLevel)
            }
            profile[x] = sum / numRows
        }

        // Normalize to [0, 1]
        val maxVal = profile.maxOrNull() ?: 1.0
        if (maxVal > 0) {
            for (i in profile.indices) {
                profile[i] /= maxVal
            }
        }

        return profile
    }

    /**
     * Apply wavelength calibration and produce the final spectrum.
     *
     * @param profile raw intensity profile (one value per pixel column)
     * @param calibration the pixel-to-wavelength mapping model
     * @param minWavelength lower bound of output range (default 400 nm)
     * @param maxWavelength upper bound of output range (default 750 nm)
     * @param resolution output spacing in nm (default 1 nm)
     */
    fun processSpectrum(
        profile: DoubleArray,
        calibration: CalibrationModel,
        minWavelength: Double = 400.0,
        maxWavelength: Double = 750.0,
        resolution: Double = 1.0
    ): List<SpectralPoint> {
        val spectrum = mutableListOf<SpectralPoint>()

        var wavelength = minWavelength
        while (wavelength <= maxWavelength) {
            val pixelX = calibration.pixelAt(wavelength)
            val intensity = interpolateProfile(profile, pixelX)

            if (intensity != null) {
                spectrum.add(SpectralPoint(wavelength, intensity))
            }

            wavelength += resolution
        }

        return spectrum
    }

    /**
     * Full pipeline: from raw frame to calibrated spectrum.
     */
    fun analyze(
        frame: RawFrame,
        calibration: CalibrationModel,
        stripHeight: Int = 50
    ): List<SpectralPoint> {
        val strip = findSpectralStrip(frame, stripHeight)
        val profile = extractIntensityProfile(frame, strip)
        return processSpectrum(profile, calibration)
    }

    /**
     * Auto-detect emission peaks in a profile for calibration assistance.
     * Returns pixel positions of local maxima above the given threshold.
     */
    fun findPeaks(
        profile: DoubleArray,
        threshold: Double = 0.3,
        minSeparation: Int = 10
    ): List<Int> {
        val peaks = mutableListOf<Int>()

        for (i in 1 until profile.size - 1) {
            if (profile[i] > threshold &&
                profile[i] > profile[i - 1] &&
                profile[i] > profile[i + 1]
            ) {
                // Check minimum separation from last peak
                if (peaks.isEmpty() || (i - peaks.last()) >= minSeparation) {
                    peaks.add(i)
                }
            }
        }

        return peaks
    }

    /**
     * Linear interpolation of the intensity profile at a fractional pixel position.
     */
    private fun interpolateProfile(profile: DoubleArray, pixelX: Double): Double? {
        if (pixelX < 0 || pixelX >= profile.size - 1) return null

        val x0 = pixelX.toInt()
        val x1 = x0 + 1
        val frac = pixelX - x0

        return profile[x0] * (1 - frac) + profile[x1] * frac
    }
}
