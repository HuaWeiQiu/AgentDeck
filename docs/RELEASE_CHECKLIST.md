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
- [ ] Upgrade v0.1.4 and confirm existing conversations use “current Codex configuration” without requiring a new API Key.
- [ ] Confirm legacy OpenAI-compatible Profiles are retained as unverified metadata and no synthetic credential is created.

## Customer experience

- [ ] Standard mode exposes only conversation and settings top-level destinations; setup and recovery open in context.
- [ ] Standard mode never requires users to interpret Termux, PRoot, Ubuntu, app-server, ports, paths, exit codes, or shell logs.
- [ ] Existing Codex login, API Key environment, and managed Provider configuration are detected before any sign-in prompt is shown.
- [ ] Reasoning and tool activity are collapsed by default, approvals explain impact before confirmation, and the composer remains above the IME.
- [ ] Advanced and Developer settings are opt-in, reversible, and cannot expose secret values in UI, exports, screenshots, or logs.

## Embedded runtime default gate

Complete this section before changing the default from `TermuxRuntime` to `EmbeddedProotRuntime`.

- [ ] Runtime packs and manifests are signed, versioned, checksum-verified, installed atomically, and recover after process death.
- [ ] The previous known-good Base, Codex, and wrapper versions can be rolled back independently without deleting user configuration or conversations.
- [ ] Runtime processes are owned by a foreground service, accept control only through an app-private authenticated channel, and terminate without orphaned children.
- [ ] APK/AAB packaging, native library extraction, ABI coverage, target-SDK behavior, licenses, notices, and corresponding source obligations are verified.
- [ ] Fresh install, upgrade, rollback, no-space, corrupt-pack, offline, lock-screen, OEM background, and reboot scenarios pass on representative ARM64 devices.

## F-Droid Termux device (compatibility backend)

- [ ] Test a supported Android device with F-Droid Termux 0.118.3 (current stable baseline) or newer.
- [ ] Grant `RUN_COMMAND` only from the contextual Doctor action.
- [ ] Verify the Doctor states before and after `allow-external-apps=true`.
- [ ] Fresh-install `ubuntu:24.04`, then Codex 0.147.0 on arm64.
- [ ] Confirm a checksum or download failure is shown as failure, never success.
- [ ] Confirm existing ChatGPT login, API Key environment, and active Provider `env_key` are detected without exposing credential values.
- [ ] With no usable credential, complete ChatGPT device auth or hidden API Key input, rerun the environment scan, and confirm authentication is ready.
- [ ] Enter a card and confirm native chat creates a Codex thread, streams Markdown, and restores the same history after leaving and reopening.
- [ ] Kill the Android app during a long turn, reopen the conversation, and confirm the orphan app-server tree is replaced, the persisted `inProgress` turn is interrupted, and a new message can run.
- [ ] Trigger command and file-change approvals; confirm accept, session accept, and decline reach Codex exactly once.
- [ ] Confirm app-server listens only on `127.0.0.1`, rejects a wrong capability token, and its supervised process tree exits after disconnect.
- [ ] Use the terminal icon and confirm the named Termux fallback session opens in the same workspace.
- [ ] Test workspace paths and CLI args containing spaces and quotes.
- [ ] Add a Sub2API Provider, retrieve its `/v1/models` list, select a model, and complete a real native-chat turn with the returned Provider/model matching the selection.
- [ ] Run two managed Providers sequentially and confirm credentials, model caches, thread mappings, and app-server processes do not cross over.
- [ ] Verify wrong keys, forbidden groups, rate limits, malformed model responses, TLS failures, and model-list timeouts terminate with actionable UI states.

## Final review

- [ ] No CLI or managed Provider credentials appear in `adb logcat`, process lists, Room, preferences, Codex config, or persistent Termux files.
- [ ] Confirm the managed credential ciphertext is under `noBackupFilesDir`, Keystore invalidation fails closed, deleting a Provider removes its ciphertext, and screenshots are blocked while the API Key editor is visible.
- [ ] UI remains usable on a compact phone viewport and with large font scaling.
- [ ] Known limitations are included in release notes.
- [ ] Tag and publish only after all required device checks pass.
