#!/usr/bin/env bash
set -euo pipefail

package_name="${1:-com.rallyhelper}"
duration_seconds="${2:-600}"
sample_seconds="${3:-60}"

if ! [[ "$duration_seconds" =~ ^[0-9]+$ && "$sample_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "usage: $0 [package] [duration-seconds] [sample-seconds]" >&2
  exit 64
fi

started_at=$(date +%s)
sample=0

echo "sample elapsed_s pid java_kb native_kb rss_kb cpu_pct service"
while :; do
  now=$(date +%s)
  elapsed=$((now - started_at))
  pid=$(adb shell pidof "$package_name" | tr -d '\r')
  if [[ -z "$pid" ]]; then
    echo "$sample $elapsed - - - - - PROCESS_MISSING"
    exit 2
  fi

  meminfo=$(adb shell dumpsys meminfo "$package_name")
  java_kb=$(awk '/Java Heap:/ {print $3; exit}' <<<"$meminfo")
  native_kb=$(awk '/Native Heap:/ {print $3; exit}' <<<"$meminfo")
  rss_kb=$(awk '/TOTAL PSS:/ {for (i = 1; i <= NF; i++) if ($i == "RSS:") {print $(i + 1); exit}}' <<<"$meminfo")
  cpu_pct=$(adb shell top -b -n 1 -p "$pid" | awk -v wanted="$pid" '$1 == wanted {print $9; exit}')
  if adb shell dumpsys activity services "$package_name" | grep -q 'isForeground=true'; then
    service_state="FOREGROUND"
  else
    service_state="MISSING"
  fi

  echo "$sample $elapsed $pid ${java_kb:--} ${native_kb:--} ${rss_kb:--} ${cpu_pct:--} $service_state"
  if (( elapsed >= duration_seconds )); then
    break
  fi

  remaining=$((duration_seconds - elapsed))
  if (( remaining < sample_seconds )); then
    sleep "$remaining"
  else
    sleep "$sample_seconds"
  fi
  sample=$((sample + 1))
done
