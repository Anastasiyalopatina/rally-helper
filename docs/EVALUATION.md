# Evaluation

## Local checks

| Check | Result |
|---|---:|
| Calibration smoke fixtures | 9/9 pass |
| Synthetic robustness mutations | 30/30 pass |
| Stale-track regression | pass |
| Duplicate/reorder identity regression | pass |
| Raw screenshots in debug APK | 0 |

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

Physical-device smoke validation is tracked outside the public repository. Independent holdout, recorded sequences, a 30–60-minute live run, and a 60-minute endurance run are not yet complete.
