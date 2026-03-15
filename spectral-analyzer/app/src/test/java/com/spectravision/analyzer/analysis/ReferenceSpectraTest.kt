package com.spectravision.analyzer.analysis

import org.junit.Assert.*
import org.junit.Test

class ReferenceSpectraTest {

    @Test
    fun `commonEmissionLines is sorted by wavelength`() {
        val lines = ReferenceSpectra.commonEmissionLines
        for (i in 1 until lines.size) {
            assertTrue(
                "Lines should be sorted: ${lines[i - 1].wavelengthNm} <= ${lines[i].wavelengthNm}",
                lines[i - 1].wavelengthNm <= lines[i].wavelengthNm
            )
        }
    }

    @Test
    fun `all emission lines are in visible range`() {
        ReferenceSpectra.commonEmissionLines.forEach { line ->
            assertTrue(
                "${line.element} ${line.wavelengthNm}nm should be in 380-780 range",
                line.wavelengthNm in 380.0..780.0
            )
        }
    }

    @Test
    fun `linesForElement returns correct subset`() {
        val hgLines = ReferenceSpectra.linesForElement("Hg")
        assertTrue(hgLines.isNotEmpty())
        assertTrue(hgLines.all { it.element == "Hg" })

        // Mercury should have lines at 435.8 and 546.1
        assertTrue(hgLines.any { Math.abs(it.wavelengthNm - 435.8) < 0.1 })
        assertTrue(hgLines.any { Math.abs(it.wavelengthNm - 546.1) < 0.1 })
    }

    @Test
    fun `elements list contains expected elements`() {
        val elements = ReferenceSpectra.elements
        assertTrue(elements.contains("H"))
        assertTrue(elements.contains("He"))
        assertTrue(elements.contains("Hg"))
        assertTrue(elements.contains("Na"))
        assertTrue(elements.contains("Ne"))
    }

    @Test
    fun `matchPeaks identifies known lines within tolerance`() {
        val peaks = listOf(436.0, 546.0, 589.0)
        val matches = ReferenceSpectra.matchPeaks(peaks, toleranceNm = 2.0)

        assertEquals(3, matches.size)

        // 436 should match Hg 435.8
        assertNotNull(matches[0].second)
        assertEquals("Hg", matches[0].second!!.element)

        // 546 should match Hg 546.1
        assertNotNull(matches[1].second)
        assertEquals("Hg", matches[1].second!!.element)

        // 589 should match Na 589.0
        assertNotNull(matches[2].second)
        assertEquals("Na", matches[2].second!!.element)
    }

    @Test
    fun `matchPeaks returns null for unmatched peaks`() {
        val peaks = listOf(500.0) // No strong line here
        val matches = ReferenceSpectra.matchPeaks(peaks, toleranceNm = 1.0)

        assertEquals(1, matches.size)
        assertNull(matches[0].second)
    }
}
