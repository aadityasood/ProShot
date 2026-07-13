# ProShot

ProShot is an Android camera prototype focused on a consistent, natural image
processing result across Android devices.

The current runtime path uses CameraX for preview and Camera2 for one
`YUV_420_888` still frame. It copies the image planes, converts and rotates the
frame as NV21, applies the ProShot Natural v0 global luma tone-curve and global
chroma-saturation lookup tables, compresses to JPEG, and saves through Android
MediaStore.

## Goals

- Keep the default capture experience simple for non-photographers.
- Preserve a stable ProShot-owned visual target across supported devices.
- Separate current runtime behavior from configured dependencies, approved
  future work, and research.
- Keep look-profile data separate from capture and processing algorithms.

## Status Vocabulary

- `IMPLEMENTED`: present in the current runtime feature path.
- `DECLARED/CONFIGURED`: present in build or native configuration but not used
  by the current runtime feature path.
- `PLANNED`: approved future engineering work with no current implementation.
- `RESEARCH`: exploratory direction that is not committed product behavior.

## Feature Status

| Feature or area | Status | Repository truth |
|:---|:---|:---|
| Beginner camera surface | `IMPLEMENTED` | Jetpack Compose UI with CameraX preview and tap focus input |
| Pre-capture sequence | `IMPLEMENTED` | Sequential AE warm-up, AF wait/lock, and crop-aware focus regions |
| Still capture | `IMPLEMENTED` | One Camera2 `YUV_420_888` frame per shutter action |
| ProShot Natural v0 | `IMPLEMENTED` | Pure-Kotlin global luma tone-curve and chroma-saturation LUT processing |
| Photo output | `IMPLEMENTED` | JPEG saved through MediaStore under `Pictures/ProShot` on API 29+; API 26-28 uses the default shared MediaStore location and legacy write permission; debug builds can save a baseline/final pair |
| Post-capture debug HUD | `IMPLEMENTED` | Capability summary plus optional timing and focus diagnostics shown after capture |
| Hilt bootstrap | `IMPLEMENTED` | Application and activity use Hilt; capture and service objects are not fully injected |
| Native `proshot` library | `DECLARED/CONFIGURED` | C++17 JNI proof builds and loads but is not in the image-processing path |
| Runtime-unused dependencies | `DECLARED/CONFIGURED` | CameraX Extensions, TensorFlow Lite, TFLite GPU, MediaPipe Vision, Coil, and Room are declared but unused by runtime features |
| OpenCV integration | `PLANNED` | A catalog version and commented setup notes exist, but OpenCV is not a dependency or linked native library |
| Persistent session and ZSL evaluation | `PLANNED` | Persistent capture ownership and bounded pre-shutter frame history |
| Burst, RAW, alignment, and merge | `PLANNED` | Multi-frame capture, frame selection, registration, merge, and ghost rejection |
| Semantic and local enhancement | `PLANNED` | Soft masks, face/skin/scene analysis, and masked regional processing |
| GPU and high-bit output | `PLANNED` | Possible OpenGL ES compute or AGSL runtime-shader work, linear/high-bit color, HEIF, and Ultra HDR |
| AI capture advisor | `RESEARCH` | Optional advisory capture policy with deterministic fallback |

## Build

From the repository root on Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
```

Native debug build:

```powershell
.\gradlew.bat externalNativeBuildDebug
```

## License

No open-source license has been selected yet. Public visibility does not grant reuse, distribution, or modification rights beyond what GitHub permits for viewing the repository.
