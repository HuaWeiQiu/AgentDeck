# Security Policy

## Supported versions

Security fixes target the latest release and the current `main` branch.

## Reporting a vulnerability

GitHub private vulnerability reporting is the preferred channel, but it is not enabled yet. Until it is available, open a public issue containing only a request for a private maintainer contact channel. Do not include credentials, exploit details, reproduction steps, logs, or private user data in that issue.

Include the affected version, Android/Termux versions, reproduction steps, impact, and any suggested mitigation. Remove API keys, OAuth tokens, shell history, and personal paths from logs before attaching them.

## Credential boundary

Existing CLI API keys, ChatGPT sessions, and OAuth tokens remain owned by each CLI; AgentDeck does not import or copy them. API keys that a user explicitly adds for a managed third-party Provider are encrypted with Android Keystore and stored only under `noBackupFilesDir`; Room stores an opaque credential reference. Codex receives a managed key on demand through the authenticated loopback `auth.command` broker described in ADR-0007.

A report that finds plaintext credentials in AgentDeck preferences, Room tables, backups, Intent extras, process arguments, generated shell source, Codex config, Termux persistent files, or logs should be treated as a security issue. Reports should also cover broker access without the instance capability token, cross-Provider credential substitution, or continued broker access after the chat instance closes.
