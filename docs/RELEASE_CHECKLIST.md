# Release checklist

## Automated baseline

- [ ] `./scripts/verify-release.sh` passes from a clean checkout with JDK 17.
- [ ] Run `connectedDebugAndroidTest` only on a disposable test device or emulator. The Gradle
      connected-test task can uninstall the target package after the run and therefore must never
      be pointed at a device containing the only copy of conversations, Runtime state, or credentials.
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

## Embedded runtime gate

- [ ] Runtime packs and manifests are signed, versioned, checksum-verified, installed atomically, and recover after process death.
- [ ] The previous known-good Base, Codex, and wrapper versions can be rolled back independently without deleting user configuration or conversations.
- [ ] Runtime processes are owned by a foreground service, accept control only through an app-private authenticated channel, and terminate without orphaned children.
- [ ] APK/AAB packaging, native library extraction, ABI coverage, target-SDK behavior, licenses, notices, and corresponding source obligations are verified.
- [ ] Fresh install, upgrade, rollback, no-space, corrupt-pack, offline, lock-screen, OEM background, and reboot scenarios pass on representative ARM64 devices.

## ARM64 Android device

- [ ] Fresh-install only AgentDeck on a supported ARM64 device; do not install or configure Termux.
- [ ] Complete the one-button download, checksum verification, extraction, Codex installation, and
      functional self-test; leaving and reopening the app must resume an interrupted download.
- [ ] Confirm the installed Runtime and persistent Codex home remain app-private and survive an
      AgentDeck upgrade and Runtime repair.
- [ ] Confirm a checksum or download failure is shown as failure, never success.
- [ ] With no Codex files on first use, verify ChatGPT device login, hidden OpenAI API Key input, and
      third-party Responses setup are offered without creating fake authentication state.
- [ ] Confirm official ChatGPT/API Key login is stored by Codex in its auth store, separately from
      `config.toml`; verify AgentDeck-managed Provider keys remain in Android Keystore.
- [ ] Open the Codex parameters editor and confirm its first-use template distinguishes optional
      values, contains commented examples, rejects malformed TOML and credentials, and is applied
      to the next session without overwriting the global Codex config.
- [ ] Add both a Sub2API preset and a generic Responses service; verify their labels/help differ,
      `/v1/models` discovery works, and the selected Provider/model reaches the real chat turn.
- [ ] Create a conversation with a role identity, start and reconnect native chat, and confirm each
      turn receives the identity as developer instructions rather than displaying a fake user message.
- [ ] Confirm the conversation list sorts and displays the actual last activity date and time.
- [ ] Enter a conversation and confirm native chat creates a Codex thread, streams Markdown, offers
      a real model selector, and restores the same history after leaving and reopening.
- [ ] Kill the Android app during a long turn, reopen the conversation, and confirm the orphan app-server tree is replaced, the persisted `inProgress` turn is interrupted, and a new message can run.
- [ ] Trigger command and file-change approvals; confirm accept, session accept, and decline reach Codex exactly once.
- [ ] Confirm app-server listens only on `127.0.0.1`, rejects a wrong capability token, and its supervised process tree exits after disconnect.
- [ ] Test workspace paths and CLI args containing spaces and quotes.
- [ ] Run two managed Providers sequentially and confirm credentials, model caches, thread mappings, and app-server processes do not cross over.
- [ ] Verify wrong keys, forbidden groups, rate limits, malformed model responses, TLS failures, and model-list timeouts terminate with actionable UI states.

## Final review

- [ ] No CLI or managed Provider credentials appear in `adb logcat`, process lists, Room,
      preferences, AgentDeck's TOML profile, or other persistent Runtime files.
- [ ] Confirm the managed credential ciphertext is under `noBackupFilesDir`, Keystore invalidation fails closed, deleting a Provider removes its ciphertext, and screenshots are blocked while the API Key editor is visible.
- [ ] UI remains usable on a compact phone viewport and with large font scaling.
- [ ] Known limitations are included in release notes.
- [ ] Tag and publish only after all required device checks pass.
