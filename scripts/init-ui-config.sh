#!/usr/bin/env bash
# Prepares ~/.bobcrew/ for the bob-crew UI from ui.template/: ui.properties, an empty secrets.json and the
# example roles.
# Files that already exist are left alone, so it is safe to run again (to pick up new roles, say).
#
#   scripts/init-ui-config.sh [target-dir]     (default: ~/.bobcrew)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
template="$(dirname "$here")/ui.template"
target="${1:-$HOME/.bobcrew}"

mkdir -p "$target/roles" "$target/user-profile"
chmod 700 "$target" "$target/user-profile"

copy() {
  if [[ -e "$2" ]]; then
    echo "kept     $2 (already there)"
  else
    install -m "$3" "$1" "$2"
    echo "created  $2"
  fi
}

copy "$template/ui.properties" "$target/ui.properties" 600
copy "$template/secrets.json" "$target/secrets.json" 600
for role in "$template"/roles/*.md; do
  copy "$role" "$target/roles/$(basename "$role")" 644
done

cat <<EOT

Next:
  - edit $target/ui.properties (server URL), and put the token in $target/token (chmod 600)
    and the server's CA certificate in $target/ca.crt
  - for the Developer role, put GIT_USER_NAME, GIT_USER_EMAIL and GH_TOKEN in $target/secrets.json,
    for example {"GIT_USER_NAME": "Ada Lovelace", "GIT_USER_EMAIL": "ada@example.com", "GH_TOKEN": "..."}
    (keep it chmod 600)
  - put custom dotfiles or settings (such as .m2/settings.xml) in $target/user-profile/
    to have them copied to /home/bob/ in session containers
  - the role's image must be on the server: scripts/build-images.sh --only java-python
EOT
