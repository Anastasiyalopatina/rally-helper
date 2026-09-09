#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_DIR"
./gradlew \
  :vision-core:verifyCalibration \
  :vision-core:verifyMutations \
  :vision-core:verifyRuntimeTemplates \
  :vision-core:verifyCoreLogic \
  :app:assembleDebug \
  :app:lintDebug \
  :app:verifyNoRawCalibrationAssets
