# ProShot Engineering Guardrails

## Look Profile Extensibility

The first product iteration ships with one public processing profile:
ProShot Natural. The pipeline should still treat that look as configuration,
not as a permanent hard-coded assumption.

Core stages should depend on neutral data contracts:

- `LookProfile` describes the selected post-processing look.
- `ToneCurvePoint` stores normalized tone curve control points.
- `RegionTuning` stores semantic-region adjustments.
- `SemanticRegion` identifies regions produced by semantic analysis.

Future look profiles should add new profile data to the catalog and reuse the
same capture, merging, semantic analysis, enhancement, color science, and output
stages. Device-family-inspired tuning should stay outside core algorithms unless
a future profile proves that a new algorithmic stage is required.

## Compatibility Without Losing The Look

Fallback changes the execution method, not the visual goal. Every valid captured
frame should still route through the selected `LookProfile`; version 1 uses
ProShot Natural for every supported device tier.

Compatibility rules:

- Prefer RAW burst when available, then YUV burst, then single-frame enhanced
  processing, then basic capture only as an emergency path.
- Do not downgrade limited devices into a generic Android-camera look.
- Keep tone curve, warmth, skin hue lock, face luminance, highlight rolloff, and
  regional tuning behavior consistent across fallback tiers where the required
  inputs exist.
- Put device-specific workarounds in a compatibility or quirk layer instead of
  scattering model checks through capture and processing code.
- Any feature that adds camera, ML, native, or storage assumptions must state
  its fallback behavior before merge.

## App Size Policy

ProShot should stay a normal-sized camera app, even as image quality improves.
Camera and ML features make it larger than a simple utility app, but size should
remain controlled by default.

Size guardrails:

- Publish release builds as Android App Bundles so Play can deliver device
  specific splits.
- Keep native ABI output focused on supported ARM targets unless emulator or
  desktop testing needs a temporary debug-only exception.
- Prefer quantized or otherwise optimized TensorFlow Lite models when visual
  quality remains acceptable.
- Do not bundle extra phone-style models, lookup tables, or calibration packs
  until those profiles actually ship.
- Consider optional on-demand model downloads only after bundled ML assets become
  the dominant APK or install-size cost.

Any task that adds ML models, native libraries, or profile assets should report
the expected install-size impact before merge.

## Privacy And Photo Safety

ProShot should protect user photos by design, while staying honest about the
limits of app-level security. Captures are processed locally by default, but
once a photo is saved to the device gallery, access is governed by Android, the
gallery app, user-granted permissions, device security, and any user-enabled
cloud backup.

Privacy guardrails:

- Do not add the `INTERNET` permission unless a future feature has explicit
  user value, consent, and a privacy impact note.
- Do not request gallery read permissions unless a real gallery browsing,
  import, or before/after review feature needs them.
- Do not upload images, process images in the cloud, or run image-content
  analytics by default.
- Do not log raw image buffers, JPEG bytes, gallery paths, EXIF dumps, or other
  photo-derived payloads.
- Keep beginner-facing privacy language clear: local-first and
  permission-minimal, without promising absolute security.

Any new permission, network feature, analytics SDK, crash-reporting SDK,
backup behavior, sharing/export flow, or cloud feature must include a privacy
impact note before merge.
