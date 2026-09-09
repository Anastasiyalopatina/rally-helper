# Device test status

Date: 2026-09-10.

## Intermediate candidate

- bounded MediaProjection smoke: passed;
- continuous RADAR stability run beyond 30 minutes: passed for performance stability;
- process and foreground service remained stable;
- no latest-frame queue drops were observed;
- memory returned after collection and showed no monotonic growth;
- no crash, ANR or OOM was observed after a clean launch;
- overlay permission and touchable overlay lifecycle: passed.

This was an intermediate engineering check, not a final release qualification. Exact device identifiers and raw device measurements are intentionally not stored in this public repository.

## Remaining gates

| Gate | Status |
|---|---|
| Functional RADAR scenario matrix | INCOMPLETE |
| At least 100 Shadow decisions | NOT_RUN |
| Final-release 60-minute endurance | DEFERRED |
| ONE_TAP live sequences | BLOCKED by earlier gates and missing evidence |
| AUTO live runs | BLOCKED by earlier gates |

The final 60-minute run is intentionally deferred until a final release candidate. Phase C changes require a new short functional smoke and labelled scenario matrix before either input mode can be considered.

## Phase C2 preparation

- Room migrations 1→2, 2→3 and 1→3 preserve legacy session and observation records: PASS on a physical device.
- Phase C2 APK compilation and installation-test path: PASS.
- Functional 10-minute MediaProjection smoke for the eventual C2 commit: NOT_RUN.
- Capture Lab OFF/ARMED latency comparison: NOT_RUN.
