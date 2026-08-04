#!/data/data/com.termux/files/usr/bin/bash
# AgentDeck launch wrapper: Termux → proot-distro Ubuntu → codex TUI
# Installed to: $HOME/.agentdeck/wrappers/codex-ubuntu.sh
set -euo pipefail

DISTRO="${AGENTDECK_DISTRO:-ubuntu}"
INNER_CWD="${AGENTDECK_INNER_CWD:-/root/projects/default}"
INNER_BIN="${AGENTDECK_INNER_BIN:-codex}"

# Optional: load env file written by the app (mode 600)
ENV_FILE="${AGENTDECK_ENV_FILE:-$HOME/.agentdeck/run/codex.env}"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "$ENV_FILE"
  set +a
fi

build_inner() {
  cat <<EOF
set -euo pipefail
mkdir -p $(printf %q "$INNER_CWD")
cd $(printf %q "$INNER_CWD")
if ! command -v $(printf %q "$INNER_BIN") >/dev/null 2>&1; then
  echo "agentdeck: '$INNER_BIN' not found inside distro '$DISTRO'" >&2
  echo "Install Codex via AgentDeck Store, then retry." >&2
  exit 127
fi
exec $(printf %q "$INNER_BIN") "\$@"
EOF
}

if ! command -v proot-distro >/dev/null 2>&1; then
  echo "agentdeck: proot-distro not found in Termux" >&2
  exit 127
fi

exec proot-distro login "$DISTRO" -- bash -lc "$(build_inner)" -- "$@"
