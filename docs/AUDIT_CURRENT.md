# Current code audit

Audited scope: the tree currently checked out from `main` plus the product-mode preparation changes in this branch.

## Implemented

- live DataStore source of truth for all exposed settings;
- three ordinary modes, with Shadow Auto isolated under developer settings;
- current-frame actionability (`presentInCurrentFrame` and exact `lastSeenFrameId`);
- temporal identity using geometry, countdown, participants, appearance and current-frame presence;
- plus-required joinability and configurable free-slot threshold;
- independent sound and vibration settings with a local SoundPool cue;
- optional draggable overlay whose bounds are excluded from CV;
- functional single-flight Shadow coordinator with deterministic priority, monotonic delay and fresh-frame revalidation;
- independent Radar alert policy and strict action safety policy;
- automation PAUSE/RESUME separate from service STOP;
- explicit `NEEDS_CALIBRATION` lifecycle state and per-session runtime reset;
- Material 3 range controls shown only for AUTO/SHADOW;
- local current/history session UI and Room v3 meaningful-transition records;
- private five-second Capture Lab ring buffer with labelled ZIP/JSON export;
- Capture Lab OFF/ARMED gating, extended ground-truth labels and Storage Access Framework export;
- Room migration regressions for 1→2, 2→3 and 1→3, executed on a physical device;
- local single-frame inspection tooling that reports detector output without copying the source image into build outputs;
- debug-capture-only retention with defaults `FAILURES` and three days;
- compact runtime templates, no raw calibration images in the APK, and reusable capture buffers;
- Room sessions, observations, decisions, aborts, transition and policy metadata;
- foreground notification STOP plus automation PAUSE/RESUME.

## Intentionally gated

- no AccessibilityService is registered;
- no component can dispatch a gesture;
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
