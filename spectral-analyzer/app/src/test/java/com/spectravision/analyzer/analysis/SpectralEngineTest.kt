package com.spectravision.analyzer.analysis

import com.spectravision.analyzer.camera.RawCameraManager.RawFrame
import com.spectravision.analyzer.data.CalibrationPoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpectralEngineTest {

    private lateinit var engine: SpectralEngine

    @Before
    fun setup() {
        engine = SpectralEngine()
    }

    // ── Calibration Model ──────────────────────────────────────────────

    @Test
    fun `buildCalibration with 2 points produces correct linear model`() {
        val points = listOf(
            CalibrationPoint(pixelX = 100.0, wavelengthNm = 436.0),
            CalibrationPoint(pixelX = 500.0, wavelengthNm = 656.0)
        )

        val model = engine.buildCalibration(points)

        // slope = (656 - 436) / (500 - 100) = 220 / 400 = 0.55 nm/px
        assertEquals(0.55, model.slope, 0.001)

        // intercept = 436 - 0.55 * 100 = 381
        assertEquals(381.0, model.intercept, 0.001)

        // Verify forward mapping
        assertEquals(436.0, model.wavelengthAt(100.0), 0.001)
        assertEquals(656.0, model.wavelengthAt(500.0), 0.001)

        // Verify inverse mapping
        assertEquals(100.0, model.pixelAt(436.0), 0.001)
        assertEquals(500.0, model.pixelAt(656.0), 0.001)
    }

    @Test
    fun `buildCalibration with 3 points uses least-squares fit`() {
        val points = listOf(
            CalibrationPoint(100.0, 436.0),
            CalibrationPoint(300.0, 546.0),
            CalibrationPoint(500.0, 656.0)
        )

        val model = engine.buildCalibration(points)

        // These 3 points are perfectly collinear, so same result as 2-point
        assertEquals(0.55, model.slope, 0.001)
        assertEquals(381.0, model.intercept, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCalibration with fewer than 2 points throws`() {
        engine.buildCalibration(listOf(CalibrationPoint(100.0, 436.0)))
    }

    // ── Spectral Strip Detection ───────────────────────────────────────

    @Test
    fun `findSpectralStrip finds brightest horizontal band`() {
        // Create a 100x50 frame with a bright strip at rows 20-30
        val width = 100
        val height = 50
        val data = ShortArray(width * height) { 100 } // background

        // Add bright strip at rows 22-28
        for (y in 22..28) {
            for (x in 0 until width) {
                data[y * width + x] = 1000
            }
        }

        val frame = RawFrame(
            width = width,
            height = height,
            data = data,
            bayerPattern = 0,
            blackLevel = intArrayOf(0, 0, 0, 0),
            whiteLevel = 4095
        )

        val (start, end) = engine.findSpectralStrip(frame, stripHeight = 10)

        // The detected strip center should be around row 25
        val center = (start + end) / 2
        assertTrue("Strip center ($center) should be near row 25", center in 20..30)
    }

    // ── Intensity Profile ──────────────────────────────────────────────

    @Test
    fun `extractIntensityProfile returns normalized values`() {
        val width = 200
        val height = 50
        val data = ShortArray(width * height) { 0 }

        // Create a gaussian-like peak at column 100
        for (y in 20..30) {
            for (x in 0 until width) {
                val dist = Math.abs(x - 100)
                val intensity = (1000 * Math.exp(-dist * dist / 200.0)).toInt()
                data[y * width + x] = intensity.toShort()
            }
        }

        val frame = RawFrame(width, height, data, 0, intArrayOf(0, 0, 0, 0), 4095)
        val profile = engine.extractIntensityProfile(frame, Pair(20, 30))

        // Peak should be at or near index 100
        val peakIdx = profile.indices.maxByOrNull { profile[it] }!!
        assertEquals(100, peakIdx)

        // Max value should be 1.0 (normalized)
        assertEquals(1.0, profile.maxOrNull()!!, 0.001)

        // Values far from peak should be near 0
        assertTrue(profile[0] < 0.01)
        assertTrue(profile[199] < 0.01)
    }

    @Test
    fun `extractIntensityProfile subtracts black level`() {
        val width = 50
        val height = 20
        val blackLevel = 64
        val data = ShortArray(width * height) { (blackLevel + 100).toShort() }

        // One column should be brighter
        for (y in 0 until height) {
            data[y * width + 25] = (blackLevel + 500).toShort()
        }

        val frame = RawFrame(width, height, data, 0, intArrayOf(blackLevel, blackLevel, blackLevel, blackLevel), 4095)
        val profile = engine.extractIntensityProfile(frame, Pair(0, height - 1))

        // Column 25 should be the peak
        val peakIdx = profile.indices.maxByOrNull { profile[it] }!!
        assertEquals(25, peakIdx)
        assertEquals(1.0, profile[25], 0.001)

        // Other columns should be (100 / 500) = 0.2
        assertEquals(0.2, profile[0], 0.01)
    }

    // ── Peak Detection ─────────────────────────────────────────────────

    @Test
    fun `findPeaks detects local maxima above threshold`() {
        val profile = DoubleArray(100) { 0.1 }

        // Add 3 peaks
        profile[20] = 0.8
        profile[50] = 0.6
        profile[80] = 0.9

        val peaks = engine.findPeaks(profile, threshold = 0.3, minSeparation = 5)

        assertEquals(3, peaks.size)
        assertTrue(peaks.contains(20))
        assertTrue(peaks.contains(50))
        assertTrue(peaks.contains(80))
    }

    @Test
    fun `findPeaks respects minimum separation`() {
        val profile = DoubleArray(100) { 0.1 }

        // Two peaks very close together
        profile[50] = 0.8
        profile[53] = 0.7

        val peaks = engine.findPeaks(profile, threshold = 0.3, minSeparation = 10)

        // Only the first should be detected
        assertEquals(1, peaks.size)
        assertEquals(50, peaks[0])
    }

    @Test
    fun `findPeaks ignores values below threshold`() {
        val profile = DoubleArray(100) { 0.1 }
        profile[50] = 0.2 // below threshold

        val peaks = engine.findPeaks(profile, threshold = 0.3)
        assertTrue(peaks.isEmpty())
    }

    // ── Full Spectrum Processing ───────────────────────────────────────

    @Test
    fun `processSpectrum maps pixel profile to wavelength spectrum`() {
        // Create a simple profile with a peak at pixel 200
        val profile = DoubleArray(400) { 0.0 }
        for (i in profile.indices) {
            val dist = Math.abs(i - 200)
            profile[i] = Math.exp(-dist * dist / 500.0)
        }

        // Calibration: pixel 0 = 400 nm, pixel 399 = 750 nm
        val cal = SpectralEngine.CalibrationModel(
            slope = (750.0 - 400.0) / 399.0,  // ~0.877 nm/px
            intercept = 400.0
        )

        val spectrum = engine.processSpectrum(profile, cal, resolution = 1.0)

        // Should cover 400-750 nm
        assertTrue(spectrum.isNotEmpty())
        assertEquals(400.0, spectrum.first().wavelengthNm, 0.1)
        assertEquals(750.0, spectrum.last().wavelengthNm, 0.1)

        // Peak should be near wavelength at pixel 200
        val expectedPeakWl = cal.wavelengthAt(200.0) // ~575 nm
        val actualPeak = spectrum.maxByOrNull { it.intensity }!!
        assertEquals(expectedPeakWl, actualPeak.wavelengthNm, 2.0)
    }

    @Test
    fun `processSpectrum handles empty profile`() {
        val profile = DoubleArray(0)
        val cal = SpectralEngine.CalibrationModel(0.5, 400.0)

        val spectrum = engine.processSpectrum(profile, cal)
        assertTrue(spectrum.isEmpty())
    }

    // ── Full Pipeline ──────────────────────────────────────────────────

    @Test
    fun `analyze runs complete pipeline`() {
        val width = 400
        val height = 100
        val data = ShortArray(width * height) { 100 }

        // Create a spectral strip at rows 45-55 with a peak at column 200
        for (y in 45..55) {
            for (x in 0 until width) {
                val dist = Math.abs(x - 200)
                val intensity = 100 + (3000 * Math.exp(-dist * dist / 500.0)).toInt()
                data[y * width + x] = intensity.toShort()
            }
        }

        val frame = RawFrame(width, height, data, 0, intArrayOf(0, 0, 0, 0), 4095)
        val cal = SpectralEngine.CalibrationModel(
            slope = (750.0 - 400.0) / 399.0,
            intercept = 400.0
        )

        val spectrum = engine.analyze(frame, cal)

        assertTrue(spectrum.isNotEmpty())
        // Peak should be near 575 nm (pixel 200 with this calibration)
        val peak = spectrum.maxByOrNull { it.intensity }!!
        assertEquals(575.0, peak.wavelengthNm, 5.0)
    }
}
