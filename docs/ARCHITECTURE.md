# Architecture

The project has two modules:

- `vision-core`: offline screen classification, card analysis, temporal tracking and fail-closed decisions.
- `app`: MediaProjection capture, DataStore settings, Room event storage, local alerts and session UI.

Runtime flow:

`MediaProjection → latest-frame queue → reusable pixel buffer → detector → RallyTracker → SafetyController → alert/shadow log`

The service observes a single `RadarSettingsStore` flow. Both runtime mode and target levels can change while capture is active. No accessibility service, synthetic gestures, overlay, network permission, or automated input is present.

Calibration screenshots are local-only. The APK contains a compact, versioned feature asset and an automated build assertion rejects raw image assets.
