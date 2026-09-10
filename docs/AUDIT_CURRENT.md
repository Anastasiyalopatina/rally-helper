# Current code audit

> Historical pre-D0 audit. See `docs/CURRENT_STATE.md` for the implemented experimental ONE_TAP_A runtime.

Audited scope: the tree currently checked out from `main` plus the product-mode preparation changes in this branch.

## Implemented

- live DataStore source of truth for all exposed settings;
- three ordinary modes, with Shadow Auto isolated under developer settings;
- current-frame actionability (`presentInCurrentFrame` and exact `lastSeenFrameId`);
- temporal identity using geometry, countdown, participants, appearance and current-frame presence;
- plus-required joinability and the fixed rule that at least one free slot is sufficient;
- independent sound and vibration settings with a local SoundPool cue;
- optional fully draggable overlay with an independently clickable One Tap button, explicit counters and CV-conflict fail-closed handling;
- functional single-flight Shadow coordinator with deterministic priority, monotonic delay and fresh-frame revalidation;
- independent Radar alert policy and strict action safety policy;
- automation PAUSE/RESUME separate from service STOP;
- explicit `NEEDS_CALIBRATION` lifecycle state and per-session runtime reset;
- Material 3 range controls shown only for AUTO/SHADOW, with delay constrained to 0–30 seconds;
- local current/history session UI and Room v4 meaningful-transition records with separate simulated and actual counters;
- private bounded Capture Lab ring buffer with three-second pre/post `MARK SCENARIO` ZIP/JSON export;
- Capture Lab OFF/ARMED gating, TUNING/HOLDOUT splits, reproducibility metadata, a live-validation dashboard and Storage Access Framework export;
- Room migration regressions for 1→2, 2→3, 1→3, 3→4 and 1→4, executed on a physical device;
- local single-frame inspection tooling that reports detector output without copying the source image into build outputs;
- debug-capture-only retention with defaults `FAILURES` and three days;
- compact runtime templates, no raw calibration images in the APK, and reusable capture buffers;
- Room sessions, observations, decisions, aborts, transition and policy metadata;
- foreground notification STOP plus automation PAUSE/RESUME.
- combined lattice/free-scan card detection with score-first NMS and stale RallyId request tests;
- unlimited travel-time policy guarded by known countdown and an explicit safety margin.
- guarded event-list refresh detection with one request per visible appearance, bounded stuck-control retry and a low-level Accessibility gesture bridge;
- stable title/location visual fingerprints and non-expired-track matching to prevent repeated counts across intermittent frame misses or card movement.

## Intentionally gated

- the AccessibilityService can dispatch only a refresh gesture requested by the guarded event-list coordinator;
- ONE_TAP join requests fail closed with a validation-required reason;
- AUTO can evaluate policy but cannot start JoinFlow;
- squad selection and travel-time acceptance cannot act on unknown evidence.

## Blocking evidence gaps

- independent holdout and real multi-frame sequences;
- live already-joined evidence;
- labelled squad-state samples;
- labelled travel-time samples;
- functional RADAR scenario coverage, 100 shadow decisions and final-release endurance;
- a fresh physical functional matrix after the Phase C runtime changes.
- the Phase C2 physical matrix beyond the validated empty-event scenario (1/13 complete).
