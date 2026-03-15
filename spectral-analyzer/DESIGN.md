# SpectraVision — Android Spectral Analysis App

## Overview

SpectraVision is an Android application that turns a smartphone camera into a
visible-light spectrometer (400–750 nm) using a diffraction grating attachment
and the Camera2 RAW API.

## How It Works

### Physical Setup

```
   Light Source
       |
       v
 [Diffraction Grating Film]  ← attached over phone camera lens
       |
       v
 [Phone Camera Sensor]       ← captures dispersed spectrum as an image
```

A transmission diffraction grating (500–1000 lines/mm, ~$3 on Amazon) is placed
over the camera lens. Incoming light is angularly dispersed by wavelength,
producing a rainbow band across the camera sensor.

### Processing Pipeline

```
1. RAW Capture (Camera2 API, DNG format)
       |
2. Spectral Strip Extraction (find the bright band in the image)
       |
3. Wavelength Calibration (map pixel position → nm using known references)
       |
4. Intensity Normalization (correct for sensor response & grating efficiency)
       |
5. Spectrum Output (wavelength vs. relative intensity, 400–750 nm)
```

### Calibration

The app uses a **two-point calibration** with known light sources:
- **Mercury vapor** (fluorescent lamp): strong lines at 436 nm, 546 nm, 578 nm
- **Sodium vapor** (street light): doublet at 589 nm
- **LED reference**: user can input known peak wavelength

Once two known wavelength–pixel correspondences are established, a linear
dispersion model maps all pixels to wavelengths:

    λ(x) = λ₀ + (Δλ/Δx) · (x - x₀)

For higher accuracy, a quadratic or cubic fit can be used with 3+ reference
points.

### Sensor Response Correction

Each camera sensor has a wavelength-dependent quantum efficiency. The app ships
with response curves for common sensors (Sony IMX series, Samsung ISOCELL) and
allows users to load custom profiles. The measured intensity is divided by the
sensor response at each wavelength to yield a corrected spectrum.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    UI Layer (Jetpack Compose)            │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ CameraView  │  │ SpectrumPlot │  │ Calibration   │  │
│  │ Preview      │  │ (MPAndroid   │  │ Wizard        │  │
│  │              │  │  Chart)      │  │               │  │
│  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘  │
│         │                 │                   │          │
├─────────┼─────────────────┼───────────────────┼──────────┤
│         │          Domain Layer               │          │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌───────▼───────┐  │
│  │ CameraManager│  │ SpectralEngine│  │ Calibration   │  │
│  │ (Camera2 API)│  │ (analysis)    │  │ Repository    │  │
│  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘  │
│         │                 │                   │          │
├─────────┼─────────────────┼───────────────────┼──────────┤
│         │           Data Layer                │          │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌───────▼───────┐  │
│  │ RAW Image    │  │ Sensor       │  │ Room DB       │  │
│  │ DNG Files    │  │ Profiles     │  │ (calibrations │  │
│  │              │  │ (JSON)       │  │  & spectra)   │  │
│  └──────────────┘  └──────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## Key Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Camera API | Camera2 | RAW/DNG capture support required |
| Image format | DNG (16-bit) | Linear, unprocessed sensor data |
| UI framework | Jetpack Compose | Modern, declarative |
| Charting | MPAndroidChart | Mature, performant for real-time |
| Persistence | Room | Structured calibration + spectra storage |
| Language | Kotlin | Android standard |
| Min SDK | 23 (Android 6.0) | Camera2 RAW support baseline |

## Spectral Resolution

With a 1000 lines/mm grating and typical phone camera (12 MP, 1.4 μm pixels):
- Usable spectral range: ~400–750 nm (350 nm span)
- Pixels across spectrum: ~1000–2000 (depends on grating distance)
- **Effective resolution: ~2–5 nm** (sufficient to resolve emission lines)

## Future Enhancements

- Computational spectral estimation (no grating, RGB-only fallback)
- Absorbance measurement (I/I₀ with reference spectrum)
- Export to CSV, JCAMP-DX formats
- Cloud database of reference spectra for material identification
- Integration with spectral databases (NIST Atomic Spectra)
