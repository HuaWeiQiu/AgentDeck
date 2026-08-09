# Changelog

All notable changes to AgentDeck are documented in this file.

## Unreleased

## 0.2.0-beta.1 - 2026-08-09

- Refined the mobile chat transcript with compact expandable activity, a keyboard-safe composer,
  customer-facing errors, and a dedicated approval sheet that remains recoverable after dismissal.

### Added

- Added managed Sub2API/OpenAI Responses-compatible Provider profiles with authenticated model discovery and per-conversation model selection.
- Added Android Keystore-backed credential storage and a loopback `auth.command` broker that keeps API keys out of Room, Codex config, Termux files, Intents, argv, and logs.
- Defined Standard, Advanced, and Developer experience levels so the default mobile flow exposes tasks and recovery actions instead of Linux runtime details.
- Accepted an `AgentRuntime` boundary and a staged `EmbeddedProotRuntime` target while retaining Termux as an explicit compatibility backend.
- Added a persisted Advanced Settings switch for model, project, compatibility-runtime, and environment controls.
- Added global and per-conversation Codex permission presets for read-only, ask-first, and full-access operation.
- Added an experimental ARM64 embedded Runtime with packaged PRoot, verified Ubuntu Base/Codex downloads, atomic installation, app-owned processes, and a foreground-service lease.
- Added an Advanced Settings runtime selector; existing completed Termux installations stay on the compatibility backend until embedded-device acceptance is complete.
- Added one-click import of the active Termux/Ubuntu Codex CLI Provider into Keystore-backed model services and bound it to the default Codex conversation.

### Changed

- Existing Codex/ChatGPT authentication remains the upgrade-safe default while local Provider profiles become real app-server runtime inputs.
- Updated the product, setup, contribution, release, and reference-project contracts for the two-tab customer experience and the future app-owned local runtime.
- Replaced the permanent Tools tab with contextual setup, grouped technical checks into customer-facing steps, and simplified the conversation and settings layouts for phone use.
- Replaced the permissive `on-request` PRoot policy with explicit `untrusted` or `never` mappings; read-only auto-declines unsafe operations and cannot open the terminal fallback.
- Chat now force-follows a newly sent message and keeps streaming output pinned to the latest item until the user deliberately scrolls upward.
- Doctor, installers, terminal fallback, and native chat now depend on the `AgentRuntime` contract instead of Termux paths or Intents.

### Fixed

- Made the “current Codex configuration” row actionable and fixed first-save model IDs so newly added services persist their discovered models.
- Hid the global setup warning once a managed conversation is ready.
- Terminated the complete embedded PRoot/Codex process tree on conversation exit, including ptrace-managed children that Android does not expose through the usual `children` file.

### Verified

- On Android 16 / iQOO Neo8 (V2301A, ARM64): embedded Ubuntu/Codex preparation, existing DeepSeek Provider import, encrypted credential persistence, a real native-chat response, IME-safe composition, collapsed reasoning, latest-message following, background foreground-service ownership, and zero orphan processes after exit.

## 0.1.4 - 2026-08-09

### Changed

- Replaced the Python stdio relay with the official authenticated Codex app-server WebSocket transport on `127.0.0.1`.
- Native chat now reports the Provider and model returned by the running CLI instead of presenting an unrelated local Profile as the active model.
- Removed Profile selection from current conversation and settings surfaces; existing database records remain compatible and untouched.
- Redesigned the transcript hierarchy: answers remain in the primary timeline, while consecutive reasoning and tool activity are grouped into a compact, expandable process row.

### Fixed

- Added an exact per-conversation supervisor lease that terminates its complete PRoot/app-server process tree on disconnect without killing unrelated Codex sessions.
- Recovered from Codex's explicit active-writer error by discarding only the stale local thread mapping and starting a new thread.
- Added a bounded Termux background-execution Doctor check and a direct Android settings action for OEM power restrictions.
- Authentication checks now inspect existing Provider/API-key configuration before running the bounded `codex login status` probe.
- Ubuntu setup installs and verifies `coreutils`, so timeout-bounded detection and authentication probes are available on fresh installations.
- WebSocket connection, request, message-size, and event-queue failures now terminate visibly instead of hanging or silently dropping protocol events.
- The message composer now follows Android IME insets, keeping typed content and the send/stop action visible above the keyboard.

### Verified

- On Android 16 / iQOO Neo8 with F-Droid Termux 0.119.0 beta: Doctor 8/8, existing DeepSeek Provider/model detection, history restore, a new native-chat response, and complete app-server tree cleanup after leaving the conversation.

## 0.1.3 - 2026-08-09

### Added

- Native Codex chat backed by the official app-server Thread/Turn/Item protocol, with history resume, Markdown, streaming deltas, stop, structured activity rows, and command/file/permission approvals.
- An authenticated single-client loopback bridge for the stable app-server stdio transport; its high-entropy token is never persisted by AgentDeck.
- A single recoverable setup coordinator with contextual actions, install stages, duplicate-install locking, and automatic rescans after returning to the app.

### Fixed

- The app now opens the conversation hub once Termux can be called; Codex login remains actionable but no longer hides the session entry behind Doctor.
- Conversation cards now enter the native transcript; Termux TUI is an explicit fallback action inside the conversation.
- Codex setup now preserves compatible CLI versions, updates versions below 0.147.0, and leaves `~/.codex` provider/auth data untouched.
- Native chat disables interactive startup update prompts; Codex updates stay inside AgentDeck's visible setup flow.
- Resuming a thread now interrupts persisted `inProgress` turns left by a disconnected bridge, so later messages do not wait forever.
- Per-conversation bridge leases now replace orphaned app-server processes before reconnecting, preventing concurrent rollout writers after Android process death.
- PRoot sessions use the official `externalSandbox` policy while retaining app-server approvals, avoiding incompatible nested bubblewrap execution.
- Ubuntu setup now repairs and verifies the apt package prerequisites needed before Codex detection or download.
- Codex downloads now try the normal IPv4/IPv6 path first and fall back to IPv4 when Android/proot dual-stack routing cannot reach GitHub.
- Large GitHub assets are downloaded in verified-length ranges; connection, stall, and transfer limits now fail predictably instead of appearing to hang indefinitely.
- Fresh Codex installation allows up to 30 minutes and explains the dependency check before any download begins.

### Design

- Applied the Thread/Turn/Item, mobile transcript, stable conversation mapping, inline approval, and terminal-fallback patterns found in Codex app-server, Happier, CloudCLI, cdesktop, and Termux.

## 0.1.2 - 2026-08-09

### Fixed

- Fresh installs now insert default Profiles through a dedicated seed path instead of the edit-only API.
- Initial data failures are logged without terminating the entire Android process.
- Verified cold start, repeat start, default data, `RUN_COMMAND`, Termux external calls, Ubuntu, and Codex detection on Android 16 / iQOO Neo8.

## 0.1.1 - 2026-08-08

### Added

- Verified Termux background result callbacks and a real environment Doctor.
- Strict, versioned recipe schema with dependency ordering and post-install verification.
- Codex CLI 0.147.0 standalone installs for arm64/x86_64 with fixed SHA-256 checksums.
- CLI adapter registry and complete card create/edit/disable/delete flows.
- Profile foreign-key integrity, one-time seed state, and safe v2-to-v3 migration.
- GitHub Actions verification, release checklist, security policy, and Apache-2.0 license.

### Changed

- AgentDeck no longer receives, stores, or transports API keys or OAuth tokens.
- Claude Code is shown as a planned adapter and cannot be installed or launched yet.
- Install and Doctor success now depend on actual Termux exit results instead of Intent delivery.

### Fixed

- Reopening a card now reuses the intended named Termux session.
- Dynamic launch values remain argv and are never interpolated into shell source.
- Editing a Profile no longer resets its creation time or unbinds referencing cards.
- Deleted user cards and Profiles no longer reappear after restart.

## 0.1.0 - 2026-08-04

- Initial Android skeleton and product design release.
