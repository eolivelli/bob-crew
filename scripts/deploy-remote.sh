#!/usr/bin/env bash
# Copies bobcrew-server to a remote machine over ssh and (re)starts it there with nohup, using the
# wider settings of start-server-wide.sh (inherited environment, --auto-approve, --trust, MCP and
# subagents).
#
#   scripts/deploy-remote.sh [-i identity-file] user@host [--stop]
#
# The remote machine needs Java 21, openssl and the `bob` command. Run `mvn install -DskipTests`
# first.
#
# The server is reached directly at https://host:8443, so that port must be open in the remote
# machine's firewall. It listens on one address, never on all interfaces: the address you give as
# host if the machine has it on an interface, otherwise set BOBCREW_REMOTE_BIND to the interface
# address that the public one maps to (typical for cloud VMs behind 1:1 NAT). The server
# certificate is issued for the host name or IP you give, signed by a private CA that
# scripts/connect-ui.sh fetches and trusts.
#
# If BOB_API_KEY is set here, it is written to <remote dir>/server.env (mode 600) on the remote
# machine, never to a command line. Otherwise an existing server.env is kept.
#
# Files on the remote machine, under $BOBCREW_REMOTE_DIR (default ~/bobcrew): server.log,
# server.pid, server.env, certs/ (CA, server certificate, JWT secret), workspaces/.
#
# The agent inherits the environment of a remote *login* shell (~/.profile and the like), not of
# an interactive one (~/.bashrc), so put things such as GH_TOKEN where a login shell reads them.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=remote-common.sh
source "$here/remote-common.sh"

usage() {
  echo "usage: scripts/deploy-remote.sh [-i identity-file] user@host [--stop]" >&2
}

parse_target "$@"
host="${TARGET#*@}"
stop_only=false
for word in "${EXTRA[@]}"; do
  [[ "$word" == "--stop" ]] && stop_only=true || die "unknown option: $word"
done
trap close_ssh EXIT

# Stops the server named in server.pid, if it is running. Arguments: remote dir.
read -r -d '' STOP_SNIPPET <<'EOF' || true
cd "$1"
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
command -v bob >/dev/null || { echo "error: bob not found on the remote machine" >&2; exit 1; }
command -v java >/dev/null || { echo "error: java not found on the remote machine" >&2; exit 1; }
v=$(java -version 2>&1 | sed -n '1s/.*"\([0-9]*\).*/\1/p')
[ "${v:-0}" -ge 21 ] || { echo "error: Java 21 or newer is needed on the remote machine (found ${v:-none})" >&2; exit 1; }
EOF

# stop first: the new jar would otherwise replace the running one
rssh bash -l -s -- "$REMOTE_DIR" <<<"$STOP_SNIPPET"

echo "copying files"
rssh "mkdir -p '$REMOTE_DIR/bobcrew-server/target' '$REMOTE_DIR/scripts' '$REMOTE_DIR/workspaces'"
rscp "$jar" "$TARGET:$REMOTE_DIR/bobcrew-server/target/"
rscp scripts/gen-dev-certs.sh scripts/start-server-wide.sh "$TARGET:$REMOTE_DIR/scripts/"

if [[ -n "${BOB_API_KEY:-}" ]]; then
  # over stdin, so the key never shows up in a process list
  printf 'BOB_API_KEY=%q\n' "$BOB_API_KEY" | rssh "umask 077; cat > '$REMOTE_DIR/server.env'"
  echo "wrote BOB_API_KEY to $REMOTE_DIR/server.env"
elif ! rssh "test -f '$REMOTE_DIR/server.env'"; then
  echo "warning: BOB_API_KEY is not set here and the remote machine has no server.env;" \
       "Bob will answer 'Authentication required' unless it has another login" >&2
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

echo "done; next: scripts/connect-ui.sh${IDENTITY:+ -i $IDENTITY} $TARGET"
