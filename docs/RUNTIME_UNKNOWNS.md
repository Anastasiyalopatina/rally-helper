# Runtime unknowns

The following items require a connected physical Android device and must not be inferred from desktop tests:

- captured app-window geometry and resize callbacks;
- system projection termination and resize callbacks;
- long-run RSS, CPU, GC, temperature and battery impact;
- game/application FPS impact;
- lifecycle behavior under lock, backgrounding, Battery Saver, popups and orientation changes;
- real holdout precision/recall and false-actionable rate;
- recorded multi-card, reorder, scroll and refresh sequences.

Until these are measured, Phase B2 is incomplete and no input automation phase should begin.
