# Contributing

AgentDeck is an Android client for real local agent CLI sessions. The 0.1.x compatibility runtime uses F-Droid Termux; the target runtime is an app-private embedded Linux environment behind the `AgentRuntime` boundary. AgentDeck does not implement its own agent loop.

## Development

Requirements: JDK 17 and Android SDK platform/build-tools 36.

```bash
./scripts/verify-release.sh
```

This command verifies packaged recipe/wrapper synchronization, runs JVM tests, builds the R8-optimized, test-signed Beta APK, and runs Android Lint.

## Change rules

- Keep existing CLI/OAuth secrets inside the CLI authentication flow. Managed third-party API keys may only use the ADR-0007 Keystore vault and authenticated `auth.command` broker; never add secrets to Room, SharedPreferences, Intents, argv, shell source, Codex config, Termux files, or logs.
- Keep dynamic launch values as argv passed to a fixed executable.
- Keep UI and domain code behind `AgentRuntime`; do not add new direct `TermuxGateway` dependencies outside the compatibility backend.
- Keep standard-mode copy free of Runtime implementation terms. Put raw commands, ports, process data, and protocol logs behind advanced or developer surfaces.
- Developer diagnostics must be bounded and redact credentials, capability tokens, auth files, and user message content by default.
- Add or update a strict packaged recipe and its tests when installation behavior changes.
- Add a `CliAdapter` before exposing a new CLI as available.
- Preserve Room migrations and user data; never use destructive fallback migration.
- Update `CHANGELOG.md` and the release checklist for user-visible behavior.

Pull requests should explain the behavioral change, test evidence, migration impact, and any real-device verification performed.
