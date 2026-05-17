# ProShot Compatibility And Maintenance

## Product Principle

Every supported phone should aim for the same ProShot Natural output feel.
Device fallback changes the execution method, not the visual goal.

ProShot may reduce burst count, input format, acceleration, or semantic-mask
precision when a device is limited, but every valid captured image must still
route through the selected look profile. Version 1 uses ProShot Natural for all
tiers.

## Compatibility Risks

Android camera apps depend on platform and device behavior that can change over
time:

- Android OS behavior changes and Google Play target-SDK requirements.
- Camera2 hardware level differences across `LEGACY`, `LIMITED`, `FULL`, and
  `LEVEL_3` devices.
- Camera availability changes while the app is running.
- OEM camera HAL and firmware behavior after device updates.
- CameraX, TensorFlow Lite, MediaPipe, OpenCV, NDK, and GPU delegate updates.
- MediaStore and scoped-storage policy changes.

The app must query capabilities at runtime and avoid assuming that a phone
supports RAW, burst capture, manual exposure, GPU delegates, or semantic masks.

## Pipeline Tiers

`CompatibilityPolicy` selects one of four execution tiers:

| Tier | When Used | Product Rule |
|---|---|---|
| `FULL_COMPUTATIONAL` | RAW/YUV burst, manual exposure, enough memory, camera available | Use the full pipeline and ProShot Natural look profile. |
| `YUV_BURST` | RAW unavailable but YUV burst is safe | Preserve burst processing and the same ProShot Natural look. |
| `SINGLE_FRAME_ENHANCED` | Legacy/external/low-memory/no burst support | Use one frame but still apply ProShot Natural tone, warmth, skin, and highlight behavior. |
| `BASIC_CAPTURE` | Camera unavailable or no usable image buffer path | Emergency path only; apply the minimal ProShot Natural transform if a valid frame exists. |

Fallback should happen in this order:

1. RAW burst.
2. YUV burst.
3. Single-frame enhanced processing.
4. Basic capture.

The selected `LookProfile` should remain stable across these tiers unless the
user intentionally chooses another profile in a future release.

### Timeout Fallback

`ARCHITECTURE.md` documents a processing-timeout fallback (>3 s → skip
alignment, apply color science only). This is a **runtime pipeline event**, not
a static device capability, so `CompatibilityPolicy.select()` — which receives
capability flags at session start — does not handle it. Timeout fallback should
be implemented at the pipeline orchestration layer when the alignment and
merging stages ship. If a timeout occurs, the orchestrator should skip
alignment and apply the selected `LookProfile`'s color-science transforms to
the best available frame, preserving the visual identity.

## Diagnostics

Each camera session should eventually produce a privacy-safe compatibility
report with:

- app version, SDK version, device manufacturer/model, ABI, memory class
- selected camera ID and hardware level
- RAW/YUV/manual/burst/semantic/GPU support flags
- selected pipeline tier
- fallback reasons
- selected look profile ID
- capture, processing, and MediaStore save result

Diagnostics must not include user images, thumbnails, faces, raw buffers, or
location metadata unless a future privacy review explicitly allows it.

## Maintenance Discipline

Before major releases:

- Review Android behavior changes and Play target-SDK requirements.
- Review CameraX, TensorFlow Lite, MediaPipe, OpenCV, NDK, and Gradle release
  notes before dependency upgrades.
- Run device smoke tests on at least one Pixel, one Samsung, and one lower-memory
  Android phone.
- Run emulator checks only for basic UI/build sanity, not camera quality signoff.
- Use staged Play rollout for production updates.

Any device-specific fix should go into a dedicated compatibility/quirk layer,
not into scattered capture or processing branches.

## Release Checklist

- Compatibility policy tests pass.
- Build, unit tests, and lint pass.
- Camera preview opens on the device test matrix.
- Capture saves to the gallery through MediaStore.
- Fallback path produces the ProShot Natural output instead of a generic capture.
- Any device-specific issue has a clear quirk entry or follow-up task.
