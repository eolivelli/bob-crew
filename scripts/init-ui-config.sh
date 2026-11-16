#!/usr/bin/env bash
# Prepares ~/.bobcrew/ for the bob-crew UI from ui.template/: ui.properties and the example roles.
# Files that already exist are left alone, so it is safe to run again (to pick up new roles, say).
#
#   scripts/init-ui-config.sh [target-dir]     (default: ~/.bobcrew)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
template="$(dirname "$here")/ui.template"
target="${1:-$HOME/.bobcrew}"

mkdir -p "$target/roles"
chmod 700 "$target"

copy() {
  if [[ -e "$2" ]]; then
    echo "kept     $2 (already there)"
  else
    install -m "$3" "$1" "$2"
    echo "created  $2"
  fi
}

copy "$template/ui.properties" "$target/ui.properties" 600
for role in "$template"/roles/*.md; do
  copy "$role" "$target/roles/$(basename "$role")" 644
done

cat <<EOT

Next:
  - edit $target/ui.properties (server URL), and put the token in $target/token (chmod 600)
    and the server's CA certificate in $target/ca.crt
  - for the Developer role, export GIT_USER_NAME, GIT_USER_EMAIL and GH_TOKEN where the UI starts
  - the role's image must be on the server: scripts/build-images.sh --only java-python
EOT
