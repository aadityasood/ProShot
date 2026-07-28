# ProShot

ProShot is an auto-first Android camera prototype focused on producing a
consistent, natural-looking result across Android devices. The default capture
experience is designed for beginners: open the app, frame the shot, and use the
on-screen shutter or a volume key.

ProShot is still an early single-frame, CPU-based prototype. It does not yet
provide burst capture, zero-shutter-lag capture, RAW processing, semantic image
enhancement, or a production-ready release experience.

## What Works Today

- CameraX live preview with Camera2 still capture.
- Touch and hardware-volume shutter input through one acceptance path.
- Accepted-only haptic feedback and fail-fast rejection while capture is busy.
- Tap-to-focus with crop-aware AF and AE regions.
- A default-off, recreation-saveable rule-of-thirds grid.
- Adaptive beginner controls for portrait, square, and landscape windows.
- Physical-edge-stable landscape placement on portrait-natural phones,
  including direct transitions between both landscape orientations.
- Camera-permission recovery with standard Android Settings fallback and manual
  guidance when Settings cannot be opened.
- ProShot Natural v0 global tone and chroma processing.
- MediaStore JPEG output and an optional post-capture debug HUD.

## Current Capture Flow

```text
Jetpack Compose camera screen
  -> CameraX preview
  -> CameraX preview unbind
  -> Camera2 single YUV_420_888 capture
  -> copied image planes
  -> NV21 conversion and output rotation
  -> ProShot Natural v0 luma and chroma LUTs
  -> JPEG compression
  -> MediaStore save
  -> CameraX preview rebind
```

Release builds save one ProShot Natural photo. Debug builds can additionally
save an unprocessed Baseline photo, producing a Baseline and Natural pair for
comparison.

## Status Vocabulary

- `IMPLEMENTED`: present in the current runtime feature path.
- `DECLARED/CONFIGURED`: present in project configuration but unused by the
  current runtime path.
- `PLANNED`: approved future work with no current implementation.
- `RESEARCH`: exploratory direction that is not committed product behavior.

## Feature Status

| Feature or area | Status | Repository truth |
|:---|:---|:---|
| Beginner capture surface | `IMPLEMENTED` | Adaptive Compose controls, touch and volume shutter, haptics, saveable grid, accessibility semantics, and rotation-stable physical placement |
| Preview and capture ownership | `IMPLEMENTED` | Activity-retained runtime coordinates CameraX preview unbind, Camera2 capture, and preview rebind with fail-fast serialization |
| Pre-capture sequence | `IMPLEMENTED` | Sequential AE warm-up, AF wait/lock, and crop-aware default or user-tap focus regions |
| Still capture | `IMPLEMENTED` | One Camera2 `YUV_420_888` frame per accepted shutter action |
| Camera2 session compatibility | `IMPLEMENTED` | Modern `SessionConfiguration` path on API 28+ with a legacy API 26-27 fallback |
| ProShot Natural v0 | `IMPLEMENTED` | Pure-Kotlin global luma tone-curve and chroma-saturation LUT processing |
| Photo output | `IMPLEMENTED` | JPEG output through MediaStore under `Pictures/ProShot` on API 29+; API 26-28 uses the default shared location and legacy write permission |
| Permission recovery | `IMPLEMENTED` | Package-details Settings route, bounded Applications Settings fallback, manual recovery guidance, and adaptive-icon compatibility |
| Post-capture debug HUD | `IMPLEMENTED` | Capability summary plus optional timing and focus diagnostics shown after capture |
| Hilt camera ownership | `IMPLEMENTED` | Application/activity bootstrap, capture runtime, preview controller, capture controller, resource factory, and session creator are injected or Hilt-bound |
| Compatibility tiers | `IMPLEMENTED` classification / `PLANNED` routing | Capability policy classifies devices, but every accepted shutter still uses the same single-frame path |
| Native `proshot` library | `DECLARED/CONFIGURED` | C++17 JNI proof builds and loads but is not used for image processing |
| Runtime-unused dependencies | `DECLARED/CONFIGURED` | CameraX Extensions, TensorFlow Lite, TFLite GPU, MediaPipe Vision, Coil, and Room are declared but unused by runtime features |
| OpenCV integration | `PLANNED` | A catalog version and commented setup notes exist, but OpenCV is not linked |
| Persistent session and ZSL | `PLANNED` | Persistent Camera2 ownership, bounded pre-shutter history, and frame selection |
| Burst, RAW, alignment, and merge | `PLANNED` | Multi-frame capture, registration, merge, and ghost rejection |
| Semantic and local enhancement | `PLANNED` | Soft masks, face/skin/scene analysis, and masked regional processing |
| GPU and high-bit output | `PLANNED` | Possible GPU processing, linear/high-bit color, HEIF, and Ultra HDR |
| AI capture advisor | `RESEARCH` | Optional advisory capture policy with a deterministic fallback |

## Current Limitations

- Every shutter action still tears down the CameraX preview and opens a fresh
  Camera2 session, so capture is not yet designed for rapid shooting.
- Processing starts from a vendor-produced YUV frame rather than RAW or a
  linear high-bit pipeline.
- ProShot Natural v0 performs only global luma and chroma transforms; localized
  and semantic enhancement is not implemented.
- There is no in-app gallery/review surface, flash control, zoom/lens selector,
  exposure control, level indicator, front-camera mode, or persistent settings.
- On Android 8-9, saving requires the legacy external-storage write permission;
  the current UI does not provide a separate request/recovery flow for it.
- Several ML, storage, and image-loading dependencies are configured for future
  work but are not active in the runtime path.

## Platform Baseline

- Minimum SDK: 26 (Android 8.0)
- Target SDK: 35
- Compile SDK: 36
- CameraX: stable 1.6.1
- Required hardware: at least one camera
- In-app permission request and recovery flow: camera
- Android 8-9 also declares the legacy external-storage write permission
- No gallery-read, location, or internet permission is declared

## Build And Verification

From the repository root on Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat lint
```

With an Android device connected:

```powershell
.\gradlew.bat connectedAndroidTest
.\gradlew.bat installDebug
```

Native proof-library build:

```powershell
.\gradlew.bat externalNativeBuildDebug
```

## Architecture

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the detailed runtime
boundaries, implemented ownership model, configured dependencies, planned work,
and pre-release watch items.

## License

Copyright (C) 2026 Aaditya Sood.

ProShot is free software licensed under the GNU General Public License,
version 3 only (`GPL-3.0-only`). See [`LICENSE`](LICENSE) for the complete
terms.

The license applies to ProShot-owned source code and documentation. Third-party
components remain under their respective licenses. The GPL does not grant
permission to use the ProShot or ProShot Natural names, project logos, or other
brand identifiers as trademarks or to imply endorsement by the project.

Separate commercial licensing may be available from the copyright holder for
uses that require different terms.
