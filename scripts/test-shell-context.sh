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

# Exercise the real app process without Shizuku before inspecting its installed metadata.
# This catches startup failures in Compose / retained update state that a shell-only probe misses.
smoke_report=app/build/reports/main-activity-smoke.txt
adb shell am force-stop io.github.audiosplit
adb logcat -b crash -c
launch=$(adb shell am start -W -n io.github.audiosplit/.MainActivity 2>&1)
printf '%s\n' "$launch" > "$smoke_report"
case "$launch" in
  *'Status: ok'*) ;;
  *) cat "$smoke_report" >&2; exit 1 ;;
esac
sleep 3
if ! adb shell pidof io.github.audiosplit >> "$smoke_report"; then
  adb logcat -b crash -d >> "$smoke_report"
  cat "$smoke_report" >&2
  exit 1
fi
printf 'MAIN_ACTIVITY_SMOKE_OK\n' >> "$smoke_report"
cat "$smoke_report"

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
