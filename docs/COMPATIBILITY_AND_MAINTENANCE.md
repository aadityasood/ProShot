# ProShot Compatibility and Maintenance

## Status Vocabulary

- `IMPLEMENTED`: present in the current runtime feature path.
- `DECLARED/CONFIGURED`: present in build or native configuration but not used
  by the current runtime feature path.
- `PLANNED`: approved future engineering work with no current implementation.
- `RESEARCH`: exploratory direction that is not committed product behavior.

## Product Principle

Supported devices should aim for the same ProShot-owned visual target. A
fallback may change execution method when a valid image buffer exists, but it
must not silently claim that an unavailable camera or missing buffer produced a
photo.

## Current Execution Truth

The only executed capture route is `IMPLEMENTED`:

```text
one Camera2 YUV_420_888 frame
  -> copied planes
  -> NV21 conversion and rotation
  -> global luma tone-curve LUT
  -> global chroma-saturation LUT
  -> JPEG
  -> MediaStore
```

Warmth, skin, face, semantic, regional, burst, RAW, native merge, and high-bit
processing are not part of this route.

## Compatibility Classifications

`CompatibilityPolicy` is an `IMPLEMENTED` classifier. It consumes normalized
hardware level, RAW/YUV/manual/burst flags, semantic and GPU capability flags,
camera availability, process memory class, and low-RAM status. It returns a
decision containing a tier, reasons, expected frame count, acceleration
preference, semantic-mask flag, and look profile.

The Compose screen currently uses the decision's look profile and renders parts
of the decision in the debug HUD. `CaptureCoordinator` does not dispatch on the
tier and always calls the single-frame controller.

| Policy classification | Classification status | Tier-specific route status | Current meaning |
|:---|:---|:---|:---|
| `FULL_COMPUTATIONAL` | `IMPLEMENTED` | `PLANNED` | Capability classification for a future full route |
| `YUV_BURST` | `IMPLEMENTED` | `PLANNED` | Capability classification for a future YUV burst route |
| `SINGLE_FRAME_ENHANCED` | `IMPLEMENTED` | `PLANNED` | Capability classification; it does not select a separate runtime route today |
| `BASIC_CAPTURE` | `IMPLEMENTED` | `PLANNED` | No-capture capability classification; camera-unavailable or no-buffer states do not produce a photo today |

A separate compatibility router, tier-specific capture dispatch, and validated
fallback execution are `PLANNED`.

## Implemented Checks and Planned Strategies

Implemented checks and seams:

- Camera2 hardware-level mapping.
- RAW, YUV, manual exposure, and burst capability inputs.
- AF/AE region support and active-array checks in capture targeting.
- Camera availability, process memory class, and low-RAM inputs.
- GPU and semantic capability indicators used by policy and debug display.
- Fixed-focus and unsupported focus-region fallbacks in the current capture
  controller.

Planned strategies:

- RAW or YUV burst routing.
- Persistent sessions and bounded pre-shutter history.
- Frame selection, alignment, merge, and ghost rejection.
- Device quirk routing and OEM extension routing.
- Semantic-mask and GPU execution.
- A basic valid-buffer fallback route.
- High-bit, HEIF, or Ultra HDR output.

### Processing Timeout

The documented processing-timeout strategy is `PLANNED`. There is no current
alignment or merge stage to skip. If temporal processing is added, timeout
handling belongs in its orchestrator and must preserve a valid deterministic
single-frame result when one is available.

## Current Debug Surface

The post-capture debug HUD is `IMPLEMENTED`. It displays:

- Selected policy tier and look profile.
- Camera hardware level.
- GPU and semantic-mask capability indicators.
- Optional captured timing diagnostics.
- Optional captured focus and lens diagnostics.

The timing and focus strings are snapshots displayed after a photo, not live
telemetry. They do not form a complete compatibility report.

A comprehensive report containing app and SDK versions, device and ABI,
memory, complete support flags, fallback reasons, and save outcome is `PLANNED`.
Any such report must exclude image content, location, raw buffers, and other
photo-derived payloads.

## Compatibility Risks

Android camera behavior can change with:

- Android platform and target-SDK requirements.
- Camera2 hardware level and HAL implementation.
- Firmware and device updates.
- Camera availability and lifecycle interruption.
- Storage and MediaStore policy.
- CameraX, NDK, ML, Compose, Gradle, and plugin upgrades.
- Memory pressure during frame copies, processing, and JPEG output.

Current CameraX dependencies use catalog version `1.4.0-alpha05`. Migration to
a stable release is `PLANNED` and requires controlled source, test, and device
validation.

## Permission and Output Baseline

The manifest declares:

- `CAMERA`.
- `WRITE_EXTERNAL_STORAGE` only through API 28.

It does not declare gallery-read, location, or `INTERNET` permission. Normal
output is saved through MediaStore. API 29+ uses
`MediaStore.Images.Media.RELATIVE_PATH` and publishes app-owned output under
`Pictures/ProShot`. API 26-28 uses the default shared MediaStore location and
the legacy write permission; that output is not app-owned in the scoped-storage
sense. After publication, Android, gallery apps, and user cloud-backup settings
govern access and copies.

## Maintenance Discipline

Before a major release:

- Review Android behavior and target-SDK changes.
- Review dependency release notes before removal, activation, or upgrades.
- Remove or justify runtime-unused dependencies.
- Run unit, integration, instrumentation, and representative device tests.
- Exercise preview unbind, Camera2 capture, and preview rebind under
  cancellation, interruption, and memory pressure.
- Use emulator runs for UI and build sanity, not camera-quality signoff.
- Validate release builds separately from debug builds.
- Keep device-specific fixes in a compatibility or quirk layer.

## Pre-Release Watch Items

- Review `android:allowBackup="true"` and define the intended backup policy.
- Confirm privacy-policy readiness for shipped permissions and behavior.
- Define image metadata controls and minimization before adding metadata fields.
- Clean up or justify declared-unused dependencies.
- Expand instrumentation and representative device-matrix coverage.
- Verify release-build permissions, shrinking, logging, capture, and save
  behavior.
- Confirm normal and debug paired output behavior without relying on unmeasured
  memory assumptions.

## Release Evidence Checklist

- Build, unit tests, and lint pass before release.
- Release build opens preview and saves a photo on the device matrix.
- Current single-frame processing matches its documented luma and chroma
  behavior.
- MediaStore output appears under the expected collection and path.
- No planned tier is described as an executed route without source evidence.
- Any new permission, dependency, route, or device workaround has an owner,
  fallback, and evidence plan.
