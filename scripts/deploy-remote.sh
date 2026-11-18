#!/usr/bin/env bash
# Copies bobcrew-server to a remote machine over ssh and (re)starts it there with nohup, using the
# wider settings of start-server-wide.sh (--auto-approve, --trust, MCP and subagents).
#
#   scripts/deploy-remote.sh [-i identity-file] user@host [--keycloak issuer [--keycloak-ca file] | --no-keycloak]
#   scripts/deploy-remote.sh [-i identity-file] user@host --stop
#
# With --keycloak, the server also accepts tokens from that Keycloak realm (scripts/deploy-keycloak.sh prints the
# issuer, and leaves its CA in ~/.bobcrew/keycloak-ca.crt), so users log in instead of being given tokens; roles
# in the realm decide who may use the server, who is an admin and who may ask for which capability. The CA (needed
# when Keycloak's certificate is from a private CA) is copied to the machine. The setting is kept in
# <remote dir>/oidc.env for later deployments; --no-keycloak removes it. Tokens from issue-token keep working.
# The server must reach Keycloak at the issuer's address.
#
# The remote machine needs Java 21, openssl and Docker, and the remote user must be able to run
# docker (docker group). Sessions run in containers from images that must already be on the remote
# Docker: copy them with scripts/push-images.sh (the server never pulls). Run
# `mvn install -DskipTests` first.
#
# The server is reached directly at https://host:8443, so that port must be open in the remote
# machine's firewall. It listens on one address, never on all interfaces: the address you give as
# host if the machine has it on an interface, otherwise set BOBCREW_REMOTE_BIND to the interface
# address that the public one maps to (typical for cloud VMs behind 1:1 NAT). The server
# certificate is issued for the host name or IP you give, signed by a private CA that
# scripts/connect-ui.sh fetches and trusts.
#
# BOB_API_KEY and BOBCREW_ALLOWED_CAPABILITIES, when set here, are written to <remote dir>/server.env (mode 600)
# on the remote machine, over ssh's standard input, never a command line; they replace earlier values of the same
# names, and the file's other lines are kept. Unset, what the file has stays.
#
# If the remote's DOCKER-USER iptables chain drops traffic and has no rule for Docker's bridges, and the remote
# user may sudo without a password, RETURN rules for br-+ and docker0 are inserted at its top so that sessions
# can reach the network. Rules that block sessions' traffic (cloud metadata, private ranges) must be inserted
# above them, after deploying.
#
# Files on the remote machine, under $BOBCREW_REMOTE_DIR (default ~/bobcrew): server.log,
# server.pid, server.env, oidc.env, certs/ (CA, server certificate, JWT secret, Keycloak's CA).
#
# The agents do not see the remote machine's environment: variables such as GH_TOKEN come from the
# machine running the UI, as listed by the session's role.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=remote-common.sh
source "$here/remote-common.sh"

usage() {
  echo "usage: scripts/deploy-remote.sh [-i identity-file] user@host [--keycloak issuer [--keycloak-ca file] | --no-keycloak | --stop]" >&2
}

# the options with values, before parse_target
args=()
keycloak=""
keycloak_ca=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keycloak|--keycloak-ca)
      [[ $# -ge 2 ]] || die "$1 needs a value"
      if [[ "$1" == --keycloak ]]; then keycloak="$2"; else keycloak_ca="$2"; fi
      shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
parse_target "${args[@]}"
host="${TARGET#*@}"
stop_only=false
no_keycloak=false
for word in "${EXTRA[@]}"; do
  case "$word" in
    --stop) stop_only=true ;;
    --no-keycloak) no_keycloak=true ;;
    *) die "unknown option: $word" ;;
  esac
done
if [[ -n "$keycloak" ]]; then
  $no_keycloak && die "--keycloak and --no-keycloak together?"
  check_keycloak "$keycloak" "$keycloak_ca"
elif [[ -n "$keycloak_ca" ]]; then
  die "--keycloak-ca goes with --keycloak"
fi
trap close_ssh EXIT

# Stops the server named in server.pid, if it is running. Arguments: remote dir.
read -r -d '' STOP_SNIPPET <<'EOF' || true
cd "$1" 2>/dev/null || exit 0   # first deployment: nothing to stop
if [ -f server.pid ]; then
  pid=$(cat server.pid)
  if kill -0 "$pid" 2>/dev/null && tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q bobcrew-server; then
    kill "$pid"
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$pid" 2>/dev/null; then echo "server $pid did not stop" >&2; exit 1; fi
    echo "stopped server $pid"
  fi
  rm -f server.pid
fi
EOF

if $stop_only; then
  rssh bash -l -s -- "$REMOTE_DIR" <<<"$STOP_SNIPPET"
  exit 0
fi

cd "$here/.."
jar=$(ls bobcrew-server/target/bobcrew-server-*-SNAPSHOT.jar 2>/dev/null | head -n 1 || true)
[[ -n "$jar" ]] || die "bobcrew-server jar not found; run 'mvn -q install -DskipTests' first"

echo "checking $TARGET"
rssh bash -l -s <<'EOF'
command -v openssl >/dev/null || { echo "error: openssl not found on the remote machine" >&2; exit 1; }
command -v docker >/dev/null || { echo "error: docker not found on the remote machine" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "error: this user cannot use docker on the remote machine (docker group?)" >&2; exit 1; }
docker image inspect bobcrew/bob-base:latest >/dev/null 2>&1 \
  || echo "warning: bobcrew/bob-base:latest is not on the remote Docker; copy it with scripts/push-images.sh" >&2
command -v java >/dev/null || { echo "error: java not found on the remote machine" >&2; exit 1; }
v=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = \([0-9]*\).*/\1/p')
[ "${v:-0}" -ge 21 ] || { echo "error: Java 21 or newer is needed on the remote machine (found ${v:-none})" >&2; exit 1; }

# Allow container bridge forwarding in DOCKER-USER if a restrictive firewall (e.g. ctool) drops it
if sudo -n iptables -S DOCKER-USER 2>/dev/null | grep -q -- "-j DROP"; then
  if ! sudo -n iptables -S DOCKER-USER 2>/dev/null | grep -q -- "-i br-"; then
    sudo -n iptables -I DOCKER-USER 1 -i br-+ -j RETURN 2>/dev/null || true
    sudo -n iptables -I DOCKER-USER 1 -i docker0 -j RETURN 2>/dev/null || true
  fi
fi
EOF

# stop first: the new jar would otherwise replace the running one
rssh bash -l -s -- "$REMOTE_DIR" <<<"$STOP_SNIPPET"

echo "copying files"
rssh "mkdir -p '$REMOTE_DIR/bobcrew-server/target' '$REMOTE_DIR/scripts'"
rscp "$jar" "$TARGET:$REMOTE_DIR/bobcrew-server/target/"
rscp scripts/gen-dev-certs.sh scripts/start-server-wide.sh "$TARGET:$REMOTE_DIR/scripts/"

remote_env_updates=""
if [[ -n "${BOB_API_KEY:-}" ]]; then
  remote_env_updates+="BOB_API_KEY=$BOB_API_KEY"$'\n'
fi
if [[ -n "${BOBCREW_ALLOWED_CAPABILITIES:-}" ]]; then
  remote_env_updates+="BOBCREW_ALLOWED_CAPABILITIES=$BOBCREW_ALLOWED_CAPABILITIES"$'\n'
fi

if [[ -n "$remote_env_updates" ]]; then
  # over stdin, so secrets never show up in a process list
  printf '%s' "$remote_env_updates" | rssh bash -s -- "$REMOTE_DIR" <<'EOF'
set -euo pipefail
dir="$1"
mkdir -p "$dir"
touch "$dir/server.env"
chmod 600 "$dir/server.env"
while IFS= read -r line || [ -n "$line" ]; do
  [[ -z "$line" || "$line" =~ ^# ]] && continue
  key="${line%%=*}"
  if grep -q "^${key}=" "$dir/server.env"; then
    tmp=$(mktemp)
    chmod 600 "$tmp"
    grep -v "^${key}=" "$dir/server.env" > "$tmp" || true
    echo "$line" >> "$tmp"
    mv "$tmp" "$dir/server.env"
  else
    echo "$line" >> "$dir/server.env"
  fi
done
EOF
  echo "updated $REMOTE_DIR/server.env"
elif ! rssh "test -f '$REMOTE_DIR/server.env'"; then
  echo "warning: BOB_API_KEY is not set here and the remote machine has no server.env;" \
       "Bob will answer 'Authentication required' unless it has another login" >&2
fi

if [[ -n "$keycloak" ]]; then
  rssh "mkdir -p '$REMOTE_DIR/certs'"
  ca_setting=""
  if [[ -n "$keycloak_ca" ]]; then
    rscp "$keycloak_ca" "$TARGET:$REMOTE_DIR/certs/keycloak-ca.crt"
    ca_setting="certs/keycloak-ca.crt"
  fi
  printf 'BOBCREW_OIDC_ISSUER_URI=%q\nBOBCREW_OIDC_CA_FILE=%q\n' "$keycloak" "$ca_setting" \
    | rssh "umask 077; cat > '$REMOTE_DIR/oidc.env'"
  echo "the server trusts logins from $keycloak"
elif $no_keycloak; then
  rssh "rm -f '$REMOTE_DIR/oidc.env' '$REMOTE_DIR/certs/keycloak-ca.crt'"
  echo "the server no longer trusts a Keycloak realm"
fi

bind="${BOBCREW_REMOTE_BIND:-$host}"
[[ "$bind" =~ ^[A-Za-z0-9.-]+$ ]] || die "BOBCREW_REMOTE_BIND must be an IPv4 address or a host name"

echo "starting the server"
rssh bash -l -s -- "$REMOTE_DIR" "$REMOTE_PORT" "$host" "$bind" <<'EOF'
set -euo pipefail
cd "$1"
chmod +x scripts/*.sh

# the one address to listen on: must belong to this machine, and is never "all interfaces"
addr="$4"
if ! [[ "$addr" =~ ^[0-9.]+$ ]]; then addr=$(getent ahostsv4 "$addr" | awk 'NR==1 {print $1}'); fi
if [ -z "$addr" ] || ! ip -o addr show | awk '{print $4}' | cut -d/ -f1 | grep -qxF "$addr"; then
  echo "error: $4 is not an address of this machine (a cloud VM often has only its private address" >&2
  echo "on the interface); rerun with BOBCREW_REMOTE_BIND=<one of the addresses below>:" >&2
  ip -o addr show | awk '{print "  " $4}' | cut -d/ -f1 >&2
  exit 1
fi

# the certificate must name the host the UI connects to; the CA is kept, so UIs that trust it
# keep working
if [ -f certs/server.crt ] && ! openssl x509 -in certs/server.crt -noout -ext subjectAltName 2>/dev/null \
    | grep -qE "(DNS:|IP Address:)$3(,|\$)"; then
  rm -f certs/server.crt certs/server.key
fi
scripts/gen-dev-certs.sh certs "$3"

if [ -f server.env ]; then set -a; . ./server.env; set +a; fi
if [ -f oidc.env ]; then set -a; . ./oidc.env; set +a; fi
export SERVER_ADDRESS="$addr" BOBCREW_PORT="$2"
nohup scripts/start-server-wide.sh > server.log 2>&1 < /dev/null &
echo $! > server.pid
for _ in $(seq 1 40); do
  if grep -q "Started BobCrewServerApplication" server.log; then echo "server started (pid $(cat server.pid))"; exit 0; fi
  kill -0 "$(cat server.pid)" 2>/dev/null || break
  sleep 1
done
echo "the server did not start; last log lines:" >&2
tail -n 20 server.log >&2
exit 1
EOF

if [[ -n "$keycloak" ]] || rssh "test -f '$REMOTE_DIR/oidc.env'"; then
  echo "done; next: scripts/connect-ui.sh${IDENTITY:+ -i $IDENTITY} $TARGET --keycloak <issuer> [--keycloak-ca <file>]"
else
  echo "done; next: scripts/connect-ui.sh${IDENTITY:+ -i $IDENTITY} $TARGET"
fi
