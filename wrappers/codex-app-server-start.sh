#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DISTRO="ubuntu"
INNER_CWD="/root/projects/default"
INSTANCE_KEY=""
WRAPPER_ROOT="/data/data/com.termux/files/home/.agentdeck/wrappers"
RUNTIME_ROOT="/data/data/com.termux/files/home/.agentdeck/runtime"
BRIDGE="$WRAPPER_ROOT/codex-app-server-bridge.py"

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
    --instance-key)
      (($# >= 2)) || fail "--instance-key requires a value"
      INSTANCE_KEY="$2"
      shift 2
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
done

[[ "$DISTRO" =~ ^[A-Za-z0-9._-]+$ && "$DISTRO" != -* ]] || fail "invalid distro name"
[[ "$INNER_CWD" == /* && "$INNER_CWD" != *$'\n'* && "$INNER_CWD" != *$'\r'* ]] || \
  fail "workspace must be an absolute path"
[[ "$INSTANCE_KEY" =~ ^[a-f0-9]{1,16}$ ]] || fail "invalid instance key"
command -v proot-distro >/dev/null 2>&1 || fail "proot-distro not found in Termux"
[[ -x "$BRIDGE" ]] || fail "app-server bridge is not installed"

umask 077
if [[ -L "$RUNTIME_ROOT" ]]; then
  fail "runtime path must not be a symbolic link"
fi
mkdir -p -- "$RUNTIME_ROOT"
chmod 700 -- "$RUNTIME_ROOT"
BOOTSTRAP="$RUNTIME_ROOT/bootstrap.$$.${RANDOM}"
LEASE="$RUNTIME_ROOT/bridge.${INSTANCE_KEY}.pid"
[[ ! -e "$BOOTSTRAP" && ! -L "$BOOTSTRAP" ]] || fail "runtime bootstrap collision"
[[ ! -L "$LEASE" ]] || fail "bridge lease must not be a symbolic link"
if [[ -e "$LEASE" && ! -f "$LEASE" ]]; then
  fail "bridge lease must be a regular file"
fi

if [[ -f "$LEASE" ]]; then
  existing_pid="$(cat -- "$LEASE" 2>/dev/null || true)"
  bridge_match=0
  key_match=0
  if [[ "$existing_pid" =~ ^[0-9]+$ && -r "/proc/$existing_pid/cmdline" ]]; then
    while IFS= read -r -d '' argument; do
      [[ "$argument" == "$BRIDGE" ]] && bridge_match=1
      [[ "$argument" == "$INSTANCE_KEY" ]] && key_match=1
    done < "/proc/$existing_pid/cmdline"
  fi
  if [[ "$bridge_match" -eq 1 && "$key_match" -eq 1 ]] && kill -0 "$existing_pid" 2>/dev/null; then
    kill "$existing_pid" 2>/dev/null || true
    for ((attempt = 0; attempt < 50; attempt++)); do
      kill -0 "$existing_pid" 2>/dev/null || break
      sleep 0.1
    done
    if kill -0 "$existing_pid" 2>/dev/null; then
      kill -KILL "$existing_pid" 2>/dev/null || true
    fi
  fi
  rm -f -- "$LEASE"
fi

nohup proot-distro login "$DISTRO" -- /usr/bin/env python3 "$BRIDGE" \
  --cwd "$INNER_CWD" \
  --bootstrap "$BOOTSTRAP" \
  --instance-key "$INSTANCE_KEY" \
  --lease "$LEASE" \
  </dev/null >/dev/null 2>&1 &
bridge_pid=$!

for ((attempt = 0; attempt < 200; attempt++)); do
  if [[ -s "$BOOTSTRAP" ]]; then
    result="$(cat -- "$BOOTSTRAP")"
    rm -f -- "$BOOTSTRAP"
    printf '%s\n' "$result"
    exit 0
  fi
  if ! kill -0 "$bridge_pid" 2>/dev/null; then
    wait "$bridge_pid" 2>/dev/null || true
    rm -f -- "$BOOTSTRAP"
    fail "app-server bridge exited during startup"
  fi
  sleep 0.1
done

kill "$bridge_pid" 2>/dev/null || true
wait "$bridge_pid" 2>/dev/null || true
rm -f -- "$BOOTSTRAP"
fail "app-server bridge startup timed out"
