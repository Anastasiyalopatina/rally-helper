# Rally Helper

Local Android/Kotlin screen-analysis utility.

The public build is fail-closed. It supports read-only monitoring and a supervised experimental one-tap screen-open flow; final send and unattended automation remain disabled.

Current validation state and bounded test commands are documented in [`docs/CURRENT_STATE.md`](docs/CURRENT_STATE.md).

## Build

```bash
./gradlew :app:assembleProductionDebug
```
