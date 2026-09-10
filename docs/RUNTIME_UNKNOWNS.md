# Runtime unknowns

> This is a backlog of broader automation evidence. It does not block experimental ONE TAP; see `docs/CURRENT_STATE.md`.

The following items require a connected physical Android device and must not be inferred from desktop tests:

- captured app-window geometry and resize callbacks beyond the validated full-screen path;
- system projection termination and resize callbacks;
- final-release 60-minute RSS, CPU, GC, temperature and battery impact;
- game/application FPS impact;
- lifecycle behavior under lock, backgrounding, Battery Saver, popups and orientation changes;
- real holdout precision/recall and false-actionable rate;
- recorded multi-card, reorder, scroll and refresh sequences.
- a live signal that distinguishes already-joined from other no-plus states;
- labelled squad states beyond the existing unknown-only observations;
- at least five distinct labelled travel times, including one above 60 seconds;
- redirect capability for a returning squad;
- overlay behavior across additional vendor permission/lifecycle variants;
- a controlled Capture Lab OFF versus ARMED CPU, RSS and latency benchmark;
- sound and vibration delivery under the user's final device sound profile (the app pipeline was invoked on-device, while the current system profile muted audio and rejected vibration);

These unknowns do not relock experimental ONE TAP. They keep AUTO unavailable, and unknown evidence always produces a manual fallback or no action.
