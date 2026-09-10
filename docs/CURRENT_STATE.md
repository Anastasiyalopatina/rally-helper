# Current state

Date: 2026-09-10. Canonical status for Phase D0.

## Product modes

- `RADAR`: **ACTIVE**. Confirmed targets emit one local sound/vibration alert per `RallyId`. Alert policy is independent from action safety, so unknown participant OCR may still alert when the visual anchors are strong.
- `ONE_TAP_A`: **ACTIVE / EXPERIMENTAL**. The overlay is automatic in this mode. A user tap starts one fresh-frame validation of the displayed `RallyId`, then may press the deterministic leftmost valid green plus inside that same current card. Success requires a later confirmed march screen.
- `ONE_TAP_B`: **NOT_IMPLEMENTED**. Send remains manual.
- `AUTO`: **LOCKED**. No automatic join/send path exists.

## Input safety

- `RefreshMode` is `OFF`, `ALERT_ONLY`, or `AUTO_REFRESH`; the default is `OFF`.
- Runtime gestures are purpose-scoped as `REFRESH` or `JOIN_PLUS`. `SEND` is defined but rejected by the Accessibility gate.
- Every `GestureRequest` contains its purpose, normalized point, source frame, optional `RallyId`, creation/expiry times, expected package and expected screen. The maximum request TTL is 750 ms.
- The Accessibility service listens only for foreground window-state changes, cannot retrieve window content, and rejects requests unless the locally verified target package is foreground.
- ONE_TAP never reuses displayed geometry. It waits for a newer analyzed frame and resolves the same `RallyId`; disappearance or replacement produces no gesture.
- A global gesture coordinator permits only one in-flight gesture. A user join suppresses refresh until the join flow reaches a terminal state.
- Android gesture completion is not success. Refresh requires a later list change; ONE_TAP_A requires a later confirmed march screen within two seconds.

## Verification

- `:vision-core:test` contains 14 ONE_TAP_A unit cases covering valid dispatch, identity replacement, negative target states, foreground/expiry guards, double tap, projection cancellation and post-gesture visual verification.
- `:vision-core:verifyCoreLogic` retains tracker, alert/action split, refresh and state-machine invariants.
- Device installation and bounded supervised results are recorded only after running the APK built from the final commit SHA.
