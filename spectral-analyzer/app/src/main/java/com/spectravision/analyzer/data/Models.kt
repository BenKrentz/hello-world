package com.spectravision.analyzer.data

import androidx.room.*

/**
 * A single spectral data point: wavelength (nm) mapped to relative intensity.
 */
data class SpectralPoint(
    val wavelengthNm: Double,
    val intensity: Double
)

/**
 * Complete spectrum measurement with metadata.
 */
@Entity(tableName = "spectra")
data class SpectrumRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "data_json") val dataJson: String, // JSON array of SpectralPoint
    val notes: String = ""
)

/**
 * Wavelength calibration: maps pixel positions to known wavelengths.
 * At least two reference points are needed for a linear calibration.
 */
@Entity(tableName = "calibrations")
data class CalibrationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "points_json") val pointsJson: String, // JSON array of {pixelX, wavelengthNm}
    val isActive: Boolean = false
)

/**
 * A single calibration reference point.
 */
data class CalibrationPoint(
    val pixelX: Double,
    val wavelengthNm: Double
)

/**
 * Sensor spectral response profile. Maps wavelength → relative quantum efficiency
 * for each of R, G, B channels.
 */
data class SensorProfile(
    val name: String,
    val responseR: Map<Double, Double>, // wavelength (nm) → QE
    val responseG: Map<Double, Double>,
    val responseB: Map<Double, Double>
)
