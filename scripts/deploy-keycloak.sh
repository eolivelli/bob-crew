#!/usr/bin/env bash
# Runs Keycloak, the identity provider for bob-crew, on a remote machine's Docker over ssh, with the project's
# realm (keycloak/bobcrew-realm.json). The machine can be one that also runs a bob-crew server, or a machine of
# its own; in production Keycloak usually is a service of its own already, and you only import the realm there.
#
#   scripts/deploy-keycloak.sh [-i identity-file] user@host [--stop]
#
# Keycloak is reached at https://host:8543 by everyone: the users' browsers (to log in), their UIs and the
# bob-crew servers (for its keys). That port must be open in the machine's firewall, and the realm's issuer,
# https://host:8543/realms/bobcrew, is what servers and UIs are configured with (deploy-remote.sh --keycloak,
# connect-ui.sh --keycloak). Keycloak listens on one address of the machine: the host you give, or
# BOBCREW_KEYCLOAK_BIND (as BOBCREW_REMOTE_BIND for deploy-remote.sh, for machines behind 1:1 NAT).
#
# What runs: the container bobcrew-keycloak (quay.io/keycloak/keycloak, pinned below), restarted with Docker,
# in production mode with HTTPS only, and its embedded database ("dev-file") on the Docker volume
# bobcrew-keycloak-data. Keycloak does not support that database for production: back the volume up, and move to
# PostgreSQL before relying on it. The realm is imported at the first start only: later changes to the file are
# not applied to a realm that exists, so users and settings made in Keycloak survive redeploys.
#
# Its certificate is signed by a private CA made on the machine (scripts/gen-dev-certs.sh), unless
# BOBCREW_KEYCLOAK_CERTS names a local directory with server.crt, server.key and ca.crt to use instead (a
# certificate from a public CA: then ca.crt may be empty and nobody needs it). The CA is copied here, to
# ~/.bobcrew/keycloak-ca.crt, for the UI and the servers.
#
# Keycloak's administrator is "admin", with a password generated on the machine into <dir>/admin.password
# (mode 600), never printed and never sent here; scripts/keycloak-user.sh uses it there to manage users. The
# admin console is at https://host:8543/admin.
#
# --stop removes the container; the volume, and with it the realm and its users, stays.
#
# Settings (environment):
#   BOBCREW_KEYCLOAK_PORT   port on the machine (default 8543)
#   BOBCREW_KEYCLOAK_DIR    directory on the machine, relative to its home (default bobcrew-keycloak)
#   BOBCREW_KEYCLOAK_BIND   address to listen on, if not the host's own
#   BOBCREW_KEYCLOAK_CERTS  local directory with the certificate to use, see above
#
# The remote user must be able to run docker; Keycloak's image is pulled there if missing (or copy it with
# scripts/push-images.sh).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=remote-common.sh
source "$here/remote-common.sh"

# Pinned: the realm file is tested with this version (bobcrew-test-support's TestKeycloak).
KEYCLOAK_IMAGE="quay.io/keycloak/keycloak:26.4.7"
KC_PORT="${BOBCREW_KEYCLOAK_PORT:-8543}"
KC_DIR="${BOBCREW_KEYCLOAK_DIR:-bobcrew-keycloak}"

usage() {
  echo "usage: scripts/deploy-keycloak.sh [-i identity-file] user@host [--stop]" >&2
}

parse_target "$@"
host="${TARGET#*@}"
stop_only=false
for word in "${EXTRA[@]}"; do
  [[ "$word" == "--stop" ]] && stop_only=true || die "unknown option: $word"
done
[[ "$KC_PORT" =~ ^[0-9]+$ ]] || die "BOBCREW_KEYCLOAK_PORT must be a number"
[[ "$KC_DIR" =~ ^[A-Za-z0-9._/-]+$ && "$KC_DIR" != /* && "$KC_DIR" != *..* ]] \
  || die "BOBCREW_KEYCLOAK_DIR must be a plain directory under the remote home"
trap close_ssh EXIT

if $stop_only; then
  rssh 'docker rm -f bobcrew-keycloak >/dev/null 2>&1 && echo "stopped Keycloak (the volume bobcrew-keycloak-data is kept)" || echo "Keycloak was not running"'
  exit 0
fi

cd "$here/.."
realm=keycloak/bobcrew-realm.json
[[ -f "$realm" ]] || die "$realm not found"
if [[ -n "${BOBCREW_KEYCLOAK_CERTS:-}" ]]; then
  for file in server.crt server.key ca.crt; do
    [[ -f "$BOBCREW_KEYCLOAK_CERTS/$file" ]] || die "$BOBCREW_KEYCLOAK_CERTS/$file not found"
  done
fi
bind="${BOBCREW_KEYCLOAK_BIND:-$host}"
[[ "$bind" =~ ^[A-Za-z0-9.-]+$ ]] || die "BOBCREW_KEYCLOAK_BIND must be an IPv4 address or a host name"

echo "checking $TARGET"
rssh bash -l -s -- "$KEYCLOAK_IMAGE" <<'EOF'
command -v openssl >/dev/null || { echo "error: openssl not found on the remote machine" >&2; exit 1; }
command -v docker >/dev/null || { echo "error: docker not found on the remote machine" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "error: this user cannot use docker on the remote machine (docker group?)" >&2; exit 1; }
if ! docker image inspect "$1" >/dev/null 2>&1; then
  echo "pulling $1"
  docker pull -q "$1" >/dev/null \
    || { echo "error: cannot pull $1 there; copy it with scripts/push-images.sh <target> $1" >&2; exit 1; }
fi
EOF

echo "copying files"
rssh "mkdir -p '$KC_DIR/scripts' && chmod 700 '$KC_DIR'"
rscp scripts/gen-dev-certs.sh "$TARGET:$KC_DIR/scripts/"
rscp "$realm" "$TARGET:$KC_DIR/bobcrew-realm.json"
if [[ -n "${BOBCREW_KEYCLOAK_CERTS:-}" ]]; then
  rssh "mkdir -p '$KC_DIR/certs' && chmod 700 '$KC_DIR/certs'"
  rscp "$BOBCREW_KEYCLOAK_CERTS/server.crt" "$BOBCREW_KEYCLOAK_CERTS/server.key" "$BOBCREW_KEYCLOAK_CERTS/ca.crt" \
    "$TARGET:$KC_DIR/certs/"
  rssh "touch '$KC_DIR/certs/provided'"
fi

echo "starting Keycloak"
rssh bash -l -s -- "$KC_DIR" "$KEYCLOAK_IMAGE" "$host" "$bind" "$KC_PORT" <<'EOF'
set -euo pipefail
cd "$1"
image="$2" host="$3" port="$5"
chmod +x scripts/*.sh

# the one address to listen on: must belong to this machine, and is never "all interfaces"
addr="$4"
if ! [[ "$addr" =~ ^[0-9.]+$ ]]; then addr=$(getent ahostsv4 "$addr" | awk 'NR==1 {print $1}'); fi
if [ -z "$addr" ] || ! ip -o addr show | awk '{print $4}' | cut -d/ -f1 | grep -qxF "$addr"; then
  echo "error: $4 is not an address of this machine; rerun with BOBCREW_KEYCLOAK_BIND=<one of these>:" >&2
  ip -o addr show | awk '{print "  " $4}' | cut -d/ -f1 >&2
  exit 1
fi

# the certificate must name the host everyone connects to; the CA is kept, so those who trust it keep working
if [ ! -f certs/provided ]; then
  if [ -f certs/server.crt ] && ! openssl x509 -in certs/server.crt -noout -ext subjectAltName 2>/dev/null \
      | grep -qE "(DNS:|IP Address:)$host(,|\$)"; then
    rm -f certs/server.crt certs/server.key
  fi
  BOBCREW_JWT_SECRET=false scripts/gen-dev-certs.sh certs "$host"
fi

umask 077
if [ ! -s admin.password ]; then
  openssl rand -base64 30 | tr -d '/+=\n' > admin.password
fi
# read by the docker command here, so the password is on no command line; used only while no admin exists
printf 'KC_BOOTSTRAP_ADMIN_USERNAME=admin\nKC_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$(cat admin.password)" > keycloak.env

docker rm -f bobcrew-keycloak >/dev/null 2>&1 || true
docker create --name bobcrew-keycloak --restart unless-stopped \
  --label bobcrew.keycloak=true \
  -p "$addr:$port:8443" \
  -v bobcrew-keycloak-data:/opt/keycloak/data \
  --env-file keycloak.env \
  "$image" start \
  --db=dev-file \
  --hostname="https://$host:$port" \
  --http-enabled=false \
  --https-certificate-file=/opt/keycloak/conf/bobcrew-tls.crt \
  --https-certificate-key-file=/opt/keycloak/conf/bobcrew-tls.key \
  --import-realm \
  --health-enabled=true >/dev/null

# copied in rather than mounted: owned by Keycloak's user inside the container, whatever this user's id is.
# The realm goes to the data volume's import directory, read at startup.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/conf" "$tmp/data/import"
cp certs/server.crt "$tmp/conf/bobcrew-tls.crt"
cp certs/server.key "$tmp/conf/bobcrew-tls.key"
cp certs/ca.crt "$tmp/conf/bobcrew-ca.crt"
cp bobcrew-realm.json "$tmp/data/import/bobcrew-realm.json"
tar -C "$tmp/conf" --owner=1000 --group=0 --numeric-owner --mode=u=rw,g=,o= \
  -cf - bobcrew-tls.crt bobcrew-tls.key bobcrew-ca.crt \
  | docker cp -a - bobcrew-keycloak:/opt/keycloak/conf/
tar -C "$tmp/data" --owner=1000 --group=0 --numeric-owner --mode=u=rwX,g=rX,o= -cf - import \
  | docker cp -a - bobcrew-keycloak:/opt/keycloak/data/
docker start bobcrew-keycloak >/dev/null

for _ in $(seq 1 120); do
  if docker logs bobcrew-keycloak 2>&1 | grep -q "started in"; then
    echo "Keycloak started"
    exit 0
  fi
  [ "$(docker inspect -f '{{.State.Running}}' bobcrew-keycloak)" = true ] || break
  sleep 1
done
echo "Keycloak did not start; last log lines:" >&2
docker logs --tail 30 bobcrew-keycloak >&2
exit 1
EOF

mkdir -p ~/.bobcrew
ca_file="$HOME/.bobcrew/keycloak-ca.crt"
rscp "$TARGET:$KC_DIR/certs/ca.crt" "$ca_file"
issuer="https://$host:$KC_PORT/realms/bobcrew"
echo
echo "Keycloak is at https://$host:$KC_PORT (admin console: https://$host:$KC_PORT/admin, user admin,"
echo "password in ~/$KC_DIR/admin.password on $host). The realm's issuer:"
echo "  $issuer"
echo "Its CA certificate is in $ca_file. Next:"
echo "  scripts/keycloak-user.sh${IDENTITY:+ -i $IDENTITY} $TARGET add <name> [--admin]"
echo "  scripts/deploy-remote.sh [-i key] user@server --keycloak $issuer --keycloak-ca $ca_file"
echo "  scripts/connect-ui.sh [-i key] user@server --keycloak $issuer --keycloak-ca $ca_file"
