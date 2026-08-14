#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

cd "$repo_root/android"
# Compile-only. Shared GitHub runners and developer laptops must not treat
# emulator or phone frame timing as a gate. Official numbers stay on a marked
# Secure ARM64 device via scripts/verify-chat-performance.sh.
./gradlew :macrobenchmark:assembleSecureBeta
