# Current state

Date: 2026-09-10. Canonical status for Phase D2.

## Product modes

- `RADAR`: **ACTIVE**. Confirmed targets produce one local alert per stable identity.
- `ONE TAP`: **ACTIVE / EXPERIMENTAL**. One explicit overlay tap authorizes one bounded flow: fresh target revalidation, open, squad selection, travel/safety check, send, and post-send verification. Any unknown or changed evidence stops with manual fallback.
- `AUTO`: **LOCKED**. No unattended join/send path is exposed.

The overlay is compact (`ONE TAP`, action, emergency close), draggable across the display, and removed immediately on STOP, task dismissal, service destruction, or projection loss. A lifecycle revision prevents queued updates from recreating a closed window; a process-wide owner prevents duplicate overlay instances.

## Input safety

- Gestures are purpose-scoped as `REFRESH`, `JOIN_PLUS`, `SELECT_SQUAD`, or `SEND`.
- Every request has a maximum 750 ms TTL and is bound to a source frame, verified foreground package/generation, projection generation, and single-flight coordinator lease.
- Squad selection uses exactly three slots. `FREE` is eligible; `RETURNING` additionally requires the expected icon/timer/occupied combination and an enabled user setting. `BUSY` and `UNKNOWN` always fail closed.
- `SEND` additionally requires a freshly verified selected squad, enabled send control, non-empty troop evidence, and `travel + safety margin < estimated remaining` when travel is known. Unknown travel defaults to manual fallback.
- Android gesture completion is not treated as success. A send succeeds only after a later verified world-map frame.
- ONE TAP suppresses refresh while its flow is active. Refresh itself is dispatched only from a fresh frame containing the detected control.
- Capture visibility loss, projection termination, geometry invalidation, foreground change, stop, and task dismissal cancel all pending actions.

## Alerts and settings

The foreground-service notification is low importance and silent. Target alerts use a separate high-importance channel plus optional app-local audio and vibration. `SYSTEM` and `MEDIA` audio paths use the same approximately 0.85-second two-tone cue. Squad priority defaults to `1 → 2 → 3`; returning squads are configurable; unknown-travel fallback is explicitly experimental and defaults off. Delay controls are limited to 0–30 seconds.

## Verification

- ONE_TAP_A regressions and the complete M1–M16 state-machine matrix pass in `:vision-core:test`.
- The deterministic Android target models three squad slots, selection, send, and verified/unverified post-send transitions; it is excluded from production.
- Room schema v6 and migrations through 1→6 pass on a physical Android device.
- Production and integration variants compile successfully. Raw calibration assets and integration markers are rejected from production by build checks.
- Real-target squad classification and a real verified send remain bounded live-observation items; weak evidence falls back after opening instead of disabling the working open step.
