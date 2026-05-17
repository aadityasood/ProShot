# ProShot

ProShot is an Android computational photography app focused on consistent,
natural image processing across a wide range of Android devices.

The project is in early development. The current foundation includes:

- Kotlin Android app scaffold with Jetpack Compose and Hilt.
- Camera compatibility policy for choosing processing tiers by device support.
- A neutral `LookProfile` system with the first bundled profile, ProShot Natural.
- Engineering guardrails for compatibility, app size, and future look profiles.

## Goals

- Preserve a consistent visual target across capable and limited devices.
- Degrade execution method before changing the selected look.
- Keep profile tuning separate from core capture and processing algorithms.
- Leave room for future natural, clear, vivid, or device-family-inspired looks
  without rewriting the pipeline.

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

## Project Status

This repository currently contains the app scaffold, compatibility policy,
look-profile foundation, and engineering documentation. Full camera capture,
processing, model integration, and gallery output are planned follow-up work.

## License

No open-source license has been selected yet. Public visibility does not grant
reuse, distribution, or modification rights beyond what GitHub permits for
viewing the repository.
