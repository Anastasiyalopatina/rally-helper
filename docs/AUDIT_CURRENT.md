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
- functional delay/skip policy with per-rally delay sampling;
- debug-capture-only retention with defaults `FAILURES` and three days;
- compact runtime templates, no raw calibration images in the APK, and reusable capture buffers;
- Room sessions, observations, decisions, aborts and policy metadata;
- foreground notification STOP and overlay PAUSE.

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
- a fresh physical smoke after the last pure policy-only change.
