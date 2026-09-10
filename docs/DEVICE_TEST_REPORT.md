# Device test status

> Historical pre-D0 device report. Current D0 device results are recorded only after installing the final SHA.

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
- The Phase C2 application commit installed and launched cleanly on a physical device: PASS.

## Phase C2 bounded smoke


The Shadow session ran for 1,051 seconds (17:31), analyzed 2,331 frames, dropped 0 frames from the latest-frame queue, and reported detector latency avg/p50/p95 of 59/52/68 ms. It produced 0 eligible decisions and 0 notifications on the available empty event state. Seven low-confidence candidates were rejected; none became actionable.

| Check | Result | Evidence |
|---|---|---|
| Real MediaProjection capture | PASS | Frame counters advanced continuously during the live session. |
| Target application visible | PASS | A live event screen was captured locally; no image was retained in the repository. |
| Event-list recognition | PASS | The available empty event screen was classified as `EVENT_LIST` with confidence 1.0 by the local frame inspector. |
| Sound | NOT_RUN | No eligible live target was available. |
| Vibration | NOT_RUN | No eligible live target was available. |
| Overlay OFF | PASS | Session ran without an overlay window. |
| Overlay ON | PASS | A separate application-overlay window was present; capture and analysis continued. |
| Pause/Resume in Shadow | PASS | Pause preserved Radar capture; Resume returned to observation with no stale pending target. |
| Mode-specific notification actions | PARTIAL | Shadow PAUSE/RESUME and STOP were exercised; non-auto action sets are code-verified only. |
| Capture Lab OFF | PASS | A save request created no archive and the ring buffer remained disabled. |
| Capture Lab ARMED | PASS | One local 19-frame, approximately five-second labelled archive was created and its manifest verified. |
| Stop | PASS | The foreground service disappeared from the system service list. |

The Capture Lab comparison is directional only, not a controlled benchmark. OFF produced 59/52/68 ms avg/p50/p95 over the long smoke. An earlier short ARMED observation produced 69/69/94 ms over 314 frames. Single CPU/RSS snapshots were noisy and are not reported as performance claims. Capture Lab therefore remains OFF by default.

## Guarded refresh smoke

Application source commit under test: `a41ba20`.

On a physical device, the application processed 1,376 frames during a 482-second Shadow session. A refresh control appeared once on a confirmed event-list screen; the application issued one request, the system gesture callback completed successfully, and the control disappeared. Runtime totals were 1 request, 1 success and 0 failures. No join or send action was available or dispatched. Raw frames and device identifiers were not retained in the repository.

## Functional matrix status

Only scenario H, an empty event list, was available during this validation window. It was captured and labelled locally; expected and actual behavior matched: event-list screen, no eligible target, and no alert. Scenarios A–G and I–M remain `NOT_RUN` because the live event supplied no rallies. Matrix completion is therefore 1/13, and the gate remains open.

No independent rally holdout, eligible Shadow-decision set, squad-state set, travel-time set, or already-joined sequence could be collected from the empty live event. The 60-minute final-release test and lifecycle matrix were not started because their prerequisite functional gates are still open.

## Phase C4 bounded validation

The Phase C4 candidate was installed cleanly and run with refresh set to `OFF`. A bounded smoke/performance session included a five-minute measurement window; collection and UI inspection brought the total session to 465 seconds. The application analyzed 892 frames, dropped 0 frames from the latest-frame queue, and reported avg/p50/p95 detector latency of 65/66/81 ms. The process and foreground service remained alive, memory samples fluctuated within a bounded range and returned near their earlier level, and the inspected logcat window contained no application crash or ANR.

| Check | Result |
|---|---|
| Refresh `OFF` on device | PASS: 0 requests, 0 accepted/completed gestures |
| Refresh detector on supplied real control image | PASS: event list and control detected; AUTO still requires a second stable frame |
| `ALERT_ONLY` no-gesture invariant | PASS in deterministic core test; live control NOT_OBSERVED in the bounded window |
| Guarded `AUTO_REFRESH` | PASS in deterministic package/screen/expiry tests; live control NOT_OBSERVED in the bounded window |
| Persistent control maximum | PASS: exactly two requests maximum in deterministic sequence |
| Gesture callback without UI change | PASS: not counted as success; bounded retry then `REFRESH_STUCK` |
| Guided R1–R8 / M1 / S1–S3 / T1–T5 | UI and 45-second termination compiled; rare live cases not collected in this window |

The connected device identifier, verified target package, raw frames and private Capture Lab archives were not written to this repository. The target package is supplied to the build through ignored local configuration. Current readiness is maintained in `CURRENT_STATE.md`.

Twenty-eight legacy private Capture Lab ZIPs were passed through the new runner. They were all reported as `NOT_OBSERVED` because they predate separate confirmed ground truth. This validates fail-closed replay classification but supplies no detector-accuracy evidence. The temporary desktop copy was removed immediately after the check.
