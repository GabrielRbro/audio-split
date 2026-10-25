#!/bin/sh
set -eu
cd "$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest \
  :app:lintDebug :tone-player:lintBluetoothDebug :tone-player:lintPhoneDebug \
  :app:assembleDebug :tone-player:assembleBluetoothDebug :tone-player:assemblePhoneDebug "$@"
