#!/usr/bin/env bash
# Runs the UI on this machine against a server started by deploy-remote.sh.
#
#   scripts/connect-ui.sh [-i identity-file] user@host
#
# It fetches the server's CA certificate over scp, asks the remote machine for a fresh 30-day
# token over ssh (the JWT secret never leaves it) and starts the UI in the foreground, connected
# to https://host:8443 (certificate and host name are verified against that CA). Ctrl-C stops the
# UI. Then open http://127.0.0.1:8642.
#
# Nothing in ~/.bobcrew/ui.properties is changed: the settings are passed to the UI as
# environment variables, which take precedence over that file.
#
# Settings (environment):
#   BOBCREW_USER     user name in the token (default: your local user name)
#   BOBCREW_UI_PORT  port of the UI (default 8642)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=remote-common.sh
source "$here/remote-common.sh"

usage() {
  echo "usage: scripts/connect-ui.sh [-i identity-file] user@host" >&2
}

parse_target "$@"
[[ ${#EXTRA[@]} -eq 0 ]] || die "unknown option: ${EXTRA[0]}"
trap close_ssh EXIT

host="${TARGET#*@}"
TOKEN_USER="${BOBCREW_USER:-$(id -un)}"
[[ "$TOKEN_USER" =~ ^[A-Za-z0-9._-]+$ ]] || die "unexpected characters in the user name '$TOKEN_USER'"

cd "$here/.."
ui_jar=$(ls bobcrew-ui/target/bobcrew-ui-*-SNAPSHOT.jar 2>/dev/null | head -n 1 || true)
[[ -n "$ui_jar" ]] || die "bobcrew-ui jar not found; run 'mvn -q install -DskipTests' first"

mkdir -p ~/.bobcrew
ca_file="$HOME/.bobcrew/remote-ca.crt"
rscp "$TARGET:$REMOTE_DIR/certs/ca.crt" "$ca_file" \
  || die "cannot fetch the CA certificate; has deploy-remote.sh been run?"

token=$(rssh bash -l -s -- "$REMOTE_DIR" "$TOKEN_USER" <<'EOF'
set -euo pipefail
cd "$1"
BOBCREW_JWT_SECRET_FILE=certs/jwt.secret java -jar bobcrew-server/target/bobcrew-server-*-SNAPSHOT.jar \
  issue-token --user "$2" --ttl 30d
EOF
) || die "cannot issue a token on the remote machine"
[[ "$token" =~ ^[A-Za-z0-9._-]+$ ]] || die "the remote machine did not return a token"
close_ssh

echo "UI: http://127.0.0.1:${BOBCREW_UI_PORT:-8642}  ->  https://$host:$REMOTE_PORT"
BOBCREW_UI_SERVER_URL="https://$host:$REMOTE_PORT" \
BOBCREW_UI_TOKEN="$token" \
BOBCREW_UI_CA_FILE="$ca_file" \
  java -jar "$ui_jar"
