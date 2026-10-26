#!/bin/sh
set -eu
cd "$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"

apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
remote=/data/local/tmp/audio-split-context-probe.apk
report=app/build/reports/shell-context-probe.txt
if [ ! -f "$apk" ]; then
  printf 'APK ausente: %s. Compile :app:assembleDebug primeiro.\n' "$apk" >&2
  exit 1
fi

adb unroot >/dev/null
adb wait-for-device
adb install -r "$apk"
adb push "$apk" "$remote"
trap 'adb shell rm -f "$remote" >/dev/null 2>&1 || true' 0
mkdir -p app/build/reports

# Do not pipe the adb command: its remote process exit status must reach the CI job.
if output=$(adb shell "CLASSPATH=$remote app_process /system/bin io.github.audiosplit.engine.ShellContextProbe" 2>&1); then
  printf '%s\n' "$output" > "$report"
  printf '%s\n' "$output"
else
  status=$?
  printf '%s\n' "$output" > "$report"
  printf '%s\n' "$output" >&2
  exit "$status"
fi

case "$output" in
  *'SHELL_CONTEXT_REGRESSION_OK '*) ;;
  *) printf 'A sonda terminou sem confirmar a regressão do contexto shell.\n' >&2; exit 1 ;;
esac
