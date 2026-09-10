# Evaluation

## Local checks

| Check | Result |
|---|---:|
| Calibration smoke fixtures | 9/9 pass |
| Synthetic robustness mutations | 30/30 pass |
| Stale-track regression | pass |
| Duplicate/reorder identity regression | pass |
| Free-slot and travel-margin guards | pass |
| Delay sampled once per rally | pass |
| Skip counter consumes eligible rallies only | pass |
| Deterministic single-target priority | pass |
| Monotonic delay + fresh-frame revalidation | pass |
| Pause cancels pending target and resumes fresh | pass |
| Relaxed alert cannot authorize action | pass |
| Pending cancellation is not a completed attempt | pass |
| Policy state resets between sessions | pass |
| Refresh OFF / alert-only / guarded-auto contract | pass |
| Wrong package / stale / expired gesture rejection | pass |
| Persistent refresh bounded to two requests | pass |
| Deterministic replay infrastructure | pass |
| Room migrations 1→2 / 2→3 / 1→3 / 3→4 / 1→4 | 5/5 pass on physical device |
| Raw screenshots in debug APK | 0 |
| Android compile / lint | pass |
| Room schema export | v4 |

Synthetic mutations cover brightness, contrast, slight scale, translation, JPEG compression, mild blur, inset shift, and harmless occlusion. They are robustness tests, not a real holdout.

## Independent validation

| Metric | Result |
|---|---:|
| Boss precision / recall | NOT_RUN |
| Joinable precision / recall | NOT_RUN |
| Level accuracy | NOT_RUN |
| Participant accuracy | NOT_RUN |
| Timer accuracy | NOT_RUN |
| False actionable target rate | NOT_RUN |
| Median / p95 detector latency | NOT_RUN |
| Frame drop rate | NOT_RUN |

Earlier device candidates completed bounded smoke and stability checks without latest-frame queue drops. Exact device measurements are intentionally not published. Those runs are not independent holdout and did not exercise the critical real-case matrix. The current gate is based on unique confirmed scenarios plus deterministic replay, not elapsed watching time or a quota of Shadow decisions. See `CURRENT_STATE.md`.

## Phase C2 live observation

One real empty-event scenario was retained locally as calibration evidence, not as an independent rally holdout. The detector classified the screen as `EVENT_LIST` with confidence 1.0. It rejected every weak decorative candidate: 0 eligible decisions, 0 alerts, and 0 false actionable targets in that single negative scenario. This observation is too small to publish precision, recall, or a false-actionable rate, so the metrics above remain `NOT_RUN`.

The 17:31 Shadow smoke analyzed 2,331 frames with 0 queue drops and avg/p50/p95 latency of 59/52/68 ms. These runtime measurements do not substitute for labelled detector evaluation.
