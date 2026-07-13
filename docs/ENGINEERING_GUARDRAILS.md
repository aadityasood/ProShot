# ProShot Engineering Guardrails

## Status Vocabulary

- `IMPLEMENTED`: present in the current runtime feature path.
- `DECLARED/CONFIGURED`: present in build or native configuration but not used
  by the current runtime feature path.
- `PLANNED`: approved future engineering work with no current implementation.
- `RESEARCH`: exploratory direction that is not committed product behavior.

Engineering proposals must not describe `DECLARED/CONFIGURED`, `PLANNED`, or
`RESEARCH` work as shipping behavior.

## Current Processing Boundary

ProShot Natural v0 is `IMPLEMENTED` as two global operations on vendor-processed
NV21:

- A luma tone-curve LUT using piecewise-linear interpolation.
- A chroma-saturation LUT.

`LookProfile` also contains warmth, skin, face, and regional fields, but current
processing does not consume them. The presence of a contract field is not proof
that its transform is implemented.

## Look Profile Extensibility

The public processing profile is ProShot Natural. Core stages should depend on
neutral contracts rather than product-positioning comparisons or device-family
checks.

- New profile data belongs in the profile catalog.
- Capture and processing algorithms must not contain scattered model checks.
- A new field must not be presented as active until an executed stage consumes
  it and tests its behavior.
- Unresolved units, including warmth values, must be defined before processing
  consumes them.

## Global and Local Processing Rules

- Global tone and color transforms may operate on the full frame.
- Localized, semantic, subject-specific, or region-selective enhancement must
  use a soft mask with values in `[0.0, 1.0]`.
- Local masks must use feathered edges appropriate to the operation.
- Every masked operation must preserve a deterministic unmasked fallback.
- Missing or low-confidence masks must not cause a destructive full-frame local
  adjustment.

Semantic masks and localized enhancement are `PLANNED`; these rules become
runtime requirements when those features are implemented.

## Capture and Compatibility Rules

The sole `IMPLEMENTED` capture route is one Camera2 `YUV_420_888` frame followed
by copied-plane conversion, orientation, global LUT processing, JPEG
compression, and MediaStore save.

`CompatibilityPolicy` is an `IMPLEMENTED` capability classifier, not an
execution router. Tier-specific dispatch is `PLANNED`.

If temporal, burst, RAW, or tier-specific routes are implemented later:

- Preserve the selected ProShot-owned visual target when a valid image buffer
  exists.
- Define the fallback order and failure behavior before merge.
- Do not claim that camera-unavailable or no-buffer states can produce a photo.
- Keep device workarounds in a compatibility or quirk layer.
- Require timestamp correlation and bounded ownership for multi-frame buffers.
- Keep frame selection deterministic and testable.

## Native and GPU Rules

The native `proshot` JNI proof is `DECLARED/CONFIGURED`; it is not image
processing. OpenCV integration is `PLANNED` and must not be described as linked
or available until build configuration and runtime code prove it.

Future GPU work must identify the actual API:

- OpenGL ES compute work may use compute shaders on supported devices.
- AGSL provides runtime shader evaluation and must not be described as a
  compute-shader API.
- CPU fallback must remain deterministic and safe.
- GPU use must not be claimed to eliminate all CPU-side frame, JPEG, or output
  allocations.

## App Size Policy

ProShot should remain a normal-sized camera app as features are added.

- Publish production builds as Android App Bundles when release distribution is
  established.
- Keep native ABI output limited to supported targets.
- Add ML models only with a documented install-size and runtime-memory impact.
- Prefer appropriately optimized models when quality remains acceptable.
- Do not bundle unused model packs, calibration data, or profile assets.
- Review declared-unused dependencies before release rather than assuming they
  are harmless.

## Privacy and Photo Safety

Captures are processed locally by default. Normal JPEG output is published
through MediaStore. API 29+ uses `MediaStore.Images.Media.RELATIVE_PATH` and
publishes app-owned output under `Pictures/ProShot`. API 26-28 uses the default
shared MediaStore location and the legacy write permission; that output is not
app-owned in the scoped-storage sense. After publication, Android, gallery apps,
device security, and user cloud-backup settings govern access and copies.

Current permissions are `CAMERA` plus legacy `WRITE_EXTERNAL_STORAGE` through
API 28. There is no gallery-read, location, or `INTERNET` permission.

Privacy guardrails:

- Do not add `INTERNET` without explicit user value, consent, and a privacy
  impact review.
- Do not request gallery-read permission without an implemented user feature
  that requires it.
- Do not upload photos or run image-content analytics by default.
- Do not log raw image buffers, JPEG bytes, gallery paths, EXIF dumps, faces,
  or other photo-derived payloads.
- Review backup behavior, metadata collection, metadata retention, and privacy
  policy readiness before release.
- Use local-first, permission-minimal language without promising absolute
  security.

Any new permission, network feature, analytics SDK, crash reporting SDK,
backup behavior, sharing flow, or cloud feature requires a privacy impact note.

## Machine Learning and AI Policy

TensorFlow Lite and MediaPipe dependencies are `DECLARED/CONFIGURED`; no runtime
model loading or inference is implemented.

Optional advisory AI is `RESEARCH`. If approved later, it must be:

- Advisory rather than the only capture decision path.
- Versioned and confidence-aware.
- Bounded in inputs and outputs.
- Optional for the user where appropriate.
- Backed by a deterministic non-ML fallback.
- Local by default unless a separately approved feature provides clear consent
  and privacy controls.

## Evidence Before Merge

Changes that add capture routes, native code, ML, permissions, storage behavior,
or new output formats must identify:

- The executed source path.
- Supported and unsupported device behavior.
- Failure and fallback handling.
- Memory and install-size implications.
- Unit, integration, instrumentation, and device evidence appropriate to risk.
- Release-build behavior rather than debug-only behavior alone.
