# Architecture

The project has two modules:

- `vision-core`: offline screen classification, card analysis, temporal tracking and fail-closed decisions.
- `app`: MediaProjection capture, DataStore settings, Room event storage, local alerts and session UI.

Runtime observation flow:

`MediaProjection → rate limiter → latest-frame queue → reusable pixel buffer → overlay mask → detector → RallyTracker → alert/action policies → Shadow coordinator → transition log`

`RadarSettingsStore` is loaded synchronously before a capture session starts and remains the live source of truth while capture is active.

The ordinary product surface exposes `RADAR`, `ONE_TAP` and `AUTO`; `SHADOW_AUTO` is isolated under developer settings. The Shadow coordinator owns deterministic single-target selection, monotonic delay, fresh-frame revalidation and policy completion. It has no input API. Radar alerts may accept high-confidence evidence when participant OCR is unknown, while the action policy always remains strict. No accessibility service or automated input is present in the current build, so `ONE_TAP` and `AUTO` remain release-gated.

PAUSE cancels pending automation and requires a fresh frame after RESUME; it does not stop projection, Radar analysis or alerts. STOP ends the service. Incompatible geometry enters an explicit `NEEDS_CALIBRATION` lifecycle state.

Pending cancellation (disappearance, pause or leaving an auto mode) clears only that rally's sampled delay and is not a completed attempt; the current skip K is retained. A delay/skip settings change cancels pending work and deliberately starts a new policy cycle under the new ranges. A new MediaProjection session resets all policy state. Entering AUTO/SHADOW after another mode requires a fresh analyzed frame.

Room v3 stores session aggregates and meaningful state transitions. Capture Lab defaults to OFF; only ARMED mode creates downscaled JPEG frames for its five-second ring. Labelled ZIP/JSON archives stay in private app storage until the user explicitly exports them through Android's system file picker.

The optional compact overlay is a touchable `TYPE_APPLICATION_OVERLAY`, constrained to a safe left strip. Its known bounds are masked before CV analysis. RADAR continues normally when overlay permission or the setting is absent.

Calibration screenshots are local-only. The APK contains a compact, versioned feature asset and an automated build assertion rejects raw image assets.
