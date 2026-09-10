# ONE_TAP test status

> The table below is a historical pre-D0 result. It is not the current capability statement. See `docs/CURRENT_STATE.md`: ONE_TAP_A is implemented and active in experimental mode.

Date: 2026-09-10.

| Gate | Status |
|---|---|
| Independent holdout | NOT_RUN |
| Critical real cases R1–R8 | INCOMPLETE |
| Confirmed deterministic replay | WAITING FOR CONFIRMED PRIVATE SEQUENCES |
| Session-reset policy regression | PASS |
| Squad-state evidence | NOT_RUN |
| Travel-time evidence | NOT_RUN |
| No-plus safety evidence | NOT_RUN |
| Final-release endurance | DEFERRED; not a prerequisite for ONE_TAP_A |
| ONE_TAP gesture implementation | NOT_PRESENT |
| ONE_TAP live test | BLOCKED |

Those rows describe the earlier candidate only. The current runtime has a purpose-scoped `JOIN_PLUS` path, fresh-identity revalidation, foreground/projection generation guards and post-gesture screen verification. It still has no `SEND` path and cannot retrieve window content.

Current deterministic overlay-to-physical-tap integration: **PASS**. Real-target recognition and the first supervised live attempt: **PENDING / NOT_OBSERVED**. These are separate claims; the deterministic target is not CV evidence.
