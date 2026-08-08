# Changelog

All notable changes to AgentDeck are documented in this file.

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
