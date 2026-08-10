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
for binary in android/app/src/main/jniLibs/arm64-v8a/libproot.so \
  android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so \
  android/app/src/main/jniLibs/arm64-v8a/libtalloc.so; do
  file "$binary" | grep -Fq 'ARM aarch64'
done
for binary in android/app/src/main/jniLibs/x86_64/libproot.so \
  android/app/src/main/jniLibs/x86_64/libproot-loader.so \
  android/app/src/main/jniLibs/x86_64/libtalloc.so; do
  file "$binary" | grep -Fq 'x86-64'
done
python3 scripts/test-file-adapter.py

cd android
./gradlew :app:testDebugUnitTest :app:assembleBeta :app:lintBeta

arm64_apk="app/build/outputs/apk/beta/app-arm64-v8a-beta.apk"
x86_apk="app/build/outputs/apk/beta/app-x86_64-beta.apk"
test -s "$arm64_apk"
test -s "$x86_apk"
test -s app/build/reports/lint-results-beta.html
for apk in "$arm64_apk" "$x86_apk"; do
  apk_entries="$(unzip -Z1 "$apk")"
  grep -Fxq 'assets/licenses/PROOT-GPL-2.0.txt' <<<"$apk_entries"
  grep -Fxq 'assets/dexopt/baseline.prof' <<<"$apk_entries"
done
arm64_entries="$(unzip -Z1 "$arm64_apk")"
x86_entries="$(unzip -Z1 "$x86_apk")"
grep -Fxq 'lib/arm64-v8a/libproot.so' <<<"$arm64_entries"
grep -Fxq 'lib/arm64-v8a/libproot-loader.so' <<<"$arm64_entries"
grep -Fxq 'lib/arm64-v8a/libtalloc.so' <<<"$arm64_entries"
! grep -Fq 'lib/x86_64/libproot.so' <<<"$arm64_entries"
grep -Fxq 'lib/x86_64/libproot.so' <<<"$x86_entries"
grep -Fxq 'lib/x86_64/libproot-loader.so' <<<"$x86_entries"
grep -Fxq 'lib/x86_64/libtalloc.so' <<<"$x86_entries"
! grep -Fq 'lib/arm64-v8a/libproot.so' <<<"$x86_entries"

echo "AgentDeck release verification passed."
