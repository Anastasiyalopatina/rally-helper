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
| Raw screenshots in debug APK | 0 |
| Android compile / lint | pass |
| Room schema export | v3 |

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

An intermediate device candidate completed a bounded smoke and a continuous RADAR stability run beyond 30 minutes without latest-frame queue drops. Exact device measurements are intentionally not published. This is not an independent holdout and did not exercise the required functional scenario matrix. Independent holdout, recorded sequences, 100 shadow decisions, and the final release-candidate endurance are not yet complete. See `DEVICE_TEST_REPORT.md`.
