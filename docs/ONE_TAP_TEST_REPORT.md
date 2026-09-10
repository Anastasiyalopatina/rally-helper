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

Those rows describe the earlier candidate only. The current runtime has purpose-scoped `JOIN_PLUS`, `SELECT_SQUAD`, and `SEND` paths, fresh-identity revalidation, foreground/source-frame/flow/projection guards, and post-send screen verification. It cannot retrieve window content.

Current complete state-machine matrix M1–M16: **PASS** in core tests. The expanded deterministic physical target and real-target squad/send observations are separate bounded validation items; deterministic fixtures are not CV evidence.
