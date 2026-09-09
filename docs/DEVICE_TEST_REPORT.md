# Device test status

Date: 2026-09-09.

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
