package com.spectravision.analyzer.analysis

/**
 * Database of known emission lines for common light sources.
 * Used to assist with wavelength calibration and spectral identification.
 */
object ReferenceSpectra {

    data class EmissionLine(
        val wavelengthNm: Double,
        val element: String,
        val source: String,
        val relativeIntensity: Double = 1.0 // 0..1 relative brightness
    )

    /**
     * Common emission lines visible in everyday light sources, useful for
     * calibration. Sorted by wavelength.
     */
    val commonEmissionLines = listOf(
        // Mercury (Hg) — found in fluorescent lamps and CFLs
        EmissionLine(404.7, "Hg", "Fluorescent/CFL", 0.5),
        EmissionLine(435.8, "Hg", "Fluorescent/CFL", 0.8),
        EmissionLine(546.1, "Hg", "Fluorescent/CFL", 1.0),
        EmissionLine(577.0, "Hg", "Fluorescent/CFL", 0.7),
        EmissionLine(579.1, "Hg", "Fluorescent/CFL", 0.6),

        // Sodium (Na) — found in street lights (low-pressure sodium)
        EmissionLine(589.0, "Na", "Sodium lamp", 1.0),
        EmissionLine(589.6, "Na", "Sodium lamp", 0.9),

        // Hydrogen (H) — Balmer series, visible in hydrogen discharge tubes
        EmissionLine(410.2, "H", "Hydrogen tube (H-delta)", 0.3),
        EmissionLine(434.0, "H", "Hydrogen tube (H-gamma)", 0.5),
        EmissionLine(486.1, "H", "Hydrogen tube (H-beta)", 0.7),
        EmissionLine(656.3, "H", "Hydrogen tube (H-alpha)", 1.0),

        // Helium (He) — helium discharge tubes
        EmissionLine(447.1, "He", "Helium tube", 0.4),
        EmissionLine(501.6, "He", "Helium tube", 0.3),
        EmissionLine(587.6, "He", "Helium tube", 1.0),
        EmissionLine(667.8, "He", "Helium tube", 0.5),
        EmissionLine(706.5, "He", "Helium tube", 0.3),

        // Neon (Ne) — neon signs
        EmissionLine(585.2, "Ne", "Neon sign", 0.5),
        EmissionLine(588.2, "Ne", "Neon sign", 0.5),
        EmissionLine(603.0, "Ne", "Neon sign", 0.4),
        EmissionLine(607.4, "Ne", "Neon sign", 0.4),
        EmissionLine(616.4, "Ne", "Neon sign", 0.6),
        EmissionLine(621.7, "Ne", "Neon sign", 0.5),
        EmissionLine(626.6, "Ne", "Neon sign", 0.5),
        EmissionLine(633.4, "Ne", "Neon sign", 0.7),
        EmissionLine(638.3, "Ne", "Neon sign", 0.8),
        EmissionLine(640.2, "Ne", "Neon sign", 1.0),
        EmissionLine(650.7, "Ne", "Neon sign", 0.7),
        EmissionLine(692.9, "Ne", "Neon sign", 0.4),
        EmissionLine(703.2, "Ne", "Neon sign", 0.5)
    ).sortedBy { it.wavelengthNm }

    /** Filter lines by element symbol. */
    fun linesForElement(element: String): List<EmissionLine> =
        commonEmissionLines.filter { it.element == element }

    /** Filter lines by source description. */
    fun linesForSource(source: String): List<EmissionLine> =
        commonEmissionLines.filter { it.source.contains(source, ignoreCase = true) }

    /** All unique elements in the database. */
    val elements: List<String> = commonEmissionLines.map { it.element }.distinct().sorted()

    /**
     * Match detected peaks to known emission lines.
     * Returns a list of (peakPixelX, bestMatchLine, distanceNm) triples.
     */
    fun matchPeaks(
        peakWavelengths: List<Double>,
        toleranceNm: Double = 5.0
    ): List<Triple<Double, EmissionLine?, Double>> {
        return peakWavelengths.map { peakWl ->
            val closest = commonEmissionLines.minByOrNull {
                Math.abs(it.wavelengthNm - peakWl)
            }
            val distance = closest?.let { Math.abs(it.wavelengthNm - peakWl) } ?: Double.MAX_VALUE
            Triple(
                peakWl,
                if (distance <= toleranceNm) closest else null,
                distance
            )
        }
    }
}
