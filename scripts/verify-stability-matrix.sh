#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
report_dir="$repo_root/android/app/build/reports/stability"
report="$report_dir/host-matrix.json"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_seconds="$(date +%s)"
result="failed"

mkdir -p "$report_dir"

write_report() {
  local exit_code="$?"
  local finished_at
  local duration
  finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  duration="$(( $(date +%s) - started_seconds ))"
  if [[ "$exit_code" -eq 0 ]]; then
    result="passed"
  fi
  python3 - "$report" "$started_at" "$finished_at" "$duration" "$result" <<'PY'
import json
import sys

path, started, finished, duration, result = sys.argv[1:]
payload = {
    "schema": 1,
    "suite": "agentdeck-host-stability-matrix",
    "started_at": started,
    "finished_at": finished,
    "duration_seconds": int(duration),
    "result": result,
    "scenarios": [
        "rpc_timeout_disconnect_malformed_late_send_close",
        "runtime_download_resume_retry_checksum_http",
        "runtime_manifest_abi_marker_space_tar",
        "file_adapter_text_pdf_docx_xlsx_limits",
    ],
}
with open(path, "w", encoding="utf-8") as output:
    json.dump(payload, output, ensure_ascii=True, indent=2)
    output.write("\n")
PY
  return "$exit_code"
}
trap write_report EXIT

python3 "$repo_root/scripts/test-file-adapter.py"
"$repo_root/android/gradlew" -p "$repo_root/android" :app:testDebugUnitTest \
  --tests 'com.agentdeck.app.data.chat.CodexRpcClientTest' \
  --tests 'com.agentdeck.app.data.chat.ChatAttachmentStoreTest' \
  --tests 'com.agentdeck.app.domain.chat.CodexProtocolTest' \
  --tests 'com.agentdeck.app.data.runtime.EmbeddedRuntimeDownloadTest' \
  --tests 'com.agentdeck.app.data.runtime.EmbeddedRuntimeManifestTest' \
  --tests 'com.agentdeck.app.data.runtime.EmbeddedProotRuntimeTest'
