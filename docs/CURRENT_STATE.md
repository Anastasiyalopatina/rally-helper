# Current state

Date: 2026-09-10. Canonical status for Phase C4.

## Runtime

- `RADAR` remains the safe default product mode; join and send actions are locked.
- Refresh is independent and defaults to `OFF`.
- `OFF` performs diagnostics only, `ALERT_ONLY` emits one local signal per appearance, and `AUTO_REFRESH` is the only mode allowed to request a refresh gesture.
- A refresh request is purpose-scoped, expires after 750 ms, requires the locally verified foreground package and a `TYPE_WINDOW_STATE_CHANGED` observation no older than 30 seconds, and is rejected outside a confirmed event-list screen.
- Android gesture completion is not counted as success. Success requires a later frame where the control disappeared or the event-list fingerprint changed.
- One initial request plus one retry is the maximum for one appearance. A persistent control produces `REFRESH_STUCK` and pauses refresh for that appearance.
- The Accessibility service cannot retrieve window content and has no join or send path.

## Validation infrastructure

- `./gradlew :vision-core:verifyCoreLogic` covers refresh modes, wrong/stale package state, expiry, cancellation, in-flight rejection, visual verification and the two-request bound.
- `./gradlew :vision-core:replayCapturedScenarios -PcaptureBundle=/absolute/path/to/export.zip` replays an exported bundle or one scenario ZIP using recorded monotonic timestamps and no real sleeps.
- Replay requires `expected-scenario.json`. Detector-triggered evidence without manually confirmed ground truth is reported as `NOT_OBSERVED`, never `PASS`.
- `./gradlew :vision-core:verifyReplayInfrastructure` exercises the complete replay transport with generated neutral frames in CI. It does not claim detector accuracy.
- Guided Validation exposes bounded 45-second cases R1–R8, M1, S1–S3 and T1–T5. A missing state ends as `NOT_OBSERVED`.
- The developer-only Evidence Collector defaults to `OFF` and deduplicates interesting rally/state transitions rather than saving every frame.
- Dataset progress is reported by unique scenario and identity counts, not frame totals.

## Readiness gate

The critical ONE_TAP_A gate is R1–R8 with at least one confirmed real sequence per case, multiple positive instances when available, deterministic replay PASS, and zero false-actionable cases on independent holdout. Roughly 20–30 unique real rally instances are desired for a full assessment. A 100-decision Shadow quota, returning-squad support, and a 60-minute run are not prerequisites for staged ONE_TAP_A.

Current status: **WAITING FOR CONFIRMED ORDINARY-SESSION EVIDENCE**. This is a data-availability state, not a request to keep a live test open. Each guided attempt ends after 45 seconds.

The Phase C4 physical-device smoke and embedded five-minute performance sample passed with zero dropped frames and zero refresh gestures in `OFF`. No suitable live refresh appearance or critical real-case sequence was observed during that bounded window, so those items remain `NOT_OBSERVED` rather than being inferred from older captures.

The replay runner was also pointed at 28 legacy private Capture Lab archives. All 28 were correctly classified as `NOT_OBSERVED` because those older ZIPs do not contain the new separate, confirmed `expected-scenario.json`; none was allowed to become a replay pass. The temporary desktop copy was deleted after this check.
