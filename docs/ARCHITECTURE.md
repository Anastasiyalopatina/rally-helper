# Architecture

The project has two modules:

- `vision-core`: offline screen classification, card analysis, temporal tracking and fail-closed decisions.
- `app`: MediaProjection capture, DataStore settings, Room event storage, local alerts and session UI.

Runtime observation flow:

`MediaProjection → rate limiter → latest-frame queue → reusable pixel buffer → overlay mask → detector → RallyTracker → alert/action policies → Shadow coordinator → transition log`

`RadarSettingsStore` is loaded synchronously before a capture session starts and remains the live source of truth while capture is active.

The ordinary product surface exposes `RADAR`, `ONE_TAP` and `AUTO`; `SHADOW_AUTO` is isolated under developer settings. The Shadow coordinator owns deterministic single-target selection, monotonic delay, fresh-frame revalidation and policy completion. It has no input API. Radar alerts may accept high-confidence evidence when participant OCR is unknown, while the action policy always remains strict. A low-level Accessibility bridge is limited to the detected refresh control on a high-confidence event-list screen; it contains no selection, join or send logic. `ONE_TAP` and `AUTO` join/send actions remain release-gated.

Refresh requests are single-flight per visible appearance. The coordinator rearms after two consecutive absent frames, retries a stuck control only after a bounded timeout, and does not rearm on one uncertain frame.

PAUSE cancels pending automation and requires a fresh frame after RESUME; it does not stop projection, Radar analysis or alerts. STOP ends the service. Incompatible geometry enters an explicit `NEEDS_CALIBRATION` lifecycle state.

Pending cancellation (disappearance, pause or leaving an auto mode) clears only that rally's sampled delay and is not a completed attempt; the current skip K is retained. A delay/skip settings change cancels pending work and deliberately starts a new policy cycle under the new ranges. A new MediaProjection session resets all policy state. Entering AUTO/SHADOW after another mode requires a fresh analyzed frame.

Room v4 stores session aggregates and meaningful state transitions. Shadow `would-attempt` counters are separate from real attempts, successes and failures. Capture Lab defaults to OFF; only ARMED mode creates downscaled JPEG frames in a bounded ring. `MARK SCENARIO` writes an approximately three-second pre-roll plus three-second post-roll with a TUNING/HOLDOUT split, build/runtime metadata, geometry, confidences, tracks and decisions. Labelled ZIP/JSON archives stay in private app storage until the user explicitly exports them through Android's system file picker.

The optional compact overlay is a touchable `TYPE_APPLICATION_OVERLAY`. It can be dragged by its heading across the display while buttons remain independently clickable. Its default placement is below the populated event-card region, its known bounds are masked before CV analysis, and analysis fails closed with a placement warning if it overlaps a critical CV region. RADAR continues normally when overlay permission or the setting is absent.

Card discovery always combines calibrated-lattice and scroll-tolerant free-scan candidates, then applies score-first non-maximum suppression and structural-evidence filtering. A visible lattice card can therefore coexist with a shifted card without producing duplicate tracks. Temporal identity includes stable visual fingerprints from the title and location regions, so a still-visible event is not counted again after an intermittent missed frame or card movement.

Travel time has no arbitrary maximum. Sending remains fail-closed unless both travel and remaining time are known and `travel + safety margin < remaining`. Capacity is fixed to the product rule that one available place is sufficient.

Calibration screenshots are local-only. The APK contains a compact, versioned feature asset and an automated build assertion rejects raw image assets.
