#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

cd "$repo_root"
git diff --check
git diff --cached --check
diff -ru recipes android/app/src/main/assets/recipes
diff -ru wrappers android/app/src/main/assets/wrappers

cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

test -s app/build/outputs/apk/debug/app-debug.apk
test -s app/build/reports/lint-results-debug.html

echo "AgentDeck release verification passed."
