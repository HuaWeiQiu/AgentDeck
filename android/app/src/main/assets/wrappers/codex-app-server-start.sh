#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DISTRO="ubuntu"
INNER_CWD="/root/projects/default"
INSTANCE_KEY=""
MODE="start"
RUNTIME_ROOT="/data/data/com.termux/files/home/.agentdeck/runtime"
LEGACY_BRIDGE="/data/data/com.termux/files/home/.agentdeck/wrappers/codex-app-server-bridge.py"
START_CONTRACT_VERSION=6

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
    --stop)
      MODE="stop"
      shift
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

umask 077
if [[ -L "$RUNTIME_ROOT" ]]; then
  fail "runtime path must not be a symbolic link"
fi
mkdir -p -- "$RUNTIME_ROOT"
chmod 700 -- "$RUNTIME_ROOT"

MARKER="agentdeck-app-server-$INSTANCE_KEY"
LEASE="$RUNTIME_ROOT/app-server.${INSTANCE_KEY}.pid"
TOKEN_FILE="$RUNTIME_ROOT/app-server.${INSTANCE_KEY}.token"
STATE="$RUNTIME_ROOT/app-server.${INSTANCE_KEY}.state"
START_LOG="$RUNTIME_ROOT/app-server.${INSTANCE_KEY}.log"

read_owned_pid() {
  local lease="$1"
  local marker="$2"
  local pid=""
  local marker_match=0
  [[ -f "$lease" && ! -L "$lease" ]] || return 1
  pid="$(cat -- "$lease" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" ]] || return 1
  while IFS= read -r -d '' argument; do
    [[ "$argument" == "$marker" ]] && marker_match=1
  done < "/proc/$pid/cmdline"
  [[ "$marker_match" -eq 1 ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  printf '%s\n' "$pid"
}

terminate_tree() {
  local root_pid="$1"
  local current_pid=""
  local child_pid=""
  local parent_pid=""
  local status_file=""
  local tree_alive=0
  local -a tree=("$root_pid")
  local index=0
  local reverse_index=0

  while ((index < ${#tree[@]})); do
    current_pid="${tree[$index]}"
    for status_file in /proc/[0-9]*/status; do
      [[ -r "$status_file" ]] || continue
      child_pid="${status_file#/proc/}"
      child_pid="${child_pid%/status}"
      [[ "$child_pid" =~ ^[0-9]+$ ]] || continue
      parent_pid="$(sed -n 's/^PPid:[[:space:]]*//p' "$status_file" 2>/dev/null || true)"
      [[ "$parent_pid" == "$current_pid" ]] || continue
      tree+=("$child_pid")
    done
    index=$((index + 1))
  done

  for ((reverse_index = ${#tree[@]} - 1; reverse_index >= 0; reverse_index--)); do
    kill "${tree[$reverse_index]}" 2>/dev/null || true
  done
  for ((attempt = 0; attempt < 10; attempt++)); do
    tree_alive=0
    for current_pid in "${tree[@]}"; do
      if kill -0 "$current_pid" 2>/dev/null; then
        tree_alive=1
      fi
    done
    ((tree_alive == 0)) && return 0
    sleep 0.1
  done
  for ((reverse_index = ${#tree[@]} - 1; reverse_index >= 0; reverse_index--)); do
    kill -KILL "${tree[$reverse_index]}" 2>/dev/null || true
  done
}

marked_server_exists() {
  local cmdline=""
  local argument=""
  for cmdline in /proc/[0-9]*/cmdline; do
    [[ -r "$cmdline" ]] || continue
    while IFS= read -r -d '' argument; do
      [[ "$argument" == "$MARKER" ]] && return 0
    done < "$cmdline" 2>/dev/null
  done
  return 1
}

stop_marked_servers() {
  local skipped_pid="${1:-}"
  local cmdline=""
  local pid=""
  local marker_match=0
  for cmdline in /proc/[0-9]*/cmdline; do
    [[ -r "$cmdline" ]] || continue
    pid="${cmdline#/proc/}"
    pid="${pid%/cmdline}"
    [[ "$pid" =~ ^[0-9]+$ && "$pid" != "$skipped_pid" ]] || continue
    marker_match=0
    while IFS= read -r -d '' argument; do
      if [[ "$argument" == "$MARKER" ]]; then
        marker_match=1
      fi
    done < "$cmdline" 2>/dev/null
    [[ "$marker_match" -eq 1 ]] || continue
    terminate_tree "$pid"
  done
}

stop_owned_server() {
  local pid=""
  pid="$(read_owned_pid "$LEASE" "$MARKER" || true)"
  [[ -z "$pid" ]] || terminate_tree "$pid"
  stop_marked_servers "$pid"
  rm -f -- "$LEASE" "$TOKEN_FILE" "$STATE" "$START_LOG"
}

stop_legacy_bridge() {
  local legacy_lease="$RUNTIME_ROOT/bridge.${INSTANCE_KEY}.pid"
  local legacy_state="$RUNTIME_ROOT/bridge.${INSTANCE_KEY}.state"
  local legacy_pid=""
  legacy_pid="$(read_owned_pid "$legacy_lease" "$LEGACY_BRIDGE" || true)"
  [[ -z "$legacy_pid" ]] || terminate_tree "$legacy_pid"
  rm -f -- "$legacy_lease" "$legacy_state"
}

stop_owned_server
stop_legacy_bridge
if marked_server_exists; then
  fail "previous AgentDeck app-server did not stop"
fi
if [[ "$MODE" == "stop" ]]; then
  exit 0
fi

TOKEN="$(od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')"
[[ "$TOKEN" =~ ^[a-f0-9]{64}$ ]] || fail "failed to generate websocket token"
printf '%s\n' "$TOKEN" > "$TOKEN_FILE"
chmod 600 -- "$TOKEN_FILE"
printf '%s\n' "starting" > "$STATE"

APP_SERVER_COMMAND='set -euo pipefail
inner_cwd="$1"
token_file="$2"
mkdir -p -- "$inner_cwd"
cd -- "$inner_cwd"
exec codex -c check_for_update_on_startup=false app-server \
  --listen ws://127.0.0.1:0 \
  --ws-auth capability-token \
  --ws-token-file "$token_file"'

SUPERVISOR_COMMAND='set -euo pipefail
distro="$1"
inner_cwd="$2"
token_file="$3"
app_server_command="$4"
child_pid=0
cleanup() {
  if ((child_pid > 0)); then
    kill "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
}
trap cleanup TERM INT EXIT
proot-distro login "$distro" -- /usr/bin/env bash -c \
  "$app_server_command" "$0" "$inner_cwd" "$token_file" &
child_pid=$!
wait "$child_pid"'

nohup bash -c "$SUPERVISOR_COMMAND" "$MARKER" \
  "$DISTRO" "$INNER_CWD" "$TOKEN_FILE" "$APP_SERVER_COMMAND" \
  </dev/null >/dev/null 2>"$START_LOG" &
server_pid=$!
printf '%s\n' "$server_pid" > "$LEASE"

for ((attempt = 0; attempt < 200; attempt++)); do
  listen_url="$(grep -Eo 'ws://127[.]0[.]0[.]1:[0-9]+' "$START_LOG" 2>/dev/null | tail -n 1 || true)"
  if [[ -n "$listen_url" ]]; then
    port="${listen_url##*:}"
    if [[ "$port" =~ ^[0-9]+$ ]] && ((port >= 1 && port <= 65535)); then
      printf 'ready:%s\n' "$port" > "$STATE"
      printf '{"port":%s,"token":"%s","pid":%s}\n' "$port" "$TOKEN" "$server_pid"
      exit 0
    fi
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    wait "$server_pid" 2>/dev/null || true
    detail="$(tail -c 400 -- "$START_LOG" 2>/dev/null || true)"
    rm -f -- "$LEASE" "$TOKEN_FILE" "$STATE"
    fail "Codex app-server exited during startup${detail:+: $detail}"
  fi
  sleep 0.1
done

terminate_tree "$server_pid"
detail="$(tail -c 400 -- "$START_LOG" 2>/dev/null || true)"
rm -f -- "$LEASE" "$TOKEN_FILE" "$STATE"
fail "Codex app-server startup timed out${detail:+: $detail}"
