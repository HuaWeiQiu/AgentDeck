#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DISTRO="ubuntu"
INNER_CWD="/root/projects/default"
INNER_BIN="codex"

fail() {
  echo "agentdeck: $*" >&2
  exit 64
}

while (($#)); do
  case "$1" in
    --distro)
      (($# >= 2)) || fail "--distro requires a value"
      DISTRO="$2"
      shift 2
      ;;
    --cwd)
      (($# >= 2)) || fail "--cwd requires a value"
      INNER_CWD="$2"
      shift 2
      ;;
    --bin)
      (($# >= 2)) || fail "--bin requires a value"
      INNER_BIN="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
done

[[ "$DISTRO" =~ ^[A-Za-z0-9._-]+$ && "$DISTRO" != -* ]] || fail "invalid distro name"
[[ "$INNER_CWD" == /* ]] || fail "workspace must be an absolute path"
[[ "$INNER_BIN" =~ ^[A-Za-z0-9._+/-]+$ ]] || fail "invalid CLI executable"

if ! command -v proot-distro >/dev/null 2>&1; then
  echo "agentdeck: proot-distro not found in Termux" >&2
  exit 127
fi

exec proot-distro login "$DISTRO" -- /usr/bin/env bash -c '
set -euo pipefail
inner_cwd="$1"
inner_bin="$2"
shift 2
mkdir -p -- "$inner_cwd"
cd -- "$inner_cwd"
if ! command -v "$inner_bin" >/dev/null 2>&1; then
  echo "agentdeck: $inner_bin not found inside distro" >&2
  exit 127
fi
exec "$inner_bin" "$@"
' agentdeck "$INNER_CWD" "$INNER_BIN" "$@"
