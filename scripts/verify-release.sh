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
# Product flavors (ADR-0012): secure = daily L1, lab = experimental L2–L4.
./gradlew \
  :app:testSecureDebugUnitTest \
  :app:testLabDebugUnitTest \
  :app:assembleSecureBeta \
  :app:assembleLabBeta \
  :app:lintSecureBeta \
  :app:lintLabBeta

secure_arm64="app/build/outputs/apk/secure/beta/app-secure-arm64-v8a-beta.apk"
secure_x86="app/build/outputs/apk/secure/beta/app-secure-x86_64-beta.apk"
lab_arm64="app/build/outputs/apk/lab/beta/app-lab-arm64-v8a-beta.apk"
lab_x86="app/build/outputs/apk/lab/beta/app-lab-x86_64-beta.apk"
for apk in "$secure_arm64" "$secure_x86" "$lab_arm64" "$lab_x86"; do
  test -s "$apk"
done
test -s app/build/reports/lint-results-secureBeta.html
test -s app/build/reports/lint-results-labBeta.html

verify_apk_common() {
  local apk="$1"
  local apk_entries
  apk_entries="$(unzip -Z1 "$apk")"
  grep -Fxq 'assets/licenses/PROOT-GPL-2.0.txt' <<<"$apk_entries"
  grep -Fxq 'assets/dexopt/baseline.prof' <<<"$apk_entries"
}

verify_abi_split() {
  local arm64_apk="$1"
  local x86_apk="$2"
  local arm64_entries x86_entries
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
}

for apk in "$secure_arm64" "$secure_x86" "$lab_arm64" "$lab_x86"; do
  verify_apk_common "$apk"
done
verify_abi_split "$secure_arm64" "$secure_x86"
verify_abi_split "$lab_arm64" "$lab_x86"

# Channel isolation: Secure must not ship either Lab-only executor; Lab must include both.
# Binary XML/strings are unreliable; search dex/manifest bytes instead.
python3 - "$secure_arm64" "$secure_x86" "$lab_arm64" "$lab_x86" <<'PY'
import sys
import zipfile

needles = (b"LabAccessibilityService", b"LabLocalMcpRuntimeAdapter")


def contains(apk: str, needle: bytes) -> bool:
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.endswith(".dex") or name.endswith("AndroidManifest.xml"):
                if needle in archive.read(name):
                    return True
    return False


secure_apks = sys.argv[1:3]
lab_apks = sys.argv[3:5]
for needle in needles:
    label = needle.decode()
    for apk in secure_apks:
        if contains(apk, needle):
            raise SystemExit(f"secure APK unexpectedly contains {label}: {apk}")
    for apk in lab_apks:
        if not contains(apk, needle):
            raise SystemExit(f"lab APK missing {label}: {apk}")
print("channel isolation ok")
PY

echo "AgentDeck release verification passed (secure + lab)."
