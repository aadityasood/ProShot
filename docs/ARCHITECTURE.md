# ProShot System Architecture

## Purpose

This document describes the repository as it exists today and separates that
runtime architecture from configured dependencies, approved future work, and
research.

Status vocabulary:

- `IMPLEMENTED`: present in the current runtime feature path.
- `DECLARED/CONFIGURED`: present in build or native configuration but not used
  by the current runtime feature path.
- `PLANNED`: approved future engineering work with no current implementation.
- `RESEARCH`: exploratory direction that is not committed product behavior.

## Current Runtime Data Flow

The sole executed image path is `IMPLEMENTED`:

```text
Jetpack Compose camera screen
  -> CameraX preview
  -> CameraCaptureRuntime-owned CameraX unbind
  -> Camera2 single YUV_420_888 capture
  -> copied image planes on the JVM heap
  -> NV21 conversion and output-orientation rotation
  -> ProShot Natural v0 global luma and chroma LUTs
  -> JPEG compression
  -> MediaStore save (Pictures/ProShot on API 29+; default shared location on API 26-28)
  -> CameraCaptureRuntime-owned CameraX rebind
```

This is an early single-frame, CPU-based prototype. It is not currently a
temporal, RAW, linear high-bit, semantic, or native multi-frame pipeline.

## Implemented Components

### CameraX Preview and Capture Lifecycle Ownership

`CameraCaptureRuntime` is configuration-retained and Activity-owned. Hilt keeps
one runtime core through configuration recreation, while the owner is released
when the logical Activity finishes rather than becoming process-global. Its
generation-based lifecycle state machine covers detached, attaching, ready,
capturing, rebinding, and detaching states. Initial preview attachment is
awaited before readiness is exposed to the UI. A fail-fast coroutine mutex
covers the complete unbind, capture/process/save, and rebind transaction; a
concurrent request returns a stable busy result without preview, capture,
processing, save, or rebind work.

- `CameraXPreviewController` shares the retained Activity component and runtime
  generation domain. It wraps `ProcessCameraProvider` resolution, binding, and
  all-use-case unbinding on the Main thread. A replacement attachment advances
  the shared generation before a stale pre-recreation detach can act, so that
  detach returns without invalidating, unbinding, or clearing the replacement
  preview. Replacement and final detach still clear lifecycle-owner, view, and
  preview-use-case references; only the process-owned provider is cached through
  the application context.
- `Camera2CaptureResourceOwner` encapsulates the physical Camera2 device,
  session, `ImageReader`, and callback thread lifecycle. Its synchronized
  register-or-close gate closes known resources atomically and gives a pending
  open or session callback a named 1,000 ms terminal grace period. A callback
  delivered during that period closes its late resource; otherwise the looper
  is terminated so a missing callback cannot retain the per-capture thread.
  After looper termination, the app cannot act on a vendor callback that is
  never delivered to its callback path.

Compose UI (`CameraScreen`) is preview-agnostic. A structured effect requests
attach/detach, observes readiness, and delegates shutter actions to
`CameraCaptureRuntime`; it does not resolve or bind the CameraX provider.


### Camera2 Capture

`SingleFrameCaptureController` opens the primary back camera, configures an
`ImageReader`, and captures one `YUV_420_888` frame. The current pre-capture
order is:

1. AE warm-up with a minimum of 3 and maximum of 12 repeating results.
2. AF wait/lock with a maximum of 30 repeating callbacks.
3. One still capture and immediate copied-plane copy.

Default-center focus prefers `CONTINUOUS_PICTURE` and falls back to `AUTO`.
A user tap with a supported AF region prefers `AUTO`; unsupported tap targeting
is reported explicitly and uses the default-center AF strategy. AUTO submits
one trigger request, waits for that trigger result, and then requires two
qualifying repeating results. Continuous-picture mode uses an eight-result
gate. AF and AE regions use crop-aware normalized coordinate mapping.

### Capture-to-Save Coordination

`CaptureCoordinator` always calls `captureSingleFrame`. It then:

1. Converts copied YUV planes to a contiguous NV21 byte array.
2. Rotates NV21 pixels to the resolved output orientation.
3. Optionally compresses and saves an unprocessed baseline JPEG in debug mode.
4. Applies the ProShot Natural v0 LUT processor.
5. Compresses the processed NV21 array to JPEG.
6. Saves through `GalleryImageSaver`.

Normal release capture saves one final JPEG. Debug paired output performs an
additional baseline compression and save and then saves the processed result.

### ProShot Natural v0 Processing

The current input is vendor-processed YUV converted to NV21. The pure-Kotlin
processor creates a new NV21 array and applies:

- A global luma tone-curve LUT using piecewise-linear interpolation.
- A global chroma-saturation LUT centered on neutral chroma value 128.

The runtime does not consume `globalWarmthShiftKelvin`, skin controls, face
targets, regional tuning, or semantic masks. Those fields exist in the
`LookProfile` contract, but warmth, skin, face, semantic, and other localized
transforms remain `PLANNED`.

Global tone and color transforms may operate on the full frame. Any future
localized, semantic, subject-specific, or region-selective enhancement must
use a soft mask and preserve a deterministic unmasked fallback.

### MediaStore Output

`GalleryImageSaver` writes JPEG bytes to Android MediaStore. On Android 10 and
later it uses `MediaStore.Images.Media.RELATIVE_PATH` and publishes app-owned
output under `Pictures/ProShot`. On API 26-28 it uses the default shared
MediaStore location and the legacy write permission; that output is not
app-owned in the scoped-storage sense. After publication, Android, gallery apps,
device security, and user cloud-backup settings govern access and copies.

### Post-Capture Debug HUD

The debug overlay is `IMPLEMENTED`, but it is not a comprehensive compatibility
report and its capture metrics are not live. The screen displays stored values
after a photo completes.

The fixed capability summary renders:

- Selected policy tier and look profile.
- Camera hardware level.
- GPU-delegate capability indicator.
- Semantic-mask capability indicator.

When available, the timing section renders preview unbind/rebind, camera open,
session configuration, AE warm-up, AF wait/lock, still capture/copy, total
Camera2 capture, YUV conversion/rotation, baseline compression/save, look
processing, final compression/save, and total pipeline durations.

When available, the focus section renders camera and lens characteristics,
available and selected AF modes, requested and effective focus source, fallback
reason, normalized target and region sizes, region support and applied regions,
metering crop, result-side AF/AE regions and crop, trigger status, request
provenance, AE/AF outcomes, timestamp correlation, and captured dimensions.

A report containing app, SDK, device, ABI, memory, complete support flags,
fallback reasons, and save outcomes is `PLANNED`.

### Hilt Bootstrap

Hilt application and activity bootstrap is `IMPLEMENTED` through
`@HiltAndroidApp` and `@AndroidEntryPoint`. Capture controllers, encoders,
savers, and service dependencies are not yet fully injected.

## Data Models

`CopiedImageFrame` is `IMPLEMENTED`. It stores:

- Image format.
- Width and height.
- Sensor timestamp.
- Copied planes, each containing row stride, pixel stride, and a byte array.

It does not store ISO, exposure time, sharpness score, or semantic metadata. A
richer capture metadata model and frame-scoring data are `PLANNED`.

## Declared or Configured Components

### Native Proof

The C++17 `proshot` shared library is `DECLARED/CONFIGURED`. It builds from
`native-pipeline.cpp`, links only the Android log library, and `MainActivity`
attempts to load it at startup. `stringFromJNI()` is declared as a JNI proof
method but is not invoked. The native library remains outside the
image-processing path. Alignment and merge implementation are absent.

OpenCV integration is `PLANNED`, not configured. The version catalog contains
an unused `4.9.0` value and CMake contains commented future setup instructions,
but there is no OpenCV dependency, package discovery, include path, or linked
OpenCV target.

### Dependency Inventory

| Component | Repository version source | Status | Current use |
|:---|:---|:---|:---|
| CameraX core, Camera2 adapter, lifecycle, and view | `1.4.0-alpha05` catalog value | `IMPLEMENTED` | Preview and lifecycle binding |
| Camera2 platform API | Android platform | `IMPLEMENTED` | Still capture and pre-capture control |
| Jetpack Compose and Material3 | Compose BOM `2024.05.00` | `IMPLEMENTED` | Camera UI and debug overlay |
| Hilt | `2.51.1` catalog value | `IMPLEMENTED` | Application/activity bootstrap only |
| CameraX Extensions | `1.4.0-alpha05` catalog value | `DECLARED/CONFIGURED` | Dependency declared; no extension route |
| TensorFlow Lite and GPU delegate | `2.16.1` catalog value | `DECLARED/CONFIGURED` | Dependencies declared; no model loading or inference |
| MediaPipe Tasks Vision | `0.10.13` catalog value | `DECLARED/CONFIGURED` | Dependency declared; no runtime task |
| Coil Compose | `2.7.0` catalog value | `DECLARED/CONFIGURED` | Dependency declared; no runtime image-loading feature |
| Room runtime, KTX, and compiler | `2.6.1` catalog value | `DECLARED/CONFIGURED` | Dependencies and compiler declared; no database path |
| Native `proshot` shared library | CMake `3.22.1`, C++17 | `DECLARED/CONFIGURED` | Library load attempt and uninvoked JNI proof method only |
| OpenCV integration | Unused catalog value `4.9.0` | `PLANNED` | Not integrated or linked |

CameraX is pre-release and requires a controlled future stable-version
migration. Dependency removal, activation, and upgrades are not part of the
current runtime architecture.

## Compatibility Policy Boundary

`CompatibilityPolicy` is an `IMPLEMENTED` capability classifier. It can return
`FULL_COMPUTATIONAL`, `YUV_BURST`, `SINGLE_FRAME_ENHANCED`, or `BASIC_CAPTURE`
decisions. The UI currently uses the selected look profile and displays the
classification, but no router dispatches tier-specific capture paths.

All shutter actions that reach `CaptureCoordinator` execute the same
single-frame YUV path. A camera-unavailable classification does not produce a
photo. A real compatibility router and every tier-specific route are `PLANNED`.

## Planned Architecture

Approved `PLANNED` work includes:

- A persistent Camera2 session building on the implemented ownership seams.
- Bounded pre-shutter frame history and ZSL evaluation.
- Timestamp-correlated frame selection and sharpness scoring.
- RAW or YUV burst capture, alignment, merge, and ghost rejection.
- HDR/night processing and device-specific quirk routing.
- OpenCV or other native processing only after explicit integration and tests.
- Semantic masks and soft-masked local enhancement.
- Warmth, skin, face, regional tuning, and metadata controls.
- Possible OpenGL ES compute work or AGSL runtime-shader evaluation.
- Linear/high-bit processing and HEIF or Ultra HDR output.
- Full compatibility reporting and runtime/instrumented lifecycle coverage.

Optional advisory AI remains `RESEARCH`; it is not committed product behavior.

## Memory Pressure Baseline

The current path allocates or retains several CPU-side buffers during a photo:

- Copied Y, U, and V plane byte arrays.
- A contiguous NV21 conversion buffer.
- A rotated NV21 buffer when rotation is required.
- A processed NV21 output array.
- JPEG compression streams and final JPEG byte arrays.
- Additional baseline JPEG compression and output work in debug paired mode.

No stable live-heap multiplier has been measured. Future GPU work may reduce
selected processing buffers, but frame copying, JPEG encoding, and output arrays
can still create CPU heap pressure.

## Privacy and Permission Baseline

The manifest currently declares:

- `CAMERA`.
- `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28` for Android 8 and 9 saving.

It does not declare gallery-read, location, or `INTERNET` permission. Normal
saving is local through MediaStore; this does not control gallery access or
user-enabled cloud backup after publication.

## Current Technical Debt

- Each shutter action still tears down all CameraX use cases and opens a new
  Camera2 session; there is no persistent capture session.
- `SingleFrameCaptureController` remains a large capture-policy area despite
  per-capture physical resource ownership moving to a dedicated owner.
- No runtime/instrumented test covers the complete unbind, capture, and rebind
  lifecycle under interruption or memory pressure.
- Compatibility decisions are classifications without an execution router.
- The native library is a proof only, and OpenCV is not integrated.
- Several dependencies are declared but unused.
- CameraX uses a pre-release catalog version.

## Pre-Release Watch Items

Before a production release, explicitly review:

- The current `android:allowBackup="true"` policy and backup behavior.
- Privacy-policy readiness for the shipped feature and permission set.
- Image metadata controls and metadata minimization.
- Dependency cleanup, stable-version migration, and release notes.
- Runtime instrumentation and representative device-matrix coverage.
- Release-build behavior, permissions, shrinking, logging, and save validation.
