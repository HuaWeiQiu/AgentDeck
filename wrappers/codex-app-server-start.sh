#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DISTRO="ubuntu"
INNER_CWD="/root/projects/default"
INSTANCE_KEY=""
MODE="start"
PROVIDER_ID=""
BASE_URL=""
MODEL=""
CREDENTIAL_REF=""
CREDENTIAL_BROKER_PORT=""
RUNTIME_ROOT="/data/data/com.termux/files/home/.agentdeck/runtime"
LEGACY_BRIDGE="/data/data/com.termux/files/home/.agentdeck/wrappers/codex-app-server-bridge.py"
CREDENTIAL_HELPER="/data/data/com.termux/files/home/.agentdeck/wrappers/codex-provider-token.py"
START_CONTRACT_VERSION=7

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
    --provider-id)
      (($# >= 2)) || fail "--provider-id requires a value"
      PROVIDER_ID="$2"
      shift 2
      ;;
    --base-url)
      (($# >= 2)) || fail "--base-url requires a value"
      BASE_URL="$2"
      shift 2
      ;;
    --model)
      (($# >= 2)) || fail "--model requires a value"
      MODEL="$2"
      shift 2
      ;;
    --credential-ref)
      (($# >= 2)) || fail "--credential-ref requires a value"
      CREDENTIAL_REF="$2"
      shift 2
      ;;
    --credential-broker-port)
      (($# >= 2)) || fail "--credential-broker-port requires a value"
      CREDENTIAL_BROKER_PORT="$2"
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
managed_values=("$PROVIDER_ID" "$BASE_URL" "$MODEL" "$CREDENTIAL_REF" "$CREDENTIAL_BROKER_PORT")
managed_count=0
for value in "${managed_values[@]}"; do
  [[ -z "$value" ]] || managed_count=$((managed_count + 1))
done
((managed_count == 0 || managed_count == ${#managed_values[@]})) || fail "managed provider options must be complete"
if ((managed_count > 0)); then
  [[ "$PROVIDER_ID" =~ ^agentdeck_[a-f0-9]{16}$ ]] || fail "invalid provider id"
  [[ "$BASE_URL" =~ ^https://[^[:space:]]+$ && ${#BASE_URL} -le 2048 ]] || fail "invalid base URL"
  [[ -n "$MODEL" && ${#MODEL} -le 512 && "$MODEL" != *$'\n'* && "$MODEL" != *$'\r'* ]] || fail "invalid model"
  [[ "$CREDENTIAL_REF" =~ ^[A-Za-z0-9._-]{1,80}$ ]] || fail "invalid credential reference"
  [[ "$CREDENTIAL_BROKER_PORT" =~ ^[0-9]+$ ]] &&
    ((CREDENTIAL_BROKER_PORT >= 1 && CREDENTIAL_BROKER_PORT <= 65535)) || fail "invalid credential broker port"
  [[ -x "$CREDENTIAL_HELPER" ]] || fail "provider credential helper is unavailable"
fi
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
CREDENTIAL_TOKEN_FILE="$RUNTIME_ROOT/app-server.${INSTANCE_KEY}.credential-token"

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
  rm -f -- "$LEASE" "$TOKEN_FILE" "$CREDENTIAL_TOKEN_FILE" "$STATE" "$START_LOG"
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
if ((managed_count > 0)); then
  CREDENTIAL_TOKEN="$(od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')"
  [[ "$CREDENTIAL_TOKEN" =~ ^[a-f0-9]{64}$ ]] || fail "failed to generate credential token"
  printf '%s\n' "$CREDENTIAL_TOKEN" > "$CREDENTIAL_TOKEN_FILE"
  chmod 600 -- "$CREDENTIAL_TOKEN_FILE"
fi
printf '%s\n' "starting" > "$STATE"

APP_SERVER_COMMAND='set -euo pipefail
inner_cwd="$1"
token_file="$2"
provider_id="$3"
base_url="$4"
model="$5"
credential_ref="$6"
credential_broker_port="$7"
credential_token_file="$8"
credential_helper="$9"
toml_quote() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf "\"%s\"" "$value"
}
mkdir -p -- "$inner_cwd"
cd -- "$inner_cwd"
codex_args=(-c check_for_update_on_startup=false)
if [[ -n "$provider_id" ]]; then
  auth_args="[$(toml_quote "$credential_helper"),$(toml_quote --port),$(toml_quote "$credential_broker_port"),$(toml_quote --token-file),$(toml_quote "$credential_token_file"),$(toml_quote --credential-ref),$(toml_quote "$credential_ref")]"
  codex_args+=(
    -c "model_provider=$(toml_quote "$provider_id")"
    -c "model=$(toml_quote "$model")"
    -c "model_providers.${provider_id}.name=$(toml_quote AgentDeck)"
    -c "model_providers.${provider_id}.base_url=$(toml_quote "$base_url")"
    -c "model_providers.${provider_id}.wire_api=$(toml_quote responses)"
    -c "model_providers.${provider_id}.auth.command=$(toml_quote /usr/bin/python3)"
    -c "model_providers.${provider_id}.auth.args=${auth_args}"
    -c "model_providers.${provider_id}.auth.timeout_ms=5000"
    -c "model_providers.${provider_id}.auth.refresh_interval_ms=0"
  )
fi
exec codex "${codex_args[@]}" app-server \
  --listen ws://127.0.0.1:0 \
  --ws-auth capability-token \
  --ws-token-file "$token_file"'

SUPERVISOR_COMMAND='set -euo pipefail
distro="$1"
inner_cwd="$2"
token_file="$3"
provider_id="$4"
base_url="$5"
model="$6"
credential_ref="$7"
credential_broker_port="$8"
credential_token_file="$9"
credential_helper="${10}"
app_server_command="${11}"
child_pid=0
cleanup() {
  if ((child_pid > 0)); then
    kill "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
}
trap cleanup TERM INT EXIT
proot-distro login "$distro" -- /usr/bin/env bash -c \
  "$app_server_command" "$0" "$inner_cwd" "$token_file" "$provider_id" "$base_url" \
  "$model" "$credential_ref" "$credential_broker_port" "$credential_token_file" \
  "$credential_helper" &
child_pid=$!
wait "$child_pid"'

nohup bash -c "$SUPERVISOR_COMMAND" "$MARKER" \
  "$DISTRO" "$INNER_CWD" "$TOKEN_FILE" "$PROVIDER_ID" "$BASE_URL" "$MODEL" \
  "$CREDENTIAL_REF" "$CREDENTIAL_BROKER_PORT" "$CREDENTIAL_TOKEN_FILE" \
  "$CREDENTIAL_HELPER" "$APP_SERVER_COMMAND" \
  </dev/null >/dev/null 2>"$START_LOG" &
server_pid=$!
printf '%s\n' "$server_pid" > "$LEASE"

for ((attempt = 0; attempt < 200; attempt++)); do
  listen_url="$(grep -Eo 'ws://127[.]0[.]0[.]1:[0-9]+' "$START_LOG" 2>/dev/null | tail -n 1 || true)"
  if [[ -n "$listen_url" ]]; then
    port="${listen_url##*:}"
    if [[ "$port" =~ ^[0-9]+$ ]] && ((port >= 1 && port <= 65535)); then
      printf 'ready:%s\n' "$port" > "$STATE"
      unlink -- "$START_LOG" 2>/dev/null || true
      if ((managed_count > 0)); then
        printf '{"port":%s,"token":"%s","credential_token":"%s","pid":%s}\n' \
          "$port" "$TOKEN" "$CREDENTIAL_TOKEN" "$server_pid"
      else
        printf '{"port":%s,"token":"%s","pid":%s}\n' "$port" "$TOKEN" "$server_pid"
      fi
      exit 0
    fi
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    wait "$server_pid" 2>/dev/null || true
    detail="$(tail -c 400 -- "$START_LOG" 2>/dev/null || true)"
    rm -f -- "$LEASE" "$TOKEN_FILE" "$CREDENTIAL_TOKEN_FILE" "$STATE"
    fail "Codex app-server exited during startup${detail:+: $detail}"
  fi
  sleep 0.1
done

terminate_tree "$server_pid"
detail="$(tail -c 400 -- "$START_LOG" 2>/dev/null || true)"
rm -f -- "$LEASE" "$TOKEN_FILE" "$CREDENTIAL_TOKEN_FILE" "$STATE"
fail "Codex app-server startup timed out${detail:+: $detail}"
