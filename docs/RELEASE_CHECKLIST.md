# Release checklist

## Automated baseline

- [ ] `./scripts/verify-release.sh` passes from a clean checkout with JDK 17.
- [ ] GitHub Actions `Android CI` passes.
- [ ] GitHub private vulnerability reporting is enabled and the reporting flow is tested.
- [ ] Recipe and wrapper sources match packaged APK assets.
- [ ] `versionCode`, `versionName`, and `CHANGELOG.md` agree with the release.
- [ ] Release APK is signed with the intended key; debug signing is never presented as production signing.

## Migration

- [ ] Upgrade a v0.1.0 install and confirm Profiles/cards are retained.
- [ ] Confirm legacy credential preferences and the old key alias are removed once.
- [ ] Delete a referenced Profile and confirm its cards remain with no binding.
- [ ] Delete the final card/Profile, restart, and confirm it does not reappear.

## F-Droid Termux device

- [ ] Test a supported Android device with F-Droid Termux 0.118.3 (current stable baseline) or newer.
- [ ] Grant `RUN_COMMAND` only from the contextual Doctor action.
- [ ] Verify the Doctor states before and after `allow-external-apps=true`.
- [ ] Fresh-install `ubuntu:24.04`, then Codex 0.147.0 on arm64.
- [ ] Confirm a checksum or download failure is shown as failure, never success.
- [ ] Complete `codex login`, rerun Doctor, and confirm authentication is ready.
- [ ] Open the same card twice and confirm the named Termux session is reused.
- [ ] Test workspace paths and CLI args containing spaces and quotes.

## Final review

- [ ] No credentials appear in `adb logcat`, Intent extras, process lists, Room, or preferences.
- [ ] UI remains usable on a compact phone viewport and with large font scaling.
- [ ] Known limitations are included in release notes.
- [ ] Tag and publish only after all required device checks pass.
