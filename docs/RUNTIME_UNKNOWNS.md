# Runtime unknowns

The following items require a connected physical Android device and must not be inferred from desktop tests:

- captured app-window geometry and resize callbacks;
- system projection termination and resize callbacks;
- final-release 60-minute RSS, CPU, GC, temperature and battery impact;
- game/application FPS impact;
- lifecycle behavior under lock, backgrounding, Battery Saver, popups and orientation changes;
- real holdout precision/recall and false-actionable rate;
- recorded multi-card, reorder, scroll and refresh sequences.
- a live signal that distinguishes already-joined from other no-plus states;
- labelled squad states beyond the existing unknown-only observations;
- labelled travel times at approximately 3, 7, 12, 20+ and 60+ seconds;
- redirect capability for a returning squad;
- overlay behavior across vendor permission/lifecycle variants.
- Capture Lab OFF versus ARMED CPU and latency delta on the final C2 build;

Until these are measured, `ONE_TAP` and `AUTO` input are locked. Unknown evidence always produces no action.
