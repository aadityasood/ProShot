# ProShot — System Architecture

## Overview

ProShot provides a reference-grade computational photography pipeline on
Android. The app captures multi-frame bursts, aligns and merges them for
HDR/noise reduction, performs semantic scene analysis, and applies per-region
enhancements with the ProShot Natural color profile.

## Pipeline Architecture

```
┌─────────────────────────────────────────────────────┐
│              LAYER 1: CAPTURE                        │
│  Camera2 API (Kotlin)                                │
│  • Burst capture: 5 frames, bracketed exposure       │
│  • Frame buffering (ring buffer of recent frames)    │
│  • Sharpness-based reference frame selection         │
│  • RAW (DNG) capture when available, YUV fallback    │
│  Input: scene    Output: List<ImageFrame>             │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│              LAYER 2: ALIGNMENT & MERGING            │
│  OpenCV + Custom C++ (NDK)                           │
│  • Gaussian pyramid (4 levels) for coarse-to-fine    │
│  • Tile-based motion estimation (16x16 → 8x8 tiles) │
│  • Sub-pixel alignment via L2 distance matching      │
│  • Adaptive temporal merging with ghost rejection    │
│  Input: List<ImageFrame>  Output: MergedHDRImage     │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│              LAYER 3: SEMANTIC ANALYSIS              │
│  MediaPipe + TFLite (Kotlin + GPU Delegate)          │
│  • Face detection: BlazeFace (<1ms)                  │
│  • Face landmarks: 478-point 3D mesh (~5ms)          │
│  • Skin mask: polygon from face landmark subset      │
│  • Person segmentation: selfie seg model (~8ms)      │
│  • Scene classification: MobileNetV3 (~15ms)         │
│  Input: MergedHDRImage  Output: SemanticMasks        │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│              LAYER 4: ENHANCEMENT                    │
│  GPU Compute (AGSL / OpenGL ES 3.1)                  │
│  • Per-region tone mapping (face, sky, background)   │
│  • Skin-aware noise reduction                        │
│     - Bilateral filter on skin (sigma_c=50, σ_s=15)  │
│     - Non-local means on non-skin (h=10)             │
│  • Face exposure optimization (target: 55-65% lum)   │
│  • Shadow fill on faces (+20-30% lift, +3K warmth)   │
│  • Adaptive sharpening (0 on skin, moderate elsewhere)│
│  Input: MergedHDRImage + SemanticMasks               │
│  Output: EnhancedImage                               │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│              LAYER 5: COLOR SCIENCE                   │
│  Custom Processing (GPU)                              │
│  • ProShot Natural tone curve (gentle S-curve)       │
│     Blacks: +5, Shadows: +8%, Highlights: -5%        │
│  • Color temperature: +3-5K global, +8-10K skin      │
│  • Saturation: -5% overall, -10% skin, +15% sky     │
│  • Skin tone protection (hue-locked in HSL)          │
│  • Per-region color adjustment using semantic masks   │
│  Input: EnhancedImage + SemanticMasks                │
│  Output: FinalImage                                  │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│              LAYER 6: OUTPUT                          │
│  • HEIF encoding (JPEG fallback)                     │
│  • EXIF metadata preservation                        │
│  • MediaStore integration (gallery visible)          │
│  • Before/after comparison storage                   │
└─────────────────────────────────────────────────────┘
```

> **Warmth-unit TODO:** The "+3-5K" and "+8-10K" values in Layer 5 above refer
> to thousands of Kelvin (3,000–5,000 K shift). The `LookProfile` data contract
> stores `globalWarmthShiftKelvin` and `RegionTuning.warmthShiftKelvin` as small
> integers (e.g. 4, 8). Whether these integers represent true Kelvin deltas,
> thousands of Kelvin, or product-relative slider units **must be resolved
> before the color-science shader implementation consumes them**.

> **v0 Processing Hook Implementation:**
> In version 0, a pure Kotlin CPU-based processing hook (`LookProfileNv21Processor`) bridges the capture
> and gallery output by applying the global tone curve and global saturation scale to the NV21 buffer
> before JPEG compression. Both luma (tone curve) and chroma (saturation) transforms are precomputed as
> 256-element byte look-up tables (LUTs) per frame, eliminating per-pixel floating-point math and reducing
> the inner loops to simple array index operations. This enables fast, deterministic correctness testing
> on JVM and Android before native shader/GPU compute blocks are fully wired.

> **Output privacy note:** Normal saves go through Android MediaStore so photos
> appear in the user's gallery. After a photo is saved there, access is governed
> by Android, gallery apps, user-granted permissions, device security, and any
> user-enabled cloud backup. ProShot should remain local-first and
> permission-minimal by default.

> **Capture Orchestration Coordinator:**
> The physical capture orchestration is decoupled from Compose UI state through `CaptureCoordinator`. The
> coordinator manages physical camera burst capture (single frame YUV for v0), orientation adjustments,
> look profile color science mapping, and gallery output saving, running stages on appropriate background
> thread dispatchers (`Dispatchers.Default` for pixel work and `Dispatchers.IO` for MediaStore interactions).

> **Capture Latency Diagnostics:**
> To measure and diagnose latency across the pipeline, `CaptureTiming` and `CaptureTimingTracker` collect and report millisecond-level durations of key stages (CameraX unbind/rebind, Camera2 open/configure/warm-up/autofocus/capture, YUV conversion, look profile processing, and saves). In debug builds, these diagnostics are propagated to the Compose UI layer and displayed in the debug HUD, while remaining completely inactive and hidden in release builds. AE warm-up and AF wait latencies are measured sequentially in separate pre-capture phases to maximize focus and exposure reliability.

> **Pre-Capture AF/AE Policy:**
> Pre-capture is executed in sequential phases: a bounded AE warm-up phase (gate min 3, max 12 frames), followed by a bounded AF wait/lock phase (max 30 frames). Focus-readiness rules ensure that: (1) null AF state is never accepted as ready in active AF modes (AUTO, CONTINUOUS_PICTURE); this prevents premature exit on Qualcomm HALs where null appears during trigger processing; (2) AUTO mode uses `AF_TRIGGER_MIN_FRAMES=2` to guard against pre-trigger pipeline-depth results, accepting only `FOCUSED_LOCKED`; (3) CONTINUOUS_PICTURE mode uses a separate `AF_PASSIVE_MIN_FRAMES=8` gate (~267 ms at 30 fps) to prevent accepting stale `PASSIVE_FOCUSED` state carried from a prior CameraX session's lens position, then accepts `FOCUSED_LOCKED` or `PASSIVE_FOCUSED`; (4) fixed-focus cameras skip the AF wait completely; (5) unknown active AF modes fail closed and wait for the bounded frame cap rather than silently accepting unfocused output. Under the T11.2 strategy, the still-capture controller experimentally prefers `CONTROL_AF_MODE_CONTINUOUS_PICTURE` over `CONTROL_AF_MODE_AUTO` when both are available, using `CONTROL_AF_MODE_AUTO` as a fallback. Explicit trigger starts (`AF_TRIGGER_START`) are only sent for `AUTO` mode. Focus targeting regions are mapped from normalized point coordinates (defaulting to center `(0.5, 0.5)`) using a tighter autofocus size (`0.04f`) and a broader autoexposure size (`0.10f`) relative to the active array, mapped using `FocusMeteringCoordinateMapper` and applied as `CONTROL_AF_REGIONS` and `CONTROL_AE_REGIONS` across pre-capture and still-capture requests when supported.

> **Focus/Lens Diagnostics:**
> Focus/lens diagnostics are debug-only evidence used to analyze physical lens limits, hardware levels, available focal lengths, timestamp correlation, and AE/AF pre-capture outcomes for a still capture. This data helps diagnose close-subject focus issues without changing active capture policies.

## Data Types

```kotlin
data class ImageFrame(
    val buffer: ByteBuffer,       // RAW or YUV data
    val width: Int,
    val height: Int,
    val format: Int,              // ImageFormat.RAW_SENSOR or YUV_420_888
    val exposureNs: Long,         // Exposure time in nanoseconds
    val iso: Int,                 // Sensor sensitivity
    val sharpnessScore: Float,    // Laplacian variance
    val timestamp: Long           // Capture timestamp
)

data class SemanticMasks(
    val faceMasks: List<FaceMask>,  // Per-face masks
    val skinMask: FloatArray,       // [0, 1] soft mask for skin regions
    val skyMask: FloatArray,        // [0, 1] soft mask for sky
    val personMask: FloatArray,     // [0, 1] soft mask for person(s)
    val sceneType: SceneType        // INDOOR, OUTDOOR_DAY, OUTDOOR_NIGHT, etc.
)

data class FaceMask(
    val boundingBox: RectF,
    val landmarks: List<PointF>,    // 478 landmarks
    val skinRegion: FloatArray,     // Soft mask for this face's skin
    val meanLuminance: Float        // Average brightness of face region
)

sealed class SceneType {
    object IndoorWarm : SceneType()
    object IndoorCool : SceneType()
    object OutdoorDay : SceneType()
    object OutdoorGoldenHour : SceneType()
    object OutdoorNight : SceneType()
    object Portrait : SceneType()
    object Landscape : SceneType()
}
```

## Key Algorithms

### Frame Selection (Sharpness Scoring)
```
For each captured frame:
  1. Convert to grayscale
  2. Apply 3x3 Laplacian kernel
  3. sharpnessScore = variance(laplacian_output)
  4. Reference frame = argmax(sharpnessScore)
```

### Skin Tone Protection
```
For each pixel in skin mask region:
  1. Convert RGB → HSL
  2. Store original hue (H_orig)
  3. Apply all tone/contrast adjustments in L and S channels
  4. Restore H = H_orig (hue lock)
  5. Clamp S adjustment to ±10% of original
  6. Convert HSL → RGB
```

### ProShot Natural Tone Curve
```
Control points (input → output, 0-255 range):
  (0, 5)       — slight black lift
  (32, 38)     — shadow lift
  (64, 72)     — lower-mid lift
  (128, 132)   — midtone subtle boost
  (192, 188)   — highlight gentle compression
  (224, 218)   — upper highlight rolloff
  (255, 250)   — white point soft clip
Interpolation: cubic spline
```

## Dependencies

| Library | Version | Purpose |
|:---|:---|:---|
| CameraX | 1.4.x | Preview rendering |
| Camera2 | platform | Burst capture control |
| OpenCV Android | 4.9.x | Frame alignment (NDK) |
| TensorFlow Lite | 2.16.x | ML model inference |
| MediaPipe Tasks | 0.10.x | Face detection, landmarks |
| Hilt | 2.51.x | Dependency injection |
| Jetpack Compose | 1.7.x | UI framework |
| Material3 | 1.3.x | Design system |
| Coil | 2.7.x | Image loading in gallery |
| Room | 2.6.x | Photo metadata database |

## Performance Targets

| Operation | Budget | Hardware |
|:---|:---|:---|
| Burst capture (5 frames) | <500ms | Camera sensor |
| Frame alignment | <300ms | CPU (OpenCV NDK) |
| Frame merging | <200ms | GPU (Vulkan/GLES) |
| Semantic analysis | <50ms | NPU/GPU (TFLite) |
| Enhancement | <150ms | GPU (AGSL) |
| Color science | <50ms | GPU |
| **Total** | **<1.5s** | |

## Graceful Degradation

Graceful degradation preserves the selected look profile. The app may reduce
capture cost, processing precision, or acceleration, but it should not fall back
to a generic Android-camera look when a valid image buffer exists.

| Capability Missing | Fallback | Look Rule |
|:---|:---|:---|
| Full RAW/manual/burst support available | `FULL_COMPUTATIONAL` | Full pipeline with selected look profile |
| No RAW support | `YUV_BURST` using YUV_420_888 | Same ProShot Natural look profile |
| Camera2 LEGACY or external camera level | `SINGLE_FRAME_ENHANCED` | Single frame still receives tone/color/skin treatment |
| No burst support | `SINGLE_FRAME_ENHANCED` | Preserve ProShot Natural tone curve and warmth |
| No GPU delegate | CPU processing fallback | Same look, slower execution |
| Low memory (<3GB) | Reduce burst count or single-frame enhanced path | Same look, lower cost |
| No face detected | Skip face-specific processing, apply global and available regional tuning | Same look without face-only adjustments |
| Processing timeout (>3s) | Skip alignment, apply color science only | Same look profile on the best available frame |
| Camera unavailable or no usable image buffer | `BASIC_CAPTURE` | Emergency path; apply minimal look transform if a frame exists |

`CompatibilityPolicy` owns the tier decision so device-specific workarounds stay
out of core capture and processing algorithms.
