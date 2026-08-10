#!/usr/bin/env bash
set -euo pipefail

if [[ "${AGENTDECK_DISPOSABLE_DEVICE:-}" != "1" ]]; then
  echo "Refusing device matrix: set AGENTDECK_DISPOSABLE_DEVICE=1 for a disposable test device." >&2
  exit 2
fi
if [[ "$#" -ne 2 ]]; then
  echo "Usage: AGENTDECK_DISPOSABLE_DEVICE=1 $0 <serial> <apk>" >&2
  exit 2
fi

serial="$1"
apk="$2"
package="com.agentdeck.app.debug"
[[ "$serial" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] || { echo "Invalid serial" >&2; exit 2; }
[[ -s "$apk" ]] || { echo "APK does not exist" >&2; exit 2; }

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
report_dir="$repo_root/android/app/build/reports/stability"
mkdir -p "$report_dir"

abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
adb -s "$serial" install -r -t "$apk"
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
pid="$(adb -s "$serial" shell pidof "$package" | tr -d '\r')"
[[ "$pid" =~ ^[0-9]+$ ]] || { echo "App did not start" >&2; exit 1; }

python3 - "$report_dir/device-${abi}-${api}.json" "$abi" "$api" <<'PY'
import json
import sys

path, abi, api = sys.argv[1:]
with open(path, "w", encoding="utf-8") as output:
    json.dump(
        {
            "schema": 1,
            "suite": "agentdeck-disposable-device-smoke",
            "abi": abi,
            "api": int(api),
            "result": "passed",
            "scenarios": ["install", "cold_start"],
        },
        output,
        ensure_ascii=True,
        indent=2,
    )
    output.write("\n")
PY
