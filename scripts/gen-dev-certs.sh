#!/usr/bin/env bash
# Creates a private CA and a server certificate for bob-crew, plus a JWT signing secret.
#
#   scripts/gen-dev-certs.sh [output-dir] [extra-host-name-or-ip ...]
#
# The server certificate is valid for localhost, 127.0.0.1, this machine's host name and every
# extra name given. Existing files are kept, so running it again is safe.
#
#   ca.crt      give this to UI users (bobcrew.ui.ca-file)
#   ca.key      keep private; only needed to sign more certificates
#   server.crt  BOBCREW_TLS_CERT
#   server.key  BOBCREW_TLS_KEY
#   jwt.secret  BOBCREW_JWT_SECRET_FILE
set -euo pipefail

out="${1:-certs}"
shift || true
mkdir -p "$out"
umask 077

if [[ ! -f "$out/ca.key" ]]; then
  openssl req -x509 -newkey rsa:3072 -nodes -days 3650 \
    -subj "/CN=bob-crew dev CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -keyout "$out/ca.key" -out "$out/ca.crt" 2>/dev/null
  echo "created $out/ca.crt"
fi

if [[ ! -f "$out/server.key" ]]; then
  san="DNS:localhost,IP:127.0.0.1,DNS:$(hostname)"
  for name in "$@"; do
    if [[ "$name" =~ ^[0-9.]+$ || "$name" == *:* ]]; then
      san="$san,IP:$name"
    else
      san="$san,DNS:$name"
    fi
  done
  openssl req -newkey rsa:3072 -nodes -subj "/CN=bob-crew server" \
    -keyout "$out/server.key" -out "$out/server.csr" 2>/dev/null
  openssl x509 -req -in "$out/server.csr" -CA "$out/ca.crt" -CAkey "$out/ca.key" -CAcreateserial \
    -days 825 -out "$out/server.crt" \
    -extfile <(printf 'subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$san") 2>/dev/null
  rm -f "$out/server.csr"
  echo "created $out/server.crt for $san"
fi

if [[ ! -f "$out/jwt.secret" ]]; then
  openssl rand -base64 48 > "$out/jwt.secret"
  echo "created $out/jwt.secret"
fi

chmod 644 "$out/ca.crt" "$out/server.crt"
