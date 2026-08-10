#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

cd "$repo_root"
git diff --check
git diff --cached --check
diff -ru recipes android/app/src/main/assets/recipes
diff -ru wrappers android/app/src/main/assets/wrappers
shasum -a 256 -c third_party/embedded-runtime.sha256

cd android
./gradlew :app:testDebugUnitTest :app:assembleBeta :app:lintBeta

test -s app/build/outputs/apk/beta/app-beta.apk
test -s app/build/reports/lint-results-beta.html
apk_entries="$(unzip -Z1 app/build/outputs/apk/beta/app-beta.apk)"
grep -Fxq 'lib/arm64-v8a/libproot.so' <<<"$apk_entries"
grep -Fxq 'lib/arm64-v8a/libproot-loader.so' <<<"$apk_entries"
grep -Fxq 'lib/arm64-v8a/libtalloc.so' <<<"$apk_entries"
grep -Fxq 'assets/licenses/PROOT-GPL-2.0.txt' <<<"$apk_entries"
grep -Fxq 'assets/dexopt/baseline.prof' <<<"$apk_entries"

echo "AgentDeck release verification passed."
