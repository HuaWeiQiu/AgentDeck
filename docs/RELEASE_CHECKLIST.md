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
- [ ] Confirm existing ChatGPT login, API Key environment, and active Provider `env_key` are detected without exposing credential values.
- [ ] With no usable credential, complete ChatGPT device auth or hidden API Key input, rerun the environment scan, and confirm authentication is ready.
- [ ] Enter a card and confirm native chat creates a Codex thread, streams Markdown, and restores the same history after leaving and reopening.
- [ ] Kill the Android app during a long turn, reopen the conversation, and confirm the orphan bridge is replaced, the persisted `inProgress` turn is interrupted, and a new message can run.
- [ ] Trigger command and file-change approvals; confirm accept, session accept, and decline reach Codex exactly once.
- [ ] Confirm the bridge listens only on `127.0.0.1`, rejects a wrong token, accepts one client, and exits after disconnect/idle timeout.
- [ ] Use the terminal icon and confirm the named Termux fallback session opens in the same workspace.
- [ ] Test workspace paths and CLI args containing spaces and quotes.

## Final review

- [ ] No CLI credentials or bridge tokens appear in `adb logcat`, process lists, Room, preferences, or persistent Termux files.
- [ ] UI remains usable on a compact phone viewport and with large font scaling.
- [ ] Known limitations are included in release notes.
- [ ] Tag and publish only after all required device checks pass.
