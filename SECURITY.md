# Security Policy

## Supported versions

Security fixes target the latest release and the current `main` branch.

## Reporting a vulnerability

GitHub private vulnerability reporting is the preferred channel, but it is not enabled yet. Until it is available, open a public issue containing only a request for a private maintainer contact channel. Do not include credentials, exploit details, reproduction steps, logs, or private user data in that issue.

Include the affected version, Android/Termux versions, reproduction steps, impact, and any suggested mitigation. Remove API keys, OAuth tokens, shell history, and personal paths from logs before attaching them.

## Credential boundary

AgentDeck does not accept, store, log, or pass API keys or OAuth tokens. Authentication belongs to each CLI. A report that finds credentials in AgentDeck preferences, Room tables, Intent extras, process arguments, or logs should be treated as a security issue.
