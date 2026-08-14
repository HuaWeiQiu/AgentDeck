#!/usr/bin/env bash
set -euo pipefail

if [[ "${AGENTDECK_DISPOSABLE_DEVICE:-}" != "1" ]]; then
  echo "Refusing chat-performance device run: set AGENTDECK_DISPOSABLE_DEVICE=1 for a disposable Secure test device." >&2
  exit 2
fi
if [[ "${AGENTDECK_SECURE_PERF_DEVICE:-}" != "1" ]]; then
  echo "Refusing chat-performance device run: set AGENTDECK_SECURE_PERF_DEVICE=1 to confirm this is the Secure performance device, not a daily phone with unique user data." >&2
  exit 2
fi
if [[ "$#" -lt 1 ]]; then
  echo "Usage: AGENTDECK_DISPOSABLE_DEVICE=1 AGENTDECK_SECURE_PERF_DEVICE=1 $0 <serial> [50|300|1000]" >&2
  exit 2
fi

serial="$1"
turn_count="${2:-300}"
[[ "$serial" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] || { echo "Invalid serial" >&2; exit 2; }
case "$turn_count" in
  50|300|1000) ;;
  *) echo "Unsupported turn count: $turn_count" >&2; exit 2 ;;
esac

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
report_dir="$repo_root/android/app/build/reports/chat-performance"
mkdir -p "$report_dir"

lab_package="com.agentdeck.app.lab.debug"
installed="$(adb -s "$serial" shell pm list packages | tr -d '\r')"
if grep -Fq "package:$lab_package" <<<"$installed"; then
  echo "Lab package $lab_package is installed on $serial. Official chat-performance numbers must come from Secure Beta only." >&2
  exit 1
fi

abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"

cd "$repo_root/android"
./gradlew :macrobenchmark:connectedSecureBetaAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.agentdeck.macrobenchmark.ChatTranscriptScrollBenchmark

python3 - "$report_dir/secure-${abi}-${api}-${turn_count}.json" \
  "$serial" "$abi" "$api" "$model" "$turn_count" <<'PY'
import json
import sys

path, serial, abi, api, model, turn_count = sys.argv[1:]
with open(path, "w", encoding="utf-8") as output:
    json.dump(
        {
            "schema": 1,
            "suite": "agentdeck-secure-chat-performance",
            "channel": "secure",
            "package": "com.agentdeck.app.debug",
            "serial_present": True,
            "abi": abi,
            "api": int(api),
            "model": model,
            "turn_count": int(turn_count),
            "result": "executed",
            "notes": [
                "Frame timing artifacts live under android/macrobenchmark/build/outputs.",
                "Do not treat this JSON as the official numeric gate; copy medians into docs/CHAT_PERFORMANCE.md.",
            ],
        },
        output,
        ensure_ascii=True,
        indent=2,
    )
    output.write("\n")
PY
