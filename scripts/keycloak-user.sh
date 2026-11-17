#!/usr/bin/env bash
# Manages the users of bob-crew's Keycloak realm, on a Keycloak started by scripts/deploy-keycloak.sh, over ssh.
#
#   scripts/keycloak-user.sh [-i identity-file] user@host add NAME [settings]
#   scripts/keycloak-user.sh [-i identity-file] user@host update NAME [settings]
#   scripts/keycloak-user.sh [-i identity-file] user@host show|password|disable|enable|delete NAME
#   scripts/keycloak-user.sh [-i identity-file] user@host list
#
# add creates the user with the role bobcrew-user (needed to use bob-crew servers) and prints a temporary
# password, once: give it to the user, who must change it at the first login. password makes a new one.
#
# Settings:
#   --admin / --no-admin   the bobcrew-admin role: see, use, share and close every session
#   --cap docker,libvirt   exactly these capability roles (--cap '' for none); a server must allow the
#                          capability too (BOBCREW_ALLOWED_CAPABILITIES)
#   --max-sessions N       the user's own limits, instead of each server's; '' removes them. Servers cap memory
#   --max-memory 16GB      and CPUs at their ceilings (BOBCREW_CEILING_MEMORY, BOBCREW_CEILING_CPUS, by default
#   --max-cpus 4           the machine's)
#   --email E --first F --last L
#
# Everything is done on the Keycloak machine with Keycloak's kcadm.sh, logged in as its admin with the password
# that deploy-keycloak.sh left there; the password never leaves that machine. The same can be done in the admin
# console (https://host:8543/admin, realm bobcrew).
#
# Settings (environment): BOBCREW_KEYCLOAK_DIR, as for deploy-keycloak.sh.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=remote-common.sh
source "$here/remote-common.sh"

KC_DIR="${BOBCREW_KEYCLOAK_DIR:-bobcrew-keycloak}"

usage() {
  sed -n '3,7p' "$0" | sed 's/^# *//' >&2
}

# the first plain word is the target, then the command and the name; options are settings
ssh_args=()
words=()
settings=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -i) [[ $# -ge 2 ]] || die "-i needs a file"; ssh_args+=("$1" "$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --admin) settings+=(admin=true); shift ;;
    --no-admin) settings+=(admin=false); shift ;;
    --cap|--caps)
      [[ $# -ge 2 ]] || die "$1 needs a value"
      [[ "$2" =~ ^((docker|libvirt)(,(docker|libvirt))*)?$ ]] || die "--cap takes docker, libvirt or both, comma separated"
      settings+=("caps=$2"); shift 2 ;;
    --max-sessions)
      [[ $# -ge 2 && "$2" =~ ^([0-9]{1,4})?$ ]] || die "--max-sessions takes a number (or '' to remove it)"
      settings+=("max_sessions=$2"); shift 2 ;;
    --max-memory)
      [[ $# -ge 2 ]] || die "--max-memory needs a value"
      value=$(tr '[:lower:]' '[:upper:]' <<<"$2")
      [[ "$value" =~ ^([0-9]{1,6}(B|KB|MB|GB|TB))?$ ]] || die "--max-memory takes a size such as 16GB (or '' to remove it)"
      settings+=("max_memory=$value"); shift 2 ;;
    --max-cpus)
      [[ $# -ge 2 && "$2" =~ ^([0-9]{1,4}(\.[0-9]{1,2})?)?$ ]] || die "--max-cpus takes a number such as 4 or 1.5 (or '' to remove it)"
      settings+=("max_cpus=$2"); shift 2 ;;
    --email)
      [[ $# -ge 2 && "$2" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || die "--email takes an email address"
      settings+=("email=$2"); shift 2 ;;
    --first|--last)
      [[ $# -ge 2 && "$2" =~ ^[A-Za-z][A-Za-z\ .\'-]{0,63}$ ]] || die "$1 takes a name (letters, spaces, . ' -)"
      settings+=("${1#--}=$2"); shift 2 ;;
    -*) die "unknown option: $1" ;;
    *) words+=("$1"); shift ;;
  esac
done
[[ ${#words[@]} -ge 2 ]] || { usage; exit 1; }
target="${words[0]}" command="${words[1]}" name="${words[2]:-}"
[[ ${#words[@]} -le 3 ]] || die "unexpected argument: ${words[3]}"
case "$command" in
  list) [[ -z "$name" ]] || die "list takes no name" ;;
  add|update|show|password|disable|enable|delete)
    [[ -n "$name" ]] || die "$command needs a user name"
    # Keycloak keeps user names in lower case
    [[ "$name" =~ ^[a-z0-9][a-z0-9._-]{1,62}$ ]] \
      || die "user names are 2 to 63 lower-case letters, digits, '.', '_' or '-'" ;;
  *) die "unknown command: $command" ;;
esac
if [[ ${#settings[@]} -gt 0 && "$command" != add && "$command" != update ]]; then
  die "settings go with add or update"
fi
[[ "$KC_DIR" =~ ^[A-Za-z0-9._/-]+$ && "$KC_DIR" != /* && "$KC_DIR" != *..* ]] \
  || die "BOBCREW_KEYCLOAK_DIR must be a plain directory under the remote home"

parse_target "${ssh_args[@]}" "$target"
trap close_ssh EXIT

rscp "$here/keycloak-admin.sh" "$TARGET:$KC_DIR/scripts/keycloak-admin.sh"
rssh bash -l -s -- "$KC_DIR" "$command" "$name" "${settings[@]}" <<'EOF'
set -euo pipefail
cd "$1" 2>/dev/null || { echo "error: no $1 here; run scripts/deploy-keycloak.sh first" >&2; exit 1; }
shift
[ -s admin.password ] || { echo "error: no admin.password; run scripts/deploy-keycloak.sh first" >&2; exit 1; }
[ "$(docker inspect -f '{{.State.Running}}' bobcrew-keycloak 2>/dev/null)" = true ] \
  || { echo "error: Keycloak is not running; run scripts/deploy-keycloak.sh" >&2; exit 1; }
# a certificate of the project's CA names localhost too; one from elsewhere is checked as the public address
if [ -f certs/provided ]; then
  url=$(docker inspect -f '{{range .Args}}{{println .}}{{end}}' bobcrew-keycloak | sed -n 's/^--hostname=//p')
  use_ca=false
else
  url=https://localhost:8443
  use_ca=true
fi
KC_CLI_PASSWORD="$(cat admin.password)" \
  docker exec -i -e KC_CLI_PASSWORD bobcrew-keycloak bash -s -- "$url" "$use_ca" "$@" < scripts/keycloak-admin.sh
EOF
