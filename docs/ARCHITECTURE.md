# Architecture

The project has three modules:

- `vision-core`: offline screen classification, card analysis, temporal tracking and fail-closed decisions.
- `app`: MediaProjection capture, DataStore settings, Room event storage, local alerts and session UI.
- `test-target`: a deterministic debug-only Android target used to verify real Accessibility gestures; it is never packaged into the production app.

Runtime observation flow:

`MediaProjection → rate limiter → latest-frame queue → reusable pixel buffer → overlay mask → detector → RallyTracker → alert/action policies → Shadow coordinator → transition log`

`RadarSettingsStore` is loaded synchronously before a capture session starts and remains the live source of truth while capture is active.

The ordinary product surface exposes `RADAR`, experimental `ONE_TAP` and locked `AUTO`; `SHADOW_AUTO` is isolated under developer settings. Refresh is an independent `OFF` / `ALERT_ONLY` / `AUTO_REFRESH` setting and defaults to `OFF`. ONE TAP uses a strict fresh-frame `RallyId` open policy followed by state-driven `SELECT_SQUAD` and `SEND` stages. The low-level Accessibility bridge accepts only unexpired purpose-scoped gestures for the locally verified foreground package and expected source screen. Foreground-window, source-frame, flow and MediaProjection generations bind each request to the validated context. It cannot retrieve window content. `AUTO` remains disabled.

Refresh requests are single-flight per visible appearance. The coordinator rearms after two consecutive absent frames, retries a stuck control only after a bounded timeout, and does not rearm on one uncertain frame.

PAUSE cancels pending automation and requires a fresh frame after RESUME; it does not stop projection, Radar analysis or alerts. Capture visibility loss, projection termination and incompatible geometry cancel every pending action. STOP ends the service. Incompatible geometry enters an explicit `NEEDS_CALIBRATION` lifecycle state.

Pending cancellation (disappearance, pause or leaving an auto mode) clears only that rally's sampled delay and is not a completed attempt; the current skip K is retained. A delay/skip settings change cancels pending work and deliberately starts a new policy cycle under the new ranges. A new MediaProjection session resets all policy state. Entering AUTO/SHADOW after another mode requires a fresh analyzed frame.

Room v6 stores session aggregates and meaningful state transitions, with separate open, squad-selection, send-verification, and end-to-end join counters. Shadow `would-attempt` counters remain separate. Capture Lab defaults to OFF; only ARMED, bounded Guided Validation, or an explicitly enabled developer Evidence Collector creates downscaled JPEG frames in a bounded ring. ZIP archives stay in private app storage until explicit export.

The compact overlay is a touchable `TYPE_APPLICATION_OVERLAY`. It can be dragged across the display while buttons remain independently clickable. Its known bounds are masked before CV analysis, so obscured evidence fails closed without moving the panel away from the user. Closing invalidates queued updates, removal is immediate, and only one controller may own a window in the process.

Card discovery always combines calibrated-lattice and scroll-tolerant free-scan candidates, then applies score-first non-maximum suppression and structural-evidence filtering. A visible lattice card can therefore coexist with a shifted card without producing duplicate tracks. Temporal identity includes stable visual fingerprints from the title and location regions, so a still-visible event is not counted again after an intermittent missed frame or card movement.

Travel time has no arbitrary maximum. Sending remains fail-closed unless both travel and remaining time are known and `travel + safety margin < remaining`. Capacity is fixed to the product rule that one available place is sufficient.

Calibration screenshots are local-only. The APK contains a compact, versioned feature asset and an automated build assertion rejects raw image assets.
