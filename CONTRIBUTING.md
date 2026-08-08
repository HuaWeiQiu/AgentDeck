# Contributing

AgentDeck is an Android launcher for real CLI sessions running in F-Droid Termux. It does not implement an agent loop or an in-app terminal.

## Development

Requirements: JDK 17 and Android SDK platform/build-tools 36.

```bash
./scripts/verify-release.sh
```

This command verifies packaged recipe/wrapper synchronization, runs JVM tests, builds the debug APK, and runs Android Lint.

## Change rules

- Keep secrets inside the CLI authentication flow. Never add API keys or OAuth tokens to Room, SharedPreferences, Intents, argv, shell source, or logs.
- Keep dynamic launch values as argv passed to a fixed executable.
- Add or update a strict packaged recipe and its tests when installation behavior changes.
- Add a `CliAdapter` before exposing a new CLI as available.
- Preserve Room migrations and user data; never use destructive fallback migration.
- Update `CHANGELOG.md` and the release checklist for user-visible behavior.

Pull requests should explain the behavioral change, test evidence, migration impact, and any real-device verification performed.
