package com.spectravision.analyzer.analysis

import com.spectravision.analyzer.data.SpectralPoint

/**
 * Corrects measured spectra for the camera sensor's non-uniform spectral response.
 *
 * Camera sensors have wavelength-dependent quantum efficiency (QE) — they are
 * more sensitive to some wavelengths than others. Without correction, a measured
 * spectrum will show artificially high intensity where the sensor is more sensitive
 * (typically green ~550 nm) and lower intensity at the extremes (violet, deep red).
 *
 * This class provides a generic correction curve based on typical silicon CMOS
 * sensor response (Sony IMX / Samsung ISOCELL families). For best accuracy,
 * users should calibrate with a known broadband source (e.g., incandescent bulb
 * approximates a blackbody).
 */
class SensorResponseCorrection {

    /**
     * Generic silicon CMOS sensor relative quantum efficiency curve.
     * Values are normalized so that the peak (around 550 nm) = 1.0.
     * Derived from published QE curves of Sony IMX377 and similar sensors.
     *
     * The map key is wavelength in nm, value is relative QE [0, 1].
     */
    private val genericSensorQE: Map<Int, Double> = mapOf(
        380 to 0.10,
        390 to 0.15,
        400 to 0.25,
        410 to 0.33,
        420 to 0.42,
        430 to 0.50,
        440 to 0.57,
        450 to 0.63,
        460 to 0.68,
        470 to 0.73,
        480 to 0.77,
        490 to 0.81,
        500 to 0.85,
        510 to 0.89,
        520 to 0.93,
        530 to 0.96,
        540 to 0.98,
        550 to 1.00,
        560 to 0.99,
        570 to 0.97,
        580 to 0.94,
        590 to 0.90,
        600 to 0.86,
        610 to 0.81,
        620 to 0.76,
        630 to 0.70,
        640 to 0.64,
        650 to 0.58,
        660 to 0.52,
        670 to 0.46,
        680 to 0.40,
        690 to 0.35,
        700 to 0.30,
        710 to 0.25,
        720 to 0.21,
        730 to 0.17,
        740 to 0.14,
        750 to 0.11,
        760 to 0.08
    )

    /**
     * Apply sensor response correction to a measured spectrum.
     * Divides each intensity value by the sensor QE at that wavelength,
     * then re-normalizes to [0, 1].
     */
    fun correctSpectrum(spectrum: List<SpectralPoint>): List<SpectralPoint> {
        if (spectrum.isEmpty()) return spectrum

        val corrected = spectrum.map { point ->
            val qe = interpolateQE(point.wavelengthNm)
            val correctedIntensity = if (qe > 0.05) {
                point.intensity / qe
            } else {
                // Below 5% QE, the correction amplifies noise too much
                point.intensity / 0.05
            }
            SpectralPoint(point.wavelengthNm, correctedIntensity)
        }

        // Re-normalize to [0, 1]
        val max = corrected.maxOfOrNull { it.intensity } ?: 1.0
        return if (max > 0) {
            corrected.map { SpectralPoint(it.wavelengthNm, it.intensity / max) }
        } else corrected
    }

    /**
     * Generate a custom correction curve from a reference measurement.
     * The user captures a known broadband source (e.g., incandescent bulb ~2700K)
     * and the measured spectrum is compared to the expected blackbody curve.
     *
     * @param measured spectrum of the reference source
     * @param referenceTemperatureK color temperature of the reference source
     * @return correction factors (multiply measured intensity by these)
     */
    fun buildCustomCorrection(
        measured: List<SpectralPoint>,
        referenceTemperatureK: Double = 2700.0
    ): Map<Double, Double> {
        return measured.associate { point ->
            val expected = planckianIntensity(point.wavelengthNm, referenceTemperatureK)
            val correction = if (point.intensity > 0.01) {
                expected / point.intensity
            } else 1.0
            point.wavelengthNm to correction
        }
    }

    /**
     * Relative spectral radiance of a blackbody at temperature T (Planck's law).
     * Normalized so peak = 1.0.
     */
    private fun planckianIntensity(wavelengthNm: Double, temperatureK: Double): Double {
        val lambda = wavelengthNm * 1e-9 // convert to meters
        val h = 6.62607015e-34  // Planck constant
        val c = 2.99792458e8    // speed of light
        val k = 1.380649e-23    // Boltzmann constant

        val exp = (h * c) / (lambda * k * temperatureK)
        if (exp > 500) return 0.0 // prevent overflow

        val radiance = (2 * h * c * c) / (Math.pow(lambda, 5.0) * (Math.exp(exp) - 1))
        // Normalize relative to peak wavelength (Wien's law: λ_max ≈ 2898/T μm)
        val peakLambda = 2.898e-3 / temperatureK
        val peakExp = (h * c) / (peakLambda * k * temperatureK)
        val peakRadiance = (2 * h * c * c) / (Math.pow(peakLambda, 5.0) * (Math.exp(peakExp) - 1))

        return radiance / peakRadiance
    }

    /**
     * Linear interpolation of the QE curve at a given wavelength.
     */
    private fun interpolateQE(wavelengthNm: Double): Double {
        val wl = wavelengthNm.toInt()
        val keys = genericSensorQE.keys.sorted()

        if (wl <= keys.first()) return genericSensorQE[keys.first()]!!
        if (wl >= keys.last()) return genericSensorQE[keys.last()]!!

        // Find bracketing keys
        val lower = keys.last { it <= wl }
        val upper = keys.first { it >= wl }

        if (lower == upper) return genericSensorQE[lower]!!

        val qeLower = genericSensorQE[lower]!!
        val qeUpper = genericSensorQE[upper]!!
        val frac = (wavelengthNm - lower) / (upper - lower)

        return qeLower + frac * (qeUpper - qeLower)
    }
}
