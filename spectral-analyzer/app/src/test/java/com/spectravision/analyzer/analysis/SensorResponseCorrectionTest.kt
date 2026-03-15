package com.spectravision.analyzer.analysis

import com.spectravision.analyzer.data.SpectralPoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SensorResponseCorrectionTest {

    private lateinit var correction: SensorResponseCorrection

    @Before
    fun setup() {
        correction = SensorResponseCorrection()
    }

    @Test
    fun `correctSpectrum returns empty list for empty input`() {
        val result = correction.correctSpectrum(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `correctSpectrum output is normalized to 0-1`() {
        val input = (400..750 step 10).map { wl ->
            SpectralPoint(wl.toDouble(), 0.5)
        }

        val result = correction.correctSpectrum(input)

        assertTrue(result.isNotEmpty())
        val maxIntensity = result.maxOf { it.intensity }
        assertEquals(1.0, maxIntensity, 0.001)
        assertTrue(result.all { it.intensity in 0.0..1.0 })
    }

    @Test
    fun `correctSpectrum boosts low-QE wavelengths relative to high-QE`() {
        // The sensor has peak QE around 550 nm and lower at 700 nm.
        // If we feed in uniform intensity, the corrected spectrum should
        // boost 700 nm relative to 550 nm.
        val input = listOf(
            SpectralPoint(550.0, 1.0),
            SpectralPoint(700.0, 1.0)
        )

        val result = correction.correctSpectrum(input)

        // 700 nm has lower QE, so after correction its intensity should be higher
        val at550 = result.first { it.wavelengthNm == 550.0 }.intensity
        val at700 = result.first { it.wavelengthNm == 700.0 }.intensity

        assertTrue(
            "700nm ($at700) should be boosted relative to 550nm ($at550)",
            at700 > at550
        )
    }

    @Test
    fun `correctSpectrum preserves wavelength values`() {
        val wavelengths = listOf(420.0, 500.0, 580.0, 650.0, 720.0)
        val input = wavelengths.map { SpectralPoint(it, 0.5) }

        val result = correction.correctSpectrum(input)

        assertEquals(wavelengths.size, result.size)
        result.forEachIndexed { i, point ->
            assertEquals(wavelengths[i], point.wavelengthNm, 0.001)
        }
    }

    @Test
    fun `buildCustomCorrection produces correction factors`() {
        val measured = (400..700 step 50).map { wl ->
            SpectralPoint(wl.toDouble(), 0.5)
        }

        val factors = correction.buildCustomCorrection(measured, 2700.0)

        assertTrue(factors.isNotEmpty())
        assertEquals(measured.size, factors.size)
        // All factors should be positive
        assertTrue(factors.values.all { it > 0 })
    }
}
