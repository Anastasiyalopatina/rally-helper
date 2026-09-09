# Architecture

The project has two modules:

- `vision-core`: offline screen classification, card analysis, temporal tracking and fail-closed decisions.
- `app`: MediaProjection capture, DataStore settings, Room event storage, local alerts and session UI.

Runtime observation flow:

`MediaProjection → rate limiter → latest-frame queue → reusable pixel buffer → overlay mask → detector → RallyTracker → SafetyController → alert/policy log`

`RadarSettingsStore` is the live source of truth for mode, levels, alerts, overlay, diagnostics, delay/skip policy, free-slot threshold and safety margin. The service observes the flow while capture is active.

The ordinary product surface exposes `RADAR`, `ONE_TAP` and `AUTO`; `SHADOW_AUTO` is isolated under developer settings. `AutoPolicy` owns selection timing/skip decisions but has no input API. `AutomationStateMachine` owns guarded flow transitions. No accessibility service or automated input is present in the current build, so `ONE_TAP` and `AUTO` remain release-gated.

The optional compact overlay is a touchable `TYPE_APPLICATION_OVERLAY`, constrained to a safe left strip. Its known bounds are masked before CV analysis. RADAR continues normally when overlay permission or the setting is absent.

Calibration screenshots are local-only. The APK contains a compact, versioned feature asset and an automated build assertion rejects raw image assets.
