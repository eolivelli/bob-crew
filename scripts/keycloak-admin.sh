#!/bin/bash
# Runs inside the bobcrew-keycloak container, fed to bash by scripts/keycloak-user.sh: manages the users of the
# bobcrew realm with Keycloak's kcadm.sh. Not meant to be run by hand.
#
#   keycloak-admin.sh <server-url> <use-ca: true|false> <command> [name] [settings...]
#
# The admin password comes in KC_CLI_PASSWORD (kcadm.sh reads it from there), never on a command line; so does
# the new user's password, which goes to Keycloak on standard input. Every argument was checked by
# keycloak-user.sh against strict patterns, so none needs quoting in JSON.
set -euo pipefail

server="$1" use_ca="$2" command="$3"
shift 3
name="${1:-}"
[ $# -gt 0 ] && shift

REALM=bobcrew
work=$(mktemp -d /tmp/kcadm.XXXXXX)
trap 'rm -rf "$work"' EXIT
config="$work/kcadm.config"

kc() { /opt/keycloak/bin/kcadm.sh "$@" --config "$config"; }

if [ "$use_ca" = true ]; then
  keytool -importcert -noprompt -alias bobcrew-ca -file /opt/keycloak/conf/bobcrew-ca.crt \
    -keystore "$work/trust.p12" -storetype PKCS12 -storepass bobcrew-trust >/dev/null 2>&1
  kc config truststore --trustpass bobcrew-trust "$work/trust.p12" >/dev/null
fi
kc config credentials --server "$server" --realm master --user admin >/dev/null 2>&1 \
  || { echo "error: cannot log in to Keycloak as admin (has admin.password changed?)" >&2; exit 1; }

user_id() {
  kc get users -r "$REALM" -q username="$1" -q exact=true --fields id --format csv --noquotes | head -n 1
}

need_user() {
  id=$(user_id "$name")
  [ -n "$id" ] || { echo "error: no user $name" >&2; exit 1; }
}

# A random password that the user must change at the first login; printed once, never stored.
new_password() {
  password=$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n' | head -c 20)
  printf '{"type":"password","value":"%s","temporary":true}' "$password" \
    | kc update "users/$id/reset-password" -r "$REALM" -f - >/dev/null
  echo "temporary password for $name (to change at the first login): $password"
}

# settings: admin=true|false, caps=<comma list or empty>, max_sessions= max_memory= max_cpus= (empty: remove),
# email= first= last=
apply() {
  local updates=() roles_add=() roles_remove=()
  for setting in "$@"; do
    key="${setting%%=*}" value="${setting#*=}"
    case "$key" in
      admin)
        if [ "$value" = true ]; then roles_add+=(bobcrew-admin); else roles_remove+=(bobcrew-admin); fi ;;
      caps)
        for cap in docker libvirt; do
          if [[ ",$value," == *",$cap,"* ]]; then roles_add+=("bobcrew-cap-$cap"); else roles_remove+=("bobcrew-cap-$cap"); fi
        done ;;
      max_sessions|max_memory|max_cpus)
        if [ -n "$value" ]; then updates+=(-s "attributes.bobcrew_$key=[\"$value\"]")
        else updates+=(-s "attributes.bobcrew_$key=[]"); fi ;;
      email) updates+=(-s "email=$value" -s emailVerified=true) ;;
      first) updates+=(-s "firstName=$value") ;;
      last) updates+=(-s "lastName=$value") ;;
    esac
  done
  if [ ${#updates[@]} -gt 0 ]; then
    kc update "users/$id" -r "$REALM" "${updates[@]}" >/dev/null
  fi
  if [ ${#roles_add[@]} -gt 0 ]; then
    args=()
    for role in "${roles_add[@]}"; do args+=(--rolename "$role"); done
    kc add-roles -r "$REALM" --uid "$id" "${args[@]}"
  fi
  if [ ${#roles_remove[@]} -gt 0 ]; then
    args=()
    for role in "${roles_remove[@]}"; do args+=(--rolename "$role"); done
    kc remove-roles -r "$REALM" --uid "$id" "${args[@]}"
  fi
}

show() {
  # all of it: kcadm's --fields leaves attributes out
  kc get "users/$id" -r "$REALM"
  echo "roles: $(kc get-roles -r "$REALM" --uid "$id" --fields name --format csv --noquotes \
    | { grep '^bobcrew-' || true; } | sort | tr '\n' ' ')"
}

case "$command" in
  add)
    [ -z "$(user_id "$name")" ] || { echo "error: the user $name exists already; use update" >&2; exit 1; }
    printf '{"username":"%s","enabled":true}' "$name" | kc create users -r "$REALM" -f - >/dev/null
    need_user
    kc add-roles -r "$REALM" --uid "$id" --rolename bobcrew-user
    apply "$@"
    show
    new_password
    ;;
  update)
    need_user
    apply "$@"
    show
    ;;
  show)
    need_user
    show
    ;;
  password)
    need_user
    new_password
    ;;
  disable|enable)
    need_user
    kc update "users/$id" -r "$REALM" -s enabled=$([ "$command" = enable ] && echo true || echo false) >/dev/null
    echo "$name: ${command}d"
    ;;
  delete)
    need_user
    kc delete "users/$id" -r "$REALM"
    echo "$name: deleted"
    ;;
  list)
    printf '%-24s %-8s %s\n' USER ENABLED ROLES
    kc get users -r "$REALM" --fields id,username,enabled --format csv --noquotes --limit 1000 \
      | while IFS=, read -r uid username enabled; do
          # kcadm.sh reads standard input, which is the list here
          roles=$(kc get-roles -r "$REALM" --uid "$uid" --fields name --format csv --noquotes < /dev/null \
            | { grep '^bobcrew-' || true; } | sort | tr '\n' ' ')
          printf '%-24s %-8s %s\n' "$username" "$enabled" "$roles"
        done
    ;;
  *)
    echo "error: unknown command $command" >&2
    exit 1
    ;;
esac
