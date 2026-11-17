#!/usr/bin/env bash
# Runs the UI on this machine against a server started by deploy-remote.sh.
#
#   scripts/connect-ui.sh [-i identity-file] user@host [--keycloak issuer [--keycloak-ca file]]
#
# It fetches the server's CA certificate over scp and starts the UI in the foreground, connected
# to https://host:8443 (certificate and host name are verified against that CA). Ctrl-C stops the
# UI. Then open http://127.0.0.1:8642.
#
# With --keycloak (the realm's issuer, as scripts/deploy-keycloak.sh prints it; the server must trust it too,
# deploy-remote.sh --keycloak), you log in in the browser with your Keycloak account. Otherwise it asks the
# remote machine for a fresh 30-day token over ssh (the JWT secret never leaves it), in the name of
# BOBCREW_USER. --keycloak-ca is Keycloak's CA certificate, if it is from a private CA (deploy-keycloak.sh leaves
# it in ~/.bobcrew/keycloak-ca.crt).
#
# Nothing in ~/.bobcrew/ui.properties is changed: the settings are passed to the UI as
# environment variables, which take precedence over that file.
#
# Settings (environment):
#   BOBCREW_USER     user name in the token, without --keycloak (default: your local user name)
#   BOBCREW_UI_PORT  port of the UI (default 8642)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=remote-common.sh
source "$here/remote-common.sh"

usage() {
  echo "usage: scripts/connect-ui.sh [-i identity-file] user@host [--keycloak issuer [--keycloak-ca file]]" >&2
}

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
[[ ${#EXTRA[@]} -eq 0 ]] || die "unknown option: ${EXTRA[0]}"
if [[ -n "$keycloak" ]]; then
  check_keycloak "$keycloak" "$keycloak_ca"
  [[ -z "$keycloak_ca" ]] || keycloak_ca="$(cd "$(dirname "$keycloak_ca")" && pwd)/$(basename "$keycloak_ca")"
elif [[ -n "$keycloak_ca" ]]; then
  die "--keycloak-ca goes with --keycloak"
fi
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

if [[ -n "$keycloak" ]]; then
  close_ssh
  echo "UI: http://127.0.0.1:${BOBCREW_UI_PORT:-8642}  ->  https://$host:$REMOTE_PORT, logging in with $keycloak"
  # no token, whatever ~/.bobcrew/ui.properties says: the login provides it
  BOBCREW_UI_SERVER_URL="https://$host:$REMOTE_PORT" \
  BOBCREW_UI_TOKEN="" \
  BOBCREW_UI_TOKEN_FILE="" \
  BOBCREW_UI_CA_FILE="$ca_file" \
  BOBCREW_UI_OIDC_ISSUER_URI="$keycloak" \
  BOBCREW_UI_OIDC_CA_FILE="$keycloak_ca" \
    java -jar "$ui_jar"
  exit
fi

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
