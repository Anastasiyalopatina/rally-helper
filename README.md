# Rally Helper

Local Android/Kotlin screen-analysis utility.

The public build is fail-closed: join/send actions remain disabled until the recorded device-validation gates pass. A separately enabled Accessibility service can press only a high-confidence refresh control on a confirmed event-list screen.

## Build

```bash
./gradlew :app:assembleDebug
```
